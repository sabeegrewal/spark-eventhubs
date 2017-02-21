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

package org.apache.spark.sql.streaming.eventhubs

import org.apache.spark.eventhubscommon.{CommonUtils, EventHubNameAndPartition, EventHubsConnector}
import org.apache.spark.eventhubscommon.client.{EventHubClient, EventHubsClientWrapper, RestfulEventHubClient}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SparkSession, SQLContext}
import org.apache.spark.sql.execution.streaming.{Offset, Source}
import org.apache.spark.sql.types._

/**
 * each source is mapped to an eventhubs instance
 */
private[spark] class EventHubsSource(
    sqlContext: SQLContext,
    parameters: Map[String, String],
    eventhubReceiverCreator: (Map[String, String], Int, Long, Int) => EventHubsClientWrapper =
      EventHubsClientWrapper.getEventHubReceiver,
    eventhubClientCreator: (String, Map[String, Map[String, String]]) => EventHubClient =
      RestfulEventHubClient.getInstance) extends Source with EventHubsConnector with Logging {

  private val eventhubsNamespace: String = parameters("eventhubs.namespace")

  require(eventhubsNamespace != null, "eventhubs.namespace is not defined")

  private val eventhubsName: String = parameters("eventhubs.name")

  require(eventhubsName != null, "eventhubs.name is not defined")

  private var _eventHubClient: EventHubClient = _

  private[eventhubs] def eventHubClient = {
    if (_eventHubClient == null) {
      _eventHubClient = eventhubClientCreator(eventhubsNamespace, Map(eventhubsName -> parameters))
    }
    _eventHubClient
  }

  private[eventhubs] def setEventHubClient(eventHubClient: EventHubClient): EventHubsSource = {
    _eventHubClient = eventHubClient
    this
  }

  private val ehNameAndPartitions = {
    val partitionCount = parameters("eventhubs.partition.count").toInt
    (for (partitionId <- 0 until partitionCount)
      yield EventHubNameAndPartition(eventhubsName, partitionId)).toList
  }

  private var currentOffsetsAndSeqNums: EventHubsOffset =
    EventHubsOffset(0L,
      ehNameAndPartitions.map{ehNameAndSpace => (ehNameAndSpace, (-1L, -1L))}.toMap)
  private var fetchedHighestOffsetsAndSeqNums: EventHubsOffset = _

  override def schema: StructType = {
    val userDefinedKeys = parameters.get("eventhubs.sql.userDefinedKeys") match {
      case Some(keys) =>
        keys.split(",").toSeq
      case None =>
        Seq()
    }
    EventHubsSourceProvider.sourceSchema(userDefinedKeys)
  }

  private[spark] def composeHighestOffset(retryIfFail: Boolean) = {
    CommonUtils.fetchLatestOffset(eventHubClient, retryIfFail = retryIfFail) match {
      case Some(highestOffsets) =>
        fetchedHighestOffsetsAndSeqNums = EventHubsOffset(Long.MaxValue, highestOffsets)
        Some(fetchedHighestOffsetsAndSeqNums.offsets)
      case _ =>
        logWarning(s"failed to fetch highest offset")
        if (retryIfFail) {
          None
        } else {
          Some(fetchedHighestOffsetsAndSeqNums.offsets)
        }
    }
  }

  /**
   * when we have reached the end of the message queue in the remote end or we haven't get any
   * idea about the highest offset, we shall fail the app when rest endpoint is not responsive, and
   * to prevent we die too much, we shall retry with 2-power interval in this case
   */
  private def failAppIfRestEndpointFail = fetchedHighestOffsetsAndSeqNums == null ||
    currentOffsetsAndSeqNums.offsets.equals(fetchedHighestOffsetsAndSeqNums.offsets)

  /**
   * @return return the target offset in the next batch
   */
  override def getOffset: Option[Offset] = {
    val highestOffsetsOpt = composeHighestOffset(failAppIfRestEndpointFail)
    require(highestOffsetsOpt.isDefined, "cannot get highest offset from rest endpoint of" +
      " eventhubs")
    val targetOffsets = CommonUtils.clamp(currentOffsetsAndSeqNums.offsets,
      highestOffsetsOpt.get, parameters)
    Some(EventHubsBatchRecord(currentOffsetsAndSeqNums.batchId + 1, targetOffsets))
  }

  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    if (start.isEmpty) {
      // read from progress tracking directory to get the start offset of the undergoing batch

    } else {
      // start from the beginning of the stream
    }
    // TODO: create EventHubsRDD
    Seq(1, 2, 3).toDF()
  }

  override def stop(): Unit = {

  }

  // uniquely identify the entities in eventhubs side, it can be the namespace or the name of a
  override def uid: String = eventhubsName

  // the list of eventhubs partitions connecting with this connector
  override def connectedInstances: List[EventHubNameAndPartition] = ehNameAndPartitions
}
