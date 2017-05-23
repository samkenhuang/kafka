/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package kafka.coordinator.group

import kafka.api.ApiVersion
import kafka.cluster.Partition
import kafka.common.OffsetAndMetadata
import kafka.log.{Log, LogAppendInfo}
import kafka.server.{FetchDataInfo, KafkaConfig, LogOffsetMetadata, ReplicaManager}
import kafka.utils.TestUtils.fail
import kafka.utils.{KafkaScheduler, MockTime, TestUtils, ZkUtils}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.{IsolationLevel, OffsetFetchResponse}
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.easymock.{Capture, EasyMock, IAnswer}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.{Before, Test}
import java.nio.ByteBuffer

import org.apache.kafka.common.internals.Topic

import scala.collection.JavaConverters._
import scala.collection._

class GroupMetadataManagerTest {

  var time: MockTime = null
  var replicaManager: ReplicaManager = null
  var groupMetadataManager: GroupMetadataManager = null
  var scheduler: KafkaScheduler = null
  var zkUtils: ZkUtils = null
  var partition: Partition = null

  val groupId = "foo"
  val groupPartitionId = 0
  val protocolType = "protocolType"
  val rebalanceTimeout = 60000
  val sessionTimeout = 10000

  @Before
  def setUp() {
    val config = KafkaConfig.fromProps(TestUtils.createBrokerConfig(nodeId = 0, zkConnect = ""))

    val offsetConfig = OffsetConfig(maxMetadataSize = config.offsetMetadataMaxSize,
      loadBufferSize = config.offsetsLoadBufferSize,
      offsetsRetentionMs = config.offsetsRetentionMinutes * 60 * 1000L,
      offsetsRetentionCheckIntervalMs = config.offsetsRetentionCheckIntervalMs,
      offsetsTopicNumPartitions = config.offsetsTopicPartitions,
      offsetsTopicSegmentBytes = config.offsetsTopicSegmentBytes,
      offsetsTopicReplicationFactor = config.offsetsTopicReplicationFactor,
      offsetsTopicCompressionCodec = config.offsetsTopicCompressionCodec,
      offsetCommitTimeoutMs = config.offsetCommitTimeoutMs,
      offsetCommitRequiredAcks = config.offsetCommitRequiredAcks)

    // make two partitions of the group topic to make sure some partitions are not owned by the coordinator
    zkUtils = EasyMock.createNiceMock(classOf[ZkUtils])
    EasyMock.expect(zkUtils.getTopicPartitionCount(Topic.GROUP_METADATA_TOPIC_NAME)).andReturn(Some(2))
    EasyMock.replay(zkUtils)

    time = new MockTime
    replicaManager = EasyMock.createNiceMock(classOf[ReplicaManager])
    groupMetadataManager = new GroupMetadataManager(0, ApiVersion.latestVersion, offsetConfig, replicaManager, zkUtils, time)
    partition = EasyMock.niceMock(classOf[Partition])
  }

  @Test
  def testLoadOffsetsWithoutGroup() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val startOffset = 15L

    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val offsetCommitRecords = createCommittedOffsetRecords(committedOffsets)
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE, offsetCommitRecords: _*)
    expectGroupMetadataLoad(groupMetadataTopicPartition, startOffset, records)

    EasyMock.replay(replicaManager)
    
    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testLoadTransactionalOffsetsWithoutGroup() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, committedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = true)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testDoNotLoadAbortedTransactionalOffsetCommits() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val abortedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, abortedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = false)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    // Since there are no committed offsets for the group, and there is no other group metadata, we don't expect the
    // group to be loaded.
    assertEquals(None, groupMetadataManager.getGroup(groupId))
  }

  @Test
  def testGroupLoadedWithPendingCommits(): Unit = {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val pendingOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, pendingOffsets)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    // The group should be loaded with pending offsets.
    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    // Ensure that no offsets are materialized, but that we have offsets pending.
    assertEquals(0, group.allOffsets.size)
    assertTrue(group.hasOffsets)
    assertTrue(group.hasPendingOffsetCommitsFromProducer(producerId))
  }

  @Test
  def testLoadWithCommittedAndAbortedTransactionalOffsetCommits() {
    // A test which loads a log with a mix of committed and aborted transactional offset committed messages.
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val abortedOffsets = Map(
      new TopicPartition("foo", 2) -> 231L,
      new TopicPartition("foo", 3) -> 4551L,
      new TopicPartition("bar", 1) -> 89921L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, abortedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = false)
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, committedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = true)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    // Ensure that only the committed offsets are materialized, and that there are no pending commits for the producer.
    // This allows us to be certain that the aborted offset commits are truly discarded.
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
    assertFalse(group.hasPendingOffsetCommitsFromProducer(producerId))
  }

  @Test
  def testLoadWithCommittedAndAbortedAndPendingTransactionalOffsetCommits() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val abortedOffsets = Map(
      new TopicPartition("foo", 2) -> 231L,
      new TopicPartition("foo", 3) -> 4551L,
      new TopicPartition("bar", 1) -> 89921L
    )

    val pendingOffsets = Map(
      new TopicPartition("foo", 3) -> 2312L,
      new TopicPartition("foo", 4) -> 45512L,
      new TopicPartition("bar", 2) -> 899212L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    val commitOffsetsLogPosition = nextOffset
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, committedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = true)
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, abortedOffsets)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = false)
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, pendingOffsets)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)

    // Ensure that only the committed offsets are materialized, and that there are no pending commits for the producer.
    // This allows us to be certain that the aborted offset commits are truly discarded.
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
      assertEquals(Some(commitOffsetsLogPosition), group.offsetWithRecordMetadata(topicPartition).head.appendedBatchOffset)
    }

    // We should have pending commits.
    assertTrue(group.hasPendingOffsetCommitsFromProducer(producerId))

    // The loaded pending commits should materialize after a commit marker comes in.
    groupMetadataManager.handleTxnCompletion(producerId, List(groupMetadataTopicPartition.partition).toSet, isCommit = true)
    assertFalse(group.hasPendingOffsetCommitsFromProducer(producerId))
    pendingOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testLoadTransactionalOffsetCommitsFromMultipleProducers(): Unit = {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val firstProducerId = 1000L
    val firstProducerEpoch: Short = 2
    val secondProducerId = 1001L
    val secondProducerEpoch: Short = 3

    val committedOffsetsFirstProducer = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val committedOffsetsSecondProducer = Map(
      new TopicPartition("foo", 2) -> 231L,
      new TopicPartition("foo", 3) -> 4551L,
      new TopicPartition("bar", 1) -> 89921L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0L

    val firstProduceRecordOffset = nextOffset
    nextOffset += appendTransactionalOffsetCommits(buffer, firstProducerId, firstProducerEpoch, nextOffset, committedOffsetsFirstProducer)
    nextOffset += completeTransactionalOffsetCommit(buffer, firstProducerId, firstProducerEpoch, nextOffset, isCommit = true)

    val secondProducerRecordOffset = nextOffset
    nextOffset += appendTransactionalOffsetCommits(buffer, secondProducerId, secondProducerEpoch, nextOffset, committedOffsetsSecondProducer)
    nextOffset += completeTransactionalOffsetCommit(buffer, secondProducerId, secondProducerEpoch, nextOffset, isCommit = true)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)

    // Ensure that only the committed offsets are materialized, and that there are no pending commits for the producer.
    // This allows us to be certain that the aborted offset commits are truly discarded.
    assertEquals(committedOffsetsFirstProducer.size + committedOffsetsSecondProducer.size, group.allOffsets.size)
    committedOffsetsFirstProducer.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
      assertEquals(Some(firstProduceRecordOffset), group.offsetWithRecordMetadata(topicPartition).head.appendedBatchOffset)
    }
    committedOffsetsSecondProducer.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
      assertEquals(Some(secondProducerRecordOffset), group.offsetWithRecordMetadata(topicPartition).head.appendedBatchOffset)
    }
  }

  @Test
  def testGroupLoadWithConsumerAndTransactionalOffsetCommitsConsumerWins(): Unit = {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val transactionalOffsetCommits = Map(
      new TopicPartition("foo", 0) -> 23L
    )

    val consumerOffsetCommits = Map(
      new TopicPartition("foo", 0) -> 24L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, transactionalOffsetCommits)
    val consumerRecordOffset = nextOffset
    nextOffset += appendConsumerOffsetCommit(buffer, nextOffset, consumerOffsetCommits)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = true)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    // The group should be loaded with pending offsets.
    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(1, group.allOffsets.size)
    assertTrue(group.hasOffsets)
    assertFalse(group.hasPendingOffsetCommitsFromProducer(producerId))
    assertEquals(consumerOffsetCommits.size, group.allOffsets.size)
    consumerOffsetCommits.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
      assertEquals(Some(consumerRecordOffset), group.offsetWithRecordMetadata(topicPartition).head.appendedBatchOffset)
    }
  }

  @Test
  def testGroupLoadWithConsumerAndTransactionalOffsetCommitsTransactionWins(): Unit = {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val producerId = 1000L
    val producerEpoch: Short = 2

    val transactionalOffsetCommits = Map(
      new TopicPartition("foo", 0) -> 23L
    )

    val consumerOffsetCommits = Map(
      new TopicPartition("foo", 0) -> 24L
    )

    val buffer = ByteBuffer.allocate(1024)
    var nextOffset = 0
    nextOffset += appendConsumerOffsetCommit(buffer, nextOffset, consumerOffsetCommits)
    nextOffset += appendTransactionalOffsetCommits(buffer, producerId, producerEpoch, nextOffset, transactionalOffsetCommits)
    nextOffset += completeTransactionalOffsetCommit(buffer, producerId, producerEpoch, nextOffset, isCommit = true)
    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    expectGroupMetadataLoad(groupMetadataTopicPartition, 0, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    // The group should be loaded with pending offsets.
    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(1, group.allOffsets.size)
    assertTrue(group.hasOffsets)
    assertFalse(group.hasPendingOffsetCommitsFromProducer(producerId))
    assertEquals(consumerOffsetCommits.size, group.allOffsets.size)
    transactionalOffsetCommits.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  private def appendConsumerOffsetCommit(buffer: ByteBuffer, baseOffset: Long, offsets: Map[TopicPartition, Long]) = {
    val builder = MemoryRecords.builder(buffer, CompressionType.NONE, TimestampType.LOG_APPEND_TIME, baseOffset)
    val commitRecords = createCommittedOffsetRecords(offsets)
    commitRecords.foreach(builder.append)
    builder.build()
    offsets.size
  }

  private def appendTransactionalOffsetCommits(buffer: ByteBuffer, producerId: Long, producerEpoch: Short,
                                               baseOffset: Long, offsets: Map[TopicPartition, Long]): Int = {
    val builder = MemoryRecords.builder(buffer, CompressionType.NONE, baseOffset, producerId, producerEpoch, 0, true)
    val commitRecords = createCommittedOffsetRecords(offsets)
    commitRecords.foreach(builder.append)
    builder.build()
    offsets.size
  }

  private def completeTransactionalOffsetCommit(buffer: ByteBuffer, producerId: Long, producerEpoch: Short, baseOffset: Long,
                                                isCommit: Boolean): Int = {
    val builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, baseOffset, time.milliseconds(), producerId, producerEpoch, 0, true, true,
      RecordBatch.NO_PARTITION_LEADER_EPOCH)
    val controlRecordType = if (isCommit) ControlRecordType.COMMIT else ControlRecordType.ABORT
    builder.appendEndTxnMarker(time.milliseconds(), new EndTransactionMarker(controlRecordType, 0))
    builder.build()
    1
  }

  @Test
  def testLoadOffsetsWithTombstones() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val startOffset = 15L

    val tombstonePartition = new TopicPartition("foo", 1)
    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      tombstonePartition -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val offsetCommitRecords = createCommittedOffsetRecords(committedOffsets)
    val tombstone = new SimpleRecord(GroupMetadataManager.offsetCommitKey(groupId, tombstonePartition), null)
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE,
      offsetCommitRecords ++ Seq(tombstone): _*)

    expectGroupMetadataLoad(groupMetadataTopicPartition, startOffset, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(committedOffsets.size - 1, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      if (topicPartition == tombstonePartition)
        assertEquals(None, group.offset(topicPartition))
      else
        assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testLoadOffsetsAndGroup() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val startOffset = 15L
    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )

    val offsetCommitRecords = createCommittedOffsetRecords(committedOffsets)
    val memberId = "98098230493"
    val groupMetadataRecord = buildStableGroupRecordWithMember(memberId)
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE,
      offsetCommitRecords ++ Seq(groupMetadataRecord): _*)

    expectGroupMetadataLoad(groupMetadataTopicPartition, startOffset, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Stable, group.currentState)
    assertEquals(memberId, group.leaderId)
    assertEquals(Set(memberId), group.allMembers)
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testLoadGroupWithTombstone() {
    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val startOffset = 15L

    val memberId = "98098230493"
    val groupMetadataRecord = buildStableGroupRecordWithMember(memberId)
    val groupMetadataTombstone = new SimpleRecord(GroupMetadataManager.groupMetadataKey(groupId), null)
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE,
      Seq(groupMetadataRecord, groupMetadataTombstone): _*)

    expectGroupMetadataLoad(groupMetadataTopicPartition, startOffset, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    assertEquals(None, groupMetadataManager.getGroup(groupId))
  }

  @Test
  def testOffsetWriteAfterGroupRemoved(): Unit = {
    // this test case checks the following scenario:
    // 1. the group exists at some point in time, but is later removed (because all members left)
    // 2. a "simple" consumer (i.e. not a consumer group) then uses the same groupId to commit some offsets

    val groupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val startOffset = 15L

    val committedOffsets = Map(
      new TopicPartition("foo", 0) -> 23L,
      new TopicPartition("foo", 1) -> 455L,
      new TopicPartition("bar", 0) -> 8992L
    )
    val offsetCommitRecords = createCommittedOffsetRecords(committedOffsets)
    val memberId = "98098230493"
    val groupMetadataRecord = buildStableGroupRecordWithMember(memberId)
    val groupMetadataTombstone = new SimpleRecord(GroupMetadataManager.groupMetadataKey(groupId), null)
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE,
      Seq(groupMetadataRecord, groupMetadataTombstone) ++ offsetCommitRecords: _*)

    expectGroupMetadataLoad(groupMetadataTopicPartition, startOffset, records)

    EasyMock.replay(replicaManager)

    groupMetadataManager.loadGroupsAndOffsets(groupMetadataTopicPartition, _ => ())

    val group = groupMetadataManager.getGroup(groupId).getOrElse(TestUtils.fail("Group was not loaded into the cache"))
    assertEquals(groupId, group.groupId)
    assertEquals(Empty, group.currentState)
    assertEquals(committedOffsets.size, group.allOffsets.size)
    committedOffsets.foreach { case (topicPartition, offset) =>
      assertEquals(Some(offset), group.offset(topicPartition).map(_.offset))
    }
  }

  @Test
  def testAddGroup() {
    val group = new GroupMetadata("foo")
    assertEquals(group, groupMetadataManager.addGroup(group))
    assertEquals(group, groupMetadataManager.addGroup(new GroupMetadata("foo")))
  }

  @Test
  def testStoreEmptyGroup() {
    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var maybeError: Option[Errors] = None
    def callback(error: Errors) {
      maybeError = Some(error)
    }

    val delayedStore = groupMetadataManager.prepareStoreGroup(group, Map.empty, callback).get
    groupMetadataManager.store(delayedStore)
    assertEquals(Some(Errors.NONE), maybeError)
  }

  @Test
  def testStoreGroupErrorMapping() {
    assertStoreGroupErrorMapping(Errors.NONE, Errors.NONE)
    assertStoreGroupErrorMapping(Errors.UNKNOWN_TOPIC_OR_PARTITION, Errors.COORDINATOR_NOT_AVAILABLE)
    assertStoreGroupErrorMapping(Errors.NOT_ENOUGH_REPLICAS, Errors.COORDINATOR_NOT_AVAILABLE)
    assertStoreGroupErrorMapping(Errors.NOT_ENOUGH_REPLICAS_AFTER_APPEND, Errors.COORDINATOR_NOT_AVAILABLE)
    assertStoreGroupErrorMapping(Errors.NOT_LEADER_FOR_PARTITION, Errors.NOT_COORDINATOR)
    assertStoreGroupErrorMapping(Errors.MESSAGE_TOO_LARGE, Errors.UNKNOWN)
    assertStoreGroupErrorMapping(Errors.RECORD_LIST_TOO_LARGE, Errors.UNKNOWN)
    assertStoreGroupErrorMapping(Errors.INVALID_FETCH_SIZE, Errors.UNKNOWN)
    assertStoreGroupErrorMapping(Errors.CORRUPT_MESSAGE, Errors.CORRUPT_MESSAGE)
  }

  private def assertStoreGroupErrorMapping(appendError: Errors, expectedError: Errors) {
    EasyMock.reset(replicaManager)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    expectAppendMessage(appendError)
    EasyMock.replay(replicaManager)

    var maybeError: Option[Errors] = None
    def callback(error: Errors) {
      maybeError = Some(error)
    }

    val delayedStore = groupMetadataManager.prepareStoreGroup(group, Map.empty, callback).get
    groupMetadataManager.store(delayedStore)
    assertEquals(Some(expectedError), maybeError)

    EasyMock.verify(replicaManager)
  }

  @Test
  def testStoreNonEmptyGroup() {
    val memberId = "memberId"
    val clientId = "clientId"
    val clientHost = "localhost"

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    val member = new MemberMetadata(memberId, groupId, clientId, clientHost, rebalanceTimeout, sessionTimeout,
      protocolType, List(("protocol", Array[Byte]())))
    member.awaitingJoinCallback = _ => ()
    group.add(member)
    group.transitionTo(PreparingRebalance)
    group.initNextGeneration()

    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var maybeError: Option[Errors] = None
    def callback(error: Errors) {
      maybeError = Some(error)
    }

    val delayedStore = groupMetadataManager.prepareStoreGroup(group, Map(memberId -> Array[Byte]()), callback).get
    groupMetadataManager.store(delayedStore)
    assertEquals(Some(Errors.NONE), maybeError)
  }

  @Test
  def testStoreNonEmptyGroupWhenCoordinatorHasMoved() {
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(None)
    val memberId = "memberId"
    val clientId = "clientId"
    val clientHost = "localhost"

    val group = new GroupMetadata(groupId)

    val member = new MemberMetadata(memberId, groupId, clientId, clientHost, rebalanceTimeout, sessionTimeout,
      protocolType, List(("protocol", Array[Byte]())))
    member.awaitingJoinCallback = _ => ()
    group.add(member)
    group.transitionTo(PreparingRebalance)
    group.initNextGeneration()

    EasyMock.replay(replicaManager)

    var maybeError: Option[Errors] = None
    def callback(error: Errors) {
      maybeError = Some(error)
    }

    groupMetadataManager.prepareStoreGroup(group, Map(memberId -> Array[Byte]()), callback)
    assertEquals(Some(Errors.NOT_COORDINATOR), maybeError)
    EasyMock.verify(replicaManager)
  }

  @Test
  def testCommitOffset() {
    val memberId = ""
    val generationId = -1
    val topicPartition = new TopicPartition("foo", 0)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    val offsets = immutable.Map(topicPartition -> OffsetAndMetadata(offset))

    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    val delayedStore = groupMetadataManager.prepareStoreOffsets(group, memberId, generationId, offsets, callback).get
    assertTrue(group.hasOffsets)

    groupMetadataManager.store(delayedStore)

    assertFalse(commitErrors.isEmpty)
    val maybeError = commitErrors.get.get(topicPartition)
    assertEquals(Some(Errors.NONE), maybeError)
    assertTrue(group.hasOffsets)

    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition)))
    val maybePartitionResponse = cachedOffsets.get(topicPartition)
    assertFalse(maybePartitionResponse.isEmpty)

    val partitionResponse = maybePartitionResponse.get
    assertEquals(Errors.NONE, partitionResponse.error)
    assertEquals(offset, partitionResponse.offset)
  }

  @Test
  def testCommitOffsetWhenCoordinatorHasMoved() {
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(None)
    val memberId = ""
    val generationId = -1
    val topicPartition = new TopicPartition("foo", 0)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    val offsets = immutable.Map(topicPartition -> OffsetAndMetadata(offset))

    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    groupMetadataManager.prepareStoreOffsets(group, memberId, generationId, offsets, callback)

    assertFalse(commitErrors.isEmpty)
    val maybeError = commitErrors.get.get(topicPartition)
    assertEquals(Some(Errors.NOT_COORDINATOR), maybeError)
    EasyMock.verify(replicaManager)
  }

  @Test
  def testCommitOffsetFailure() {
    assertCommitOffsetErrorMapping(Errors.UNKNOWN_TOPIC_OR_PARTITION, Errors.COORDINATOR_NOT_AVAILABLE)
    assertCommitOffsetErrorMapping(Errors.NOT_ENOUGH_REPLICAS, Errors.COORDINATOR_NOT_AVAILABLE)
    assertCommitOffsetErrorMapping(Errors.NOT_ENOUGH_REPLICAS_AFTER_APPEND, Errors.COORDINATOR_NOT_AVAILABLE)
    assertCommitOffsetErrorMapping(Errors.NOT_LEADER_FOR_PARTITION, Errors.NOT_COORDINATOR)
    assertCommitOffsetErrorMapping(Errors.MESSAGE_TOO_LARGE, Errors.INVALID_COMMIT_OFFSET_SIZE)
    assertCommitOffsetErrorMapping(Errors.RECORD_LIST_TOO_LARGE, Errors.INVALID_COMMIT_OFFSET_SIZE)
    assertCommitOffsetErrorMapping(Errors.INVALID_FETCH_SIZE, Errors.INVALID_COMMIT_OFFSET_SIZE)
    assertCommitOffsetErrorMapping(Errors.CORRUPT_MESSAGE, Errors.CORRUPT_MESSAGE)
  }

  private def assertCommitOffsetErrorMapping(appendError: Errors, expectedError: Errors): Unit = {
    EasyMock.reset(replicaManager)

    val memberId = ""
    val generationId = -1
    val topicPartition = new TopicPartition("foo", 0)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    val offsets = immutable.Map(topicPartition -> OffsetAndMetadata(offset))

    expectAppendMessage(appendError)
    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    val delayedStore = groupMetadataManager.prepareStoreOffsets(group, memberId, generationId, offsets, callback).get
    assertTrue(group.hasOffsets)

    groupMetadataManager.store(delayedStore)

    assertFalse(commitErrors.isEmpty)
    val maybeError = commitErrors.get.get(topicPartition)
    assertEquals(Some(expectedError), maybeError)
    assertFalse(group.hasOffsets)

    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition).map(_.offset))

    EasyMock.verify(replicaManager)
  }

  @Test
  def testExpireOffset() {
    val memberId = ""
    val generationId = -1
    val topicPartition1 = new TopicPartition("foo", 0)
    val topicPartition2 = new TopicPartition("foo", 1)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    // expire the offset after 1 millisecond
    val startMs = time.milliseconds
    val offsets = immutable.Map(
      topicPartition1 -> OffsetAndMetadata(offset, "", startMs, startMs + 1),
      topicPartition2 -> OffsetAndMetadata(offset, "", startMs, startMs + 3))

    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andStubReturn(Some(partition))
    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    val delayedStore = groupMetadataManager.prepareStoreOffsets(group, memberId, generationId, offsets, callback).get
    assertTrue(group.hasOffsets)

    groupMetadataManager.store(delayedStore)
    assertFalse(commitErrors.isEmpty)
    assertEquals(Some(Errors.NONE), commitErrors.get.get(topicPartition1))

    // expire only one of the offsets
    time.sleep(2)

    EasyMock.reset(partition)
    EasyMock.expect(partition.appendRecordsToLeader(EasyMock.anyObject(classOf[MemoryRecords]),
      isFromClient = EasyMock.eq(false), requiredAcks = EasyMock.anyInt()))
      .andReturn(LogAppendInfo.UnknownLogAppendInfo)
    EasyMock.replay(partition)

    groupMetadataManager.cleanupGroupMetadata()

    assertEquals(Some(group), groupMetadataManager.getGroup(groupId))
    assertEquals(None, group.offset(topicPartition1))
    assertEquals(Some(offset), group.offset(topicPartition2).map(_.offset))

    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition1, topicPartition2)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition1).map(_.offset))
    assertEquals(Some(offset), cachedOffsets.get(topicPartition2).map(_.offset))
  }

  @Test
  def testGroupMetadataRemoval() {
    val topicPartition1 = new TopicPartition("foo", 0)
    val topicPartition2 = new TopicPartition("foo", 1)

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)
    group.generationId = 5

    // expect the group metadata tombstone
    EasyMock.reset(partition)
    val recordsCapture: Capture[MemoryRecords] = EasyMock.newCapture()

    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andStubReturn(Some(partition))
    EasyMock.expect(partition.appendRecordsToLeader(EasyMock.capture(recordsCapture),
      isFromClient = EasyMock.eq(false), requiredAcks = EasyMock.anyInt()))
      .andReturn(LogAppendInfo.UnknownLogAppendInfo)
    EasyMock.replay(replicaManager, partition)

    groupMetadataManager.cleanupGroupMetadata()

    assertTrue(recordsCapture.hasCaptured)

    val records = recordsCapture.getValue.records.asScala.toList
    recordsCapture.getValue.batches.asScala.foreach { batch =>
      assertEquals(RecordBatch.CURRENT_MAGIC_VALUE, batch.magic)
      assertEquals(TimestampType.CREATE_TIME, batch.timestampType)
    }
    assertEquals(1, records.size)

    val metadataTombstone = records.head
    assertTrue(metadataTombstone.hasKey)
    assertFalse(metadataTombstone.hasValue)
    assertTrue(metadataTombstone.timestamp > 0)

    val groupKey = GroupMetadataManager.readMessageKey(metadataTombstone.key).asInstanceOf[GroupMetadataKey]
    assertEquals(groupId, groupKey.key)

    // the full group should be gone since all offsets were removed
    assertEquals(None, groupMetadataManager.getGroup(groupId))
    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition1, topicPartition2)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition1).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition2).map(_.offset))
  }

  @Test
  def testGroupMetadataRemovalWithLogAppendTime() {
    val topicPartition1 = new TopicPartition("foo", 0)
    val topicPartition2 = new TopicPartition("foo", 1)

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)
    group.generationId = 5

    // expect the group metadata tombstone
    EasyMock.reset(partition)
    val recordsCapture: Capture[MemoryRecords] = EasyMock.newCapture()

    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andStubReturn(Some(partition))
    EasyMock.expect(partition.appendRecordsToLeader(EasyMock.capture(recordsCapture),
      isFromClient = EasyMock.eq(false), requiredAcks = EasyMock.anyInt()))
      .andReturn(LogAppendInfo.UnknownLogAppendInfo)
    EasyMock.replay(replicaManager, partition)

    groupMetadataManager.cleanupGroupMetadata()

    assertTrue(recordsCapture.hasCaptured)

    val records = recordsCapture.getValue.records.asScala.toList
    recordsCapture.getValue.batches.asScala.foreach { batch =>
      assertEquals(RecordBatch.CURRENT_MAGIC_VALUE, batch.magic)
      // Use CREATE_TIME, like the producer. The conversion to LOG_APPEND_TIME (if necessary) happens automatically.
      assertEquals(TimestampType.CREATE_TIME, batch.timestampType)
    }
    assertEquals(1, records.size)

    val metadataTombstone = records.head
    assertTrue(metadataTombstone.hasKey)
    assertFalse(metadataTombstone.hasValue)
    assertTrue(metadataTombstone.timestamp > 0)

    val groupKey = GroupMetadataManager.readMessageKey(metadataTombstone.key).asInstanceOf[GroupMetadataKey]
    assertEquals(groupId, groupKey.key)

    // the full group should be gone since all offsets were removed
    assertEquals(None, groupMetadataManager.getGroup(groupId))
    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition1, topicPartition2)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition1).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition2).map(_.offset))
  }

  @Test
  def testExpireGroupWithOffsetsOnly() {
    // verify that the group is removed properly, but no tombstone is written if
    // this is a group which is only using kafka for offset storage

    val memberId = ""
    val generationId = -1
    val topicPartition1 = new TopicPartition("foo", 0)
    val topicPartition2 = new TopicPartition("foo", 1)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    // expire the offset after 1 millisecond
    val startMs = time.milliseconds
    val offsets = immutable.Map(
      topicPartition1 -> OffsetAndMetadata(offset, "", startMs, startMs + 1),
      topicPartition2 -> OffsetAndMetadata(offset, "", startMs, startMs + 3))

    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andStubReturn(Some(partition))
    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    val delayedStore = groupMetadataManager.prepareStoreOffsets(group, memberId, generationId, offsets, callback).get
    assertTrue(group.hasOffsets)

    groupMetadataManager.store(delayedStore)
    assertFalse(commitErrors.isEmpty)
    assertEquals(Some(Errors.NONE), commitErrors.get.get(topicPartition1))

    // expire all of the offsets
    time.sleep(4)

    // expect the offset tombstone
    EasyMock.reset(partition)
    val recordsCapture: Capture[MemoryRecords] = EasyMock.newCapture()

    EasyMock.expect(partition.appendRecordsToLeader(EasyMock.capture(recordsCapture),
      isFromClient = EasyMock.eq(false), requiredAcks = EasyMock.anyInt()))
      .andReturn(LogAppendInfo.UnknownLogAppendInfo)
    EasyMock.replay(partition)

    groupMetadataManager.cleanupGroupMetadata()

    assertTrue(recordsCapture.hasCaptured)

    // verify the tombstones are correct and only for the expired offsets
    val records = recordsCapture.getValue.records.asScala.toList
    assertEquals(2, records.size)
    records.foreach { message =>
      assertTrue(message.hasKey)
      assertFalse(message.hasValue)
      val offsetKey = GroupMetadataManager.readMessageKey(message.key).asInstanceOf[OffsetKey]
      assertEquals(groupId, offsetKey.key.group)
      assertEquals("foo", offsetKey.key.topicPartition.topic)
    }

    // the full group should be gone since all offsets were removed
    assertEquals(None, groupMetadataManager.getGroup(groupId))
    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition1, topicPartition2)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition1).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition2).map(_.offset))
  }

  @Test
  def testExpireOffsetsWithActiveGroup() {
    val memberId = "memberId"
    val clientId = "clientId"
    val clientHost = "localhost"
    val topicPartition1 = new TopicPartition("foo", 0)
    val topicPartition2 = new TopicPartition("foo", 1)
    val offset = 37

    groupMetadataManager.addPartitionOwnership(groupPartitionId)

    val group = new GroupMetadata(groupId)
    groupMetadataManager.addGroup(group)

    val member = new MemberMetadata(memberId, groupId, clientId, clientHost, rebalanceTimeout, sessionTimeout,
      protocolType, List(("protocol", Array[Byte]())))
    member.awaitingJoinCallback = _ => ()
    group.add(member)
    group.transitionTo(PreparingRebalance)
    group.initNextGeneration()

    // expire the offset after 1 millisecond
    val startMs = time.milliseconds
    val offsets = immutable.Map(
      topicPartition1 -> OffsetAndMetadata(offset, "", startMs, startMs + 1),
      topicPartition2 -> OffsetAndMetadata(offset, "", startMs, startMs + 3))

    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andStubReturn(Some(partition))
    expectAppendMessage(Errors.NONE)
    EasyMock.replay(replicaManager)

    var commitErrors: Option[immutable.Map[TopicPartition, Errors]] = None
    def callback(errors: immutable.Map[TopicPartition, Errors]) {
      commitErrors = Some(errors)
    }

    val delayedStore = groupMetadataManager.prepareStoreOffsets(group, memberId, group.generationId, offsets, callback).get
    assertTrue(group.hasOffsets)

    groupMetadataManager.store(delayedStore)
    assertFalse(commitErrors.isEmpty)
    assertEquals(Some(Errors.NONE), commitErrors.get.get(topicPartition1))

    // expire all of the offsets
    time.sleep(4)

    // expect the offset tombstone
    EasyMock.reset(partition)
    EasyMock.expect(partition.appendRecordsToLeader(EasyMock.anyObject(classOf[MemoryRecords]),
      isFromClient = EasyMock.eq(false), requiredAcks = EasyMock.anyInt()))
      .andReturn(LogAppendInfo.UnknownLogAppendInfo)
    EasyMock.replay(partition)

    groupMetadataManager.cleanupGroupMetadata()

    // group should still be there, but the offsets should be gone
    assertEquals(Some(group), groupMetadataManager.getGroup(groupId))
    assertEquals(None, group.offset(topicPartition1))
    assertEquals(None, group.offset(topicPartition2))

    val cachedOffsets = groupMetadataManager.getOffsets(groupId, Some(Seq(topicPartition1, topicPartition2)))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition1).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), cachedOffsets.get(topicPartition2).map(_.offset))
  }

  private def expectAppendMessage(error: Errors) {
    val capturedArgument: Capture[Map[TopicPartition, PartitionResponse] => Unit] = EasyMock.newCapture()
    EasyMock.expect(replicaManager.appendRecords(EasyMock.anyLong(),
      EasyMock.anyShort(),
      internalTopicsAllowed = EasyMock.eq(true),
      isFromClient = EasyMock.eq(false),
      EasyMock.anyObject().asInstanceOf[Map[TopicPartition, MemoryRecords]],
      EasyMock.capture(capturedArgument))).andAnswer(new IAnswer[Unit] {
      override def answer = capturedArgument.getValue.apply(
        Map(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId) ->
          new PartitionResponse(error, 0L, RecordBatch.NO_TIMESTAMP)
        )
      )})
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
  }

  private def buildStableGroupRecordWithMember(memberId: String): SimpleRecord = {
    val group = new GroupMetadata(groupId)
    group.transitionTo(PreparingRebalance)
    val memberProtocols = List(("roundrobin", Array.emptyByteArray))
    val member = new MemberMetadata(memberId, groupId, "clientId", "clientHost", 30000, 10000, "consumer", memberProtocols)
    group.add(member)
    member.awaitingJoinCallback = _ => {}
    group.initNextGeneration()
    group.transitionTo(Stable)

    val groupMetadataKey = GroupMetadataManager.groupMetadataKey(groupId)
    val groupMetadataValue = GroupMetadataManager.groupMetadataValue(group, Map(memberId -> Array.empty[Byte]))
    new SimpleRecord(groupMetadataKey, groupMetadataValue)
  }

  private def expectGroupMetadataLoad(groupMetadataTopicPartition: TopicPartition,
                                      startOffset: Long,
                                      records: MemoryRecords): Unit = {
    val endOffset = startOffset + records.records.asScala.size
    val logMock =  EasyMock.mock(classOf[Log])
    val fileRecordsMock = EasyMock.mock(classOf[FileRecords])

    EasyMock.expect(replicaManager.getLog(groupMetadataTopicPartition)).andStubReturn(Some(logMock))
    EasyMock.expect(logMock.logStartOffset).andStubReturn(startOffset)
    EasyMock.expect(replicaManager.getLogEndOffset(groupMetadataTopicPartition)).andStubReturn(Some(endOffset))
    EasyMock.expect(logMock.read(EasyMock.eq(startOffset), EasyMock.anyInt(), EasyMock.eq(None), EasyMock.eq(true), EasyMock.eq(IsolationLevel.READ_UNCOMMITTED)))
      .andReturn(FetchDataInfo(LogOffsetMetadata(startOffset), fileRecordsMock))
    EasyMock.expect(fileRecordsMock.readInto(EasyMock.anyObject(classOf[ByteBuffer]), EasyMock.anyInt()))
      .andReturn(records.buffer)

    EasyMock.replay(logMock, fileRecordsMock)
  }

  private def createCommittedOffsetRecords(committedOffsets: Map[TopicPartition, Long],
                                           groupId: String = groupId): Seq[SimpleRecord] = {
    committedOffsets.map { case (topicPartition, offset) =>
      val offsetAndMetadata = OffsetAndMetadata(offset)
      val offsetCommitKey = GroupMetadataManager.offsetCommitKey(groupId, topicPartition)
      val offsetCommitValue = GroupMetadataManager.offsetCommitValue(offsetAndMetadata)
      new SimpleRecord(offsetCommitKey, offsetCommitValue)
    }.toSeq
  }

}