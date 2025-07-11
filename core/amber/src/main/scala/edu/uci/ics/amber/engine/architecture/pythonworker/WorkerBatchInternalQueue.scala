/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package edu.uci.ics.amber.engine.architecture.pythonworker

import edu.uci.ics.amber.engine.architecture.pythonworker.WorkerBatchInternalQueue._
import edu.uci.ics.amber.engine.common.actormessage.ActorCommand
import edu.uci.ics.amber.engine.common.ambermessage.{
  DirectControlMessagePayload,
  DataFrame,
  DataPayload
}
import edu.uci.ics.amber.core.virtualidentity.ChannelIdentity
import edu.uci.ics.amber.engine.architecture.rpc.controlcommands.EmbeddedControlMessage
import lbmq.LinkedBlockingMultiQueue

import scala.collection.mutable

object WorkerBatchInternalQueue {
  final val DATA_QUEUE = 1
  final val CONTROL_QUEUE = 0

  // 4 kinds of elements can be accepted by internal queue
  sealed trait InternalQueueElement

  case class DataElement(dataPayload: DataPayload, from: ChannelIdentity)
      extends InternalQueueElement

  case class ControlElement(cmd: DirectControlMessagePayload, from: ChannelIdentity)
      extends InternalQueueElement
  case class EmbeddedControlMessageElement(cmd: EmbeddedControlMessage, from: ChannelIdentity)
      extends InternalQueueElement
  case class ActorCommandElement(cmd: ActorCommand) extends InternalQueueElement
}

/** Inspired by the mailbox-ed thread, the internal queue should
  * be a part of DP thread.
  */
trait WorkerBatchInternalQueue {

  private val lbmq = new LinkedBlockingMultiQueue[Int, InternalQueueElement]()

  lbmq.addSubQueue(DATA_QUEUE, DATA_QUEUE)
  lbmq.addSubQueue(CONTROL_QUEUE, CONTROL_QUEUE)

  private val dataQueue = lbmq.getSubQueue(DATA_QUEUE)

  private val controlQueue = lbmq.getSubQueue(CONTROL_QUEUE)

  // the values in below maps are in batches
  private val inQueueSizeMapping =
    new mutable.HashMap[ChannelIdentity, Long]() // read and written by main thread
  @volatile private var outQueueSizeMapping =
    new mutable.HashMap[ChannelIdentity, Long]() // written by DP thread, read by main thread

  def enqueueData(elem: InternalQueueElement): Unit = {
    dataQueue.add(elem)
    elem match {
      case DataElement(dataPayload, from) =>
        dataPayload match {
          case frame: DataFrame =>
            inQueueSizeMapping(from) =
              inQueueSizeMapping.getOrElseUpdate(from, 0L) + frame.inMemSize
          case _ =>
          // do nothing
        }
      case _ =>
      // do nothing
    }
  }

  def enqueueCommand(cmd: DirectControlMessagePayload, from: ChannelIdentity): Unit = {
    controlQueue.add(ControlElement(cmd, from))
  }

  def enqueueActorCommand(command: ActorCommand): Unit = {
    controlQueue.add(ActorCommandElement(command))
  }

  def getElement: InternalQueueElement = {
    val elem = lbmq.take()
    elem match {
      case DataElement(dataPayload, from) =>
        dataPayload match {
          case frame: DataFrame =>
            outQueueSizeMapping(from) =
              outQueueSizeMapping.getOrElseUpdate(from, 0L) + frame.inMemSize
          case _ =>
          // do nothing
        }
      case _ =>
      // do nothing
    }
    elem
  }

  def disableDataQueue(): Unit = dataQueue.enable(false)

  def enableDataQueue(): Unit = dataQueue.enable(true)

  def getDataQueueLength: Int = dataQueue.size()

  def getControlQueueLength: Int = controlQueue.size()

  def isControlQueueEmpty: Boolean = controlQueue.isEmpty

  def getQueuedCredit(sender: ChannelIdentity): Long = {
    val inBytes = inQueueSizeMapping.getOrElseUpdate(sender, 0L)
    val outBytes = outQueueSizeMapping.getOrElseUpdate(sender, 0L)
    inBytes - outBytes
  }

}
