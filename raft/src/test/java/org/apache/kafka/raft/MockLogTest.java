/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.raft;

import org.apache.kafka.common.message.LeaderChangeMessageData;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.utils.Utils;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.apache.kafka.common.record.ControlRecordUtils.deserialize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MockLogTest {

    private MockLog log;

    @Before
    public void setup() {
        log = new MockLog();
    }

    @Test
    public void testAppendAsLeaderHelper() {
        int epoch = 2;
        SimpleRecord recordOne = new SimpleRecord("one".getBytes());
        log.appendAsLeader(Collections.singleton(recordOne), epoch);
        assertEquals(epoch, log.lastFetchedEpoch());
        assertEquals(0L, log.startOffset());
        assertEquals(1L, log.endOffset());

        Records records = log.read(0, OptionalLong.of(1));
        List<? extends RecordBatch> batches = Utils.toList(records.batches().iterator());

        RecordBatch batch = batches.get(0);
        assertEquals(0, batch.baseOffset());
        assertEquals(0, batch.lastOffset());

        List<Record> fetchedRecords = Utils.toList(batch.iterator());
        assertEquals(1, fetchedRecords.size());
        assertEquals(recordOne, new SimpleRecord(fetchedRecords.get(0)));
        assertEquals(0, fetchedRecords.get(0).offset());

        SimpleRecord recordTwo = new SimpleRecord("two".getBytes());
        SimpleRecord recordThree = new SimpleRecord("three".getBytes());
        log.appendAsLeader(Arrays.asList(recordTwo, recordThree), epoch);
        assertEquals(0L, log.startOffset());
        assertEquals(3L, log.endOffset());

        records = log.read(0, OptionalLong.empty());
        batches = Utils.toList(records.batches().iterator());
        assertEquals(2, batches.size());

        fetchedRecords = Utils.toList(records.records().iterator());
        assertEquals(3, fetchedRecords.size());
        assertEquals(recordOne, new SimpleRecord(fetchedRecords.get(0)));
        assertEquals(0, fetchedRecords.get(0).offset());

        assertEquals(recordTwo, new SimpleRecord(fetchedRecords.get(1)));
        assertEquals(1, fetchedRecords.get(1).offset());

        assertEquals(recordThree, new SimpleRecord(fetchedRecords.get(2)));
        assertEquals(2, fetchedRecords.get(2).offset());
    }

    @Test
    public void testTruncateTo() {
        int epoch = 2;
        SimpleRecord recordOne = new SimpleRecord("one".getBytes());
        SimpleRecord recordTwo = new SimpleRecord("two".getBytes());
        log.appendAsLeader(Arrays.asList(recordOne, recordTwo), epoch);

        SimpleRecord recordThree = new SimpleRecord("three".getBytes());
        log.appendAsLeader(Collections.singleton(recordThree), epoch);

        assertEquals(0L, log.startOffset());
        assertEquals(3L, log.endOffset());

        log.truncateTo(2);
        assertEquals(0L, log.startOffset());
        assertEquals(2L, log.endOffset());

        log.truncateTo(1);
        assertEquals(0L, log.startOffset());
        assertEquals(0L, log.endOffset());
    }

    @Test
    public void testUpdateHighWatermark() {
        long newOffset = 5L;
        log.updateHighWatermark(newOffset);
        assertEquals(newOffset, log.highWatermark());
    }

    @Test
    public void testDecrementHighWatermark() {
        log.updateHighWatermark(4);
        assertThrows(IllegalArgumentException.class, () -> log.updateHighWatermark(3));
    }

    @Test
    public void testAssignEpochStartOffset() {
        log.assignEpochStartOffset(2, 0);
        assertEquals(2, log.lastFetchedEpoch());
    }

    @Test
    public void testAssignEpochStartOffsetNotEqualToEndOffset() {
        assertThrows(IllegalArgumentException.class, () -> log.assignEpochStartOffset(2, 1));
    }

    @Test
    public void testAppendAsLeader() {
        // The record passed-in offsets are not going to affect the eventual offsets.
        final long initialOffset = 5L;
        SimpleRecord recordFoo = new SimpleRecord("foo".getBytes());
        final int currentEpoch = 3;
        log.appendAsLeader(MemoryRecords.withRecords(initialOffset, CompressionType.NONE, recordFoo), currentEpoch);

        assertEquals(0, log.startOffset());
        assertEquals(1, log.endOffset());
        assertEquals(currentEpoch, log.lastFetchedEpoch());

        Records records = log.read(0, OptionalLong.empty());
        List<ByteBuffer> extractRecords = new ArrayList<>();
        for (Record record : records.records()) {
            extractRecords.add(record.value());
        }

        assertEquals(1, extractRecords.size());
        assertEquals(recordFoo.value(), extractRecords.get(0));
        assertEquals(Optional.of(new OffsetAndEpoch(1, currentEpoch)), log.endOffsetForEpoch(currentEpoch));
    }

    @Test
    public void testAppendControlRecord() {
        final long initialOffset = 5L;
        final int currentEpoch = 3;
        LeaderChangeMessageData messageData =  new LeaderChangeMessageData().setLeaderId(0);
        log.appendAsLeader(MemoryRecords.withLeaderChangeMessage(
            initialOffset, 2, messageData), currentEpoch);

        assertEquals(0, log.startOffset());
        assertEquals(1, log.endOffset());
        assertEquals(currentEpoch, log.lastFetchedEpoch());

        Records records = log.read(0, OptionalLong.empty());
        for (RecordBatch batch : records.batches()) {
            assertTrue(batch.isControlBatch());
        }
        List<ByteBuffer> extractRecords = new ArrayList<>();
        for (Record record : records.records()) {
            LeaderChangeMessageData deserializedData = deserialize(record);
            assertEquals(deserializedData, messageData);
            extractRecords.add(record.value());
        }

        assertEquals(1, extractRecords.size());
        assertEquals(Optional.of(new OffsetAndEpoch(1, currentEpoch)), log.endOffsetForEpoch(currentEpoch));
    }

    @Test
    public void testAppendAsFollower() {
        final long initialOffset = 5L;
        final int epoch = 3;
        SimpleRecord recordFoo = new SimpleRecord("foo".getBytes());
        log.appendAsFollower(MemoryRecords.withRecords(initialOffset, CompressionType.NONE, epoch, recordFoo));

        assertEquals(5L, log.startOffset());
        assertEquals(6L, log.endOffset());
        assertEquals(3, log.lastFetchedEpoch());

        Records records = log.read(0, OptionalLong.empty());
        List<ByteBuffer> extractRecords = new ArrayList<>();
        for (Record record : records.records()) {
            extractRecords.add(record.value());
        }

        assertEquals(1, extractRecords.size());
        assertEquals(recordFoo.value(), extractRecords.get(0));
        assertEquals(Optional.of(new OffsetAndEpoch(5, 0)), log.endOffsetForEpoch(0));
        assertEquals(Optional.of(new OffsetAndEpoch(log.endOffset(), epoch)), log.endOffsetForEpoch(epoch));
    }

    @Test
    public void testReadRecords() {
        int epoch = 2;

        ByteBuffer recordOneBuffer = ByteBuffer.allocate(4);
        recordOneBuffer.putInt(1);
        SimpleRecord recordOne = new SimpleRecord(recordOneBuffer);

        ByteBuffer recordTwoBuffer = ByteBuffer.allocate(4);
        recordTwoBuffer.putInt(2);
        SimpleRecord recordTwo = new SimpleRecord(recordTwoBuffer);

        log.appendAsLeader(Arrays.asList(recordOne, recordTwo), epoch);

        Records records = log.read(0, OptionalLong.empty());

        List<ByteBuffer> extractRecords = new ArrayList<>();
        for (Record record : records.records()) {
            extractRecords.add(record.value());
        }
        assertEquals(Arrays.asList(recordOne.value(), recordTwo.value()), extractRecords);
    }
}