/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.cdc.base.source.reader.external;

import org.apache.seatunnel.common.utils.SeaTunnelException;
import org.apache.seatunnel.connectors.cdc.base.source.offset.Offset;
import org.apache.seatunnel.connectors.cdc.base.source.split.CompletedSnapshotSplitInfo;
import org.apache.seatunnel.connectors.cdc.base.source.split.IncrementalSplit;
import org.apache.seatunnel.connectors.cdc.base.source.split.SourceRecords;
import org.apache.seatunnel.connectors.cdc.base.source.split.SourceSplitBase;

import org.apache.kafka.connect.source.SourceRecord;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.relational.TableId;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.apache.seatunnel.connectors.cdc.base.utils.SourceRecordUtils.getTableId;

/**
 * Fetcher to fetch data from table split, the split is the incremental split {@link
 * IncrementalSplit}.
 */
@Slf4j
public class IncrementalSourceStreamFetcher implements Fetcher<SourceRecords, SourceSplitBase> {
    private final FetchTask.Context taskContext;
    private final ExecutorService executorService;
    // has entered pure binlog mode
    private final Set<TableId> pureBinlogPhaseTables;
    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private volatile Throwable readException;

    private FetchTask<SourceSplitBase> streamFetchTask;

    private IncrementalSplit currentIncrementalSplit;

    private Offset splitStartWatermark;

    // maximum watermark for each table
    private Map<TableId, Offset> maxSplitHighWatermarkMap;
    // finished spilt info
    private Map<TableId, List<CompletedSnapshotSplitInfo>> finishedSplitsInfo;

    private static final long READER_CLOSE_TIMEOUT_SECONDS = 30L;

    public IncrementalSourceStreamFetcher(FetchTask.Context taskContext, int subTaskId) {
        this.taskContext = taskContext;
        ThreadFactory threadFactory =
                new ThreadFactoryBuilder().setNameFormat("debezium-reader-" + subTaskId).build();
        this.executorService = Executors.newSingleThreadExecutor(threadFactory);
        this.pureBinlogPhaseTables = new HashSet<>();
    }

    @Override
    public void submitTask(FetchTask<SourceSplitBase> fetchTask) {
        this.streamFetchTask = fetchTask;
        this.currentIncrementalSplit = fetchTask.getSplit().asIncrementalSplit();
        configureFilter();
        taskContext.configure(currentIncrementalSplit);
        this.queue = taskContext.getQueue();
        executorService.submit(
                () -> {
                    try {
                        streamFetchTask.execute(taskContext);
                    } catch (Exception e) {
                        log.error(
                                String.format(
                                        "Execute stream read task for incremental split %s fail",
                                        currentIncrementalSplit),
                                e);
                        readException = e;
                    }
                });
    }

    @Override
    public boolean isFinished() {
        return currentIncrementalSplit == null || !streamFetchTask.isRunning();
    }

    @Override
    public Iterator<SourceRecords> pollSplitRecords()
            throws InterruptedException, SeaTunnelException {
        checkReadException();
        final List<SourceRecord> sourceRecords = new ArrayList<>();
        if (streamFetchTask.isRunning()) {
            List<DataChangeEvent> batch = queue.poll();
            for (DataChangeEvent event : batch) {
                if (shouldEmit(event.getRecord())) {
                    sourceRecords.add(event.getRecord());
                }
            }
        }
        List<SourceRecords> sourceRecordsSet = new ArrayList<>();
        sourceRecordsSet.add(new SourceRecords(sourceRecords));
        return sourceRecordsSet.iterator();
    }

    private void checkReadException() {
        if (readException != null) {
            throw new SeaTunnelException(
                    String.format(
                            "Read split %s error due to %s.",
                            currentIncrementalSplit, readException.getMessage()),
                    readException);
        }
    }

    @Override
    public void close() {
        try {
            if (taskContext != null) {
                taskContext.close();
            }
            if (streamFetchTask != null) {
                streamFetchTask.shutdown();
            }
            if (executorService != null) {
                executorService.shutdown();
                if (!executorService.awaitTermination(
                        READER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn(
                            "Failed to close the stream fetcher in {} seconds. Service will execute force close(ExecutorService.shutdownNow)",
                            READER_CLOSE_TIMEOUT_SECONDS);
                    executorService.shutdownNow();
                }
            }
        } catch (Exception e) {
            log.error("Close stream fetcher error", e);
        }
    }

    /** Returns the record should emit or not. */
    private boolean shouldEmit(SourceRecord sourceRecord) {
        if (taskContext.isDataChangeRecord(sourceRecord)) {
            Offset position = taskContext.getStreamOffset(sourceRecord);
            TableId tableId = getTableId(sourceRecord);
            if (!taskContext.isExactlyOnce()) {
                log.trace(
                        "The table {} is not support exactly-once, so ignore the watermark check",
                        tableId);
                return position.isAfter(splitStartWatermark);
            }
            // check whether the pure binlog mode has been entered
            if (hasEnterPureBinlogPhase(tableId, position)) {
                return true;
            }
            // not enter pure binlog mode and need to check whether the current record meets the
            // emitting conditions.
            if (finishedSplitsInfo.containsKey(tableId)) {
                for (CompletedSnapshotSplitInfo splitInfo : finishedSplitsInfo.get(tableId)) {
                    if (taskContext.isRecordBetween(
                                    sourceRecord,
                                    splitInfo.getSplitStart(),
                                    splitInfo.getSplitEnd())
                            && position.isAfter(splitInfo.getWatermark().getHighWatermark())) {
                        return true;
                    }
                }
            }
            return false;
        }
        return true;
    }

    private boolean hasEnterPureBinlogPhase(TableId tableId, Offset position) {
        // only the table who captured snapshot splits need to filter
        if (pureBinlogPhaseTables.contains(tableId)) {
            return true;
        }
        // the existed tables those have finished snapshot reading
        if (maxSplitHighWatermarkMap.containsKey(tableId)
                && position.isAtOrAfter(maxSplitHighWatermarkMap.get(tableId))) {
            pureBinlogPhaseTables.add(tableId);
            return true;
        }
        return false;
    }

    private void configureFilter() {
        splitStartWatermark = currentIncrementalSplit.getStartupOffset();
        Map<TableId, List<CompletedSnapshotSplitInfo>> splitsInfoMap = new HashMap<>();
        Map<TableId, Offset> tableIdBinlogPositionMap = new HashMap<>();
        List<CompletedSnapshotSplitInfo> completedSnapshotSplitInfos =
                currentIncrementalSplit.getCompletedSnapshotSplitInfos();

        // latest-offset mode
        if (completedSnapshotSplitInfos.isEmpty()) {
            for (TableId tableId : currentIncrementalSplit.getTableIds()) {
                tableIdBinlogPositionMap.put(tableId, currentIncrementalSplit.getStartupOffset());
            }
        }

        // calculate the max high watermark of every table
        for (CompletedSnapshotSplitInfo finishedSplitInfo : completedSnapshotSplitInfos) {
            TableId tableId = finishedSplitInfo.getTableId();
            List<CompletedSnapshotSplitInfo> list =
                    splitsInfoMap.getOrDefault(tableId, new ArrayList<>());
            list.add(finishedSplitInfo);
            splitsInfoMap.put(tableId, list);

            Offset highWatermark = finishedSplitInfo.getWatermark().getHighWatermark();
            Offset maxHighWatermark = tableIdBinlogPositionMap.get(tableId);
            if (maxHighWatermark == null || highWatermark.isAfter(maxHighWatermark)) {
                tableIdBinlogPositionMap.put(tableId, highWatermark);
            }
        }
        this.finishedSplitsInfo = splitsInfoMap;
        this.maxSplitHighWatermarkMap = tableIdBinlogPositionMap;
        this.pureBinlogPhaseTables.clear();
    }
}
