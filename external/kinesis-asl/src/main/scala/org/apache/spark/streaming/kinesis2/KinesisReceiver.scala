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
package org.apache.spark.streaming.kinesis2

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.internal.Logging
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener, Receiver}
import org.apache.spark.streaming.{Duration, kinesis2}
import org.apache.spark.util.Utils
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.{NettyNioAsyncHttpClient, ProxyConfiguration}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ConfigsBuilder, InitialPositionInStream, InitialPositionInStreamExtended}
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.processor.RecordProcessorCheckpointer
import software.amazon.kinesis.retrieval.KinesisClientRecord
import software.amazon.kinesis.retrieval.polling.PollingConfig

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Custom AWS Kinesis-specific implementation of Spark Streaming's Receiver.
 * This implementation relies on the Kinesis Client Library (KCL) Worker as described here:
 * https://github.com/awslabs/amazon-kinesis-client
 *
 * The way this Receiver works is as follows:
 *
 *   - The receiver starts a KCL Worker, which is essentially runs a threadpool of multiple
 * KinesisRecordProcessor
 *   - Each KinesisRecordProcessor receives data from a Kinesis shard in batches. Each batch is
 * inserted into a Block Generator, and the corresponding range of sequence numbers is recorded.
 *   - When the block generator defines a block, then the recorded sequence number ranges that were
 * inserted into the block are recorded separately for being used later.
 *   - When the block is ready to be pushed, the block is pushed and the ranges are reported as
 * metadata of the block. In addition, the ranges are used to find out the latest sequence
 * number for each shard that can be checkpointed through the DynamoDB.
 *   - Periodically, each KinesisRecordProcessor checkpoints the latest successfully stored sequence
 * number for it own shard.
 *
 * @param streamName         Kinesis stream name
 * @param kinesisCreds
 * @param dynamoDBCreds
 * @param cloudWatchCreds
 * @param regionName         Region name used by the Kinesis Client Library for
 *                           DynamoDB (lease coordination and checkpointing) and CloudWatch (metrics)
 * @param checkpointAppName  Kinesis application name. Kinesis Apps are mapped to Kinesis Streams
 *                           by the Kinesis Client Library.  If you change the App name or Stream name,
 *                           the KCL will throw errors.  This usually requires deleting the backing
 *                           DynamoDB table with the same name this Kinesis application.
 * @param checkpointInterval Checkpoint interval for Kinesis checkpointing.
 *                           See the Kinesis Spark Streaming documentation for more
 *                           details on the different types of checkpoints.
 * @param storageLevel       Storage level to use for storing the received objects
 * @param messageHandler
 * @tparam T
 */
private[kinesis2] class KinesisReceiver[T](streamName: String,
                                           endpointUrl: URI,
                                           regionName: String,
                                           kinesisCreds: SparkAWSCredentials,
                                           dynamoDBCreds: Option[SparkAWSCredentials],
                                           cloudWatchCreds: Option[SparkAWSCredentials],
                                           cloudWatchUrl: Option[URI],
                                           checkpointAppName: String,
                                           checkpointInterval: Duration,
                                           initialPositionInStream: Option[InitialPositionInStream],
                                           maxRecords: Option[Integer],
                                           protocol: Option[Protocol],
                                           dynamoProxyHost: Option[String],
                                           dynamoProxyPort: Option[Integer],
                                           storageLevel: StorageLevel,
                                           messageHandler: KinesisClientRecord => T)

  extends Receiver[T](storageLevel) with Logging {
  receiver =>

  /*
   * =================================================================================
   * The following vars are initialize in the onStart() method which executes in the
   * Spark worker after this Receiver is serialized and shipped to the worker.
   * =================================================================================
   */

  /**
   * workerId is used by the KCL should be based on the ip address of the actual Spark Worker
   * where this code runs (not the driver's IP address.)
   */
  @volatile private var scheduler: Scheduler = null
  @volatile private var workerId: String = null

  /**
   * Worker is the core client abstraction from the Kinesis Client Library (KCL).
   * A worker can process more than one shards from the given stream.
   * Each shard is assigned its own IRecordProcessor and the worker run multiple such
   * processors.
   */
  // @volatile private var worker: Worker = null
  @volatile private var workerThread: Thread = null

  /** BlockGenerator used to generates blocks out of Kinesis data */
  @volatile private var blockGenerator: BlockGenerator = null

  /**
   * Sequence number ranges added to the current block being generated.
   * Accessing and updating of this map is synchronized by locks in BlockGenerator.
   */
  private val seqNumRangesInCurrentBlock = new mutable.ArrayBuffer[kinesis2.SequenceNumberRange]

  /** Sequence number ranges of data added to each generated block */
  private val blockIdToSeqNumRanges = new ConcurrentHashMap[StreamBlockId, kinesis2.SequenceNumberRanges]

  /**
   * The centralized kinesisCheckpointer that checkpoints based on the given checkpointInterval.
   */
  @volatile private var kinesisCheckpointer: KinesisCheckpointer = null

  /**
   * Latest sequence number ranges that have been stored successfully.
   * This is used for checkpointing through KCL */
  private val shardIdToLatestStoredSeqNum = new ConcurrentHashMap[String, String]

  /**
   * This is called when the KinesisReceiver starts and must be non-blocking.
   * The KCL creates and manages the receiving/processing thread pool through Worker.run().
   */
  override def onStart() {
    blockGenerator = supervisor.createBlockGenerator(new GeneratedBlockHandler)
    workerId = Utils.localHostName() + ":" + UUID.randomUUID()
    kinesisCheckpointer = new KinesisCheckpointer(receiver, checkpointInterval, workerId)

    val region = Region.of(regionName)
    val dynamoHost = dynamoProxyHost.getOrElse("")
    val dynamoPort = dynamoProxyPort.getOrElse(0): Integer
    var dynamoDbClient: DynamoDbAsyncClient = null
    if (!dynamoHost.equalsIgnoreCase("")) {
      val proxy = ProxyConfiguration.builder().host(dynamoHost).port(dynamoPort).build
      val httpClient = NettyNioAsyncHttpClient.builder().proxyConfiguration(proxy).build
      dynamoDbClient = DynamoDbAsyncClient.builder().credentialsProvider(dynamoDBCreds.getOrElse(kinesisCreds).provider).region(region).httpClient(httpClient).build
    } else {
      dynamoDbClient = DynamoDbAsyncClient.builder().credentialsProvider(dynamoDBCreds.getOrElse(kinesisCreds).provider).region(region).build
    }
    val cloudWatchClient = CloudWatchAsyncClient.builder()
      .endpointOverride(cloudWatchUrl.getOrElse(KinesisInputDStream.DEFAULT_MONITORING_ENDPOINT_URL))
      .credentialsProvider(cloudWatchCreds.getOrElse(kinesisCreds).provider)
      .region(region).build

    val kinesisClient = KinesisAsyncClient.builder
      .region(region)
      .credentialsProvider(kinesisCreds.provider)
      .httpClient(NettyNioAsyncHttpClient.builder().protocol(protocol.getOrElse(Protocol.HTTP1_1)).build())
      .endpointOverride(endpointUrl).build

    val initialPosition = initialPositionInStream.getOrElse(InitialPositionInStream.TRIM_HORIZON)
    val recordProcessor = new KinesisRecordProcessorFactory(receiver, workerId)

    val configsBuilder = new ConfigsBuilder(streamName,
      checkpointAppName,
      kinesisClient,
      dynamoDbClient,
      cloudWatchClient,
      UUID.randomUUID.toString,
      recordProcessor)
    configsBuilder.tableName(checkpointAppName)

    val pollingConfig = new PollingConfig(streamName, kinesisClient)
    val lease = configsBuilder.leaseManagementConfig()
    logInfo("Dynamo DB table name: " + lease.tableName + " " + lease.streamName)
    val maxRecord = maxRecords.getOrElse(100: Integer)
    pollingConfig.maxRecords(maxRecord)

    scheduler = new Scheduler(
      configsBuilder.checkpointConfig(),
      configsBuilder.coordinatorConfig(),
      configsBuilder.leaseManagementConfig(),
      configsBuilder.lifecycleConfig(),
      configsBuilder.metricsConfig(),
      configsBuilder.processorConfig(),
      configsBuilder.retrievalConfig()
        .retrievalSpecificConfig(pollingConfig)
        .initialPositionInStreamExtended(InitialPositionInStreamExtended.newInitialPosition(initialPosition)))

    workerThread = new Thread() {
      override def run(): Unit = {
        try {
          scheduler.run()
        } catch {
          case NonFatal(e) =>
            restart("Error running the KCL worker in Receiver", e)
        }
      }
    }
    blockIdToSeqNumRanges.clear()
    blockGenerator.start()

    workerThread.setName(s"Kinesis Receiver ${streamId}")
    workerThread.setDaemon(true)
    workerThread.start()

    logInfo(s"Started receiver with workerId $workerId")
  }

  /**
   * This is called when the KinesisReceiver stops.
   * The KCL worker.shutdown() method stops the receiving/processing threads.
   * The KCL will do its best to drain and checkpoint any in-flight records upon shutdown.
   */
  override def onStop() {
    if (workerThread != null) {
      if (scheduler != null) {
        scheduler.shutdown()
        scheduler = null
      }
      workerThread.join()
      workerThread = null
      logInfo(s"Stopped receiver for workerId $workerId")
    }
    workerId = null
    if (kinesisCheckpointer != null) {
      kinesisCheckpointer.shutdown()
      kinesisCheckpointer = null
    }
  }

  /** Add records of the given shard to the current block being generated */
  private[kinesis2] def addRecords(shardId: String, records: java.util.List[KinesisClientRecord]): Unit = {
    if (records.size > 0) {
      val dataIterator = records.iterator().asScala.map(messageHandler)
      val metadata = kinesis2.SequenceNumberRange(streamName, shardId,
        records.get(0).sequenceNumber(), records.get(records.size() - 1).sequenceNumber(),
        records.size())
      blockGenerator.addMultipleDataWithCallback(dataIterator, metadata)
    }
  }

  /** Return the current rate limit defined in [[BlockGenerator]]. */
  private[kinesis2] def getCurrentLimit: Int = {
    assert(blockGenerator != null)
    math.min(blockGenerator.getCurrentLimit, Int.MaxValue).toInt
  }

  /** Get the latest sequence number for the given shard that can be checkpointed through KCL */
  private[kinesis2] def getLatestSeqNumToCheckpoint(shardId: String): Option[String] = {
    Option(shardIdToLatestStoredSeqNum.get(shardId))
  }

  /**
   * Set the checkpointer that will be used to checkpoint sequence numbers to DynamoDB for the
   * given shardId.
   */
  def setCheckpointer(shardId: String, checkpointer: RecordProcessorCheckpointer): Unit = {
    assert(kinesisCheckpointer != null, "Kinesis Checkpointer not initialized!")
    kinesisCheckpointer.setCheckpointer(shardId, checkpointer)
  }

  /**
   * Remove the checkpointer for the given shardId. The provided checkpointer will be used to
   * checkpoint one last time for the given shard. If `checkpointer` is `null`, then we will not
   * checkpoint.
   */
  def removeCheckpointer(shardId: String, checkpointer: RecordProcessorCheckpointer): Unit = {
    assert(kinesisCheckpointer != null, "Kinesis Checkpointer not initialized!")
    kinesisCheckpointer.removeCheckpointer(shardId, checkpointer)
  }

  /**
   * Remember the range of sequence numbers that was added to the currently active block.
   * Internally, this is synchronized with `finalizeRangesForCurrentBlock()`.
   */
  private def rememberAddedRange(range: kinesis2.SequenceNumberRange): Unit = {
    seqNumRangesInCurrentBlock += range
  }

  /**
   * Finalize the ranges added to the block that was active and prepare the ranges buffer
   * for next block. Internally, this is synchronized with `rememberAddedRange()`.
   */
  private def finalizeRangesForCurrentBlock(blockId: StreamBlockId): Unit = {
    blockIdToSeqNumRanges.put(blockId, kinesis2.SequenceNumberRanges(seqNumRangesInCurrentBlock.toArray))
    seqNumRangesInCurrentBlock.clear()
    logDebug(s"Generated block $blockId has $blockIdToSeqNumRanges")
  }

  /** Store the block along with its associated ranges */
  private def storeBlockWithRanges(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[T]): Unit = {
    val rangesToReportOption = Option(blockIdToSeqNumRanges.remove(blockId))
    if (rangesToReportOption.isEmpty) {
      stop("Error while storing block into Spark, could not find sequence number ranges " +
        s"for block $blockId")
      return
    }

    val rangesToReport = rangesToReportOption.get
    var attempt = 0
    var stored = false
    var throwable: Throwable = null
    while (!stored && attempt <= 3) {
      try {
        store(arrayBuffer, rangesToReport)
        stored = true
      } catch {
        case NonFatal(th) =>
          attempt += 1
          throwable = th
      }
    }
    if (!stored) {
      stop("Error while storing block into Spark", throwable)
    }

    // Update the latest sequence number that have been successfully stored for each shard
    // Note that we are doing this sequentially because the array of sequence number ranges
    // is assumed to be
    rangesToReport.ranges.foreach { range =>
      shardIdToLatestStoredSeqNum.put(range.shardId, range.toSeqNumber)
    }
  }

  /**
   * Class to handle blocks generated by this receiver's block generator. Specifically, in
   * the context of the Kinesis Receiver, this handler does the following.
   *
   * - When an array of records is added to the current active block in the block generator,
   * this handler keeps track of the corresponding sequence number range.
   * - When the currently active block is ready to sealed (not more records), this handler
   * keep track of the list of ranges added into this block in another H
   */
  private class GeneratedBlockHandler extends BlockGeneratorListener {

    /**
     * Callback method called after a data item is added into the BlockGenerator.
     * The data addition, block generation, and calls to onAddData and onGenerateBlock
     * are all synchronized through the same lock.
     */
    def onAddData(data: Any, metadata: Any): Unit = {
      rememberAddedRange(metadata.asInstanceOf[kinesis2.SequenceNumberRange])
    }

    /**
     * Callback method called after a block has been generated.
     * The data addition, block generation, and calls to onAddData and onGenerateBlock
     * are all synchronized through the same lock.
     */
    def onGenerateBlock(blockId: StreamBlockId): Unit = {
      finalizeRangesForCurrentBlock(blockId)
    }

    /** Callback method called when a block is ready to be pushed / stored. */
    def onPushBlock(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {
      storeBlockWithRanges(blockId, arrayBuffer.asInstanceOf[mutable.ArrayBuffer[T]])
    }

    /** Callback called in case of any error in internal of the BlockGenerator */
    def onError(message: String, throwable: Throwable): Unit = {
      reportError(message, throwable)
    }
  }

}
