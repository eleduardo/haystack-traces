/*
 *  Copyright 2017 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.expedia.www.haystack.stitch.span.collector

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.{CommittableOffsetBatch, _}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches}
import com.expedia.open.tracing.stitch.StitchedSpan
import com.expedia.www.haystack.stitch.span.collector.config.entities.CollectorConfiguration
import com.expedia.www.haystack.stitch.span.collector.serdes.StitchedSpanDeserializer
import com.expedia.www.haystack.stitch.span.collector.writers.StitchedSpanWriter
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class StreamTopology(collectorConfig: CollectorConfiguration,
                     writers: Seq[StitchedSpanWriter])(implicit val system: ActorSystem) {

  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  /**
    * build and start the topology
    */
  def start(): (KillSwitch, Future[Done]) = {
    val settings = ConsumerSettings.create(system, new ByteArrayDeserializer, new StitchedSpanDeserializer)

    Consumer
      .committableSource(settings, Subscriptions.topics(collectorConfig.consumerTopic))
      .viaMat(KillSwitches.single)(Keep.right)
      .groupedWithin(collectorConfig.batchSize, collectorConfig.batchIntervalMillis.millis)
      .mapAsync(collectorConfig.parallelism) { msg =>
        writeStitchedSpans(msg).map(writeResultFuture => writeResultFuture.map(_.committableOffset))
      }
      .mapConcat(toImmutable)
      .batch(collectorConfig.commitBatch, first => CommittableOffsetBatch.empty.updated(first)) {
        (batch, elem) => batch.updated(elem)
      }
      .mapAsync(collectorConfig.parallelism)(_.commitScaladsl())
      .toMat(Sink.ignore)(Keep.both)
      .run()
  }

  private def writeStitchedSpans(records: Seq[CommittableMessage[Array[Byte], StitchedSpan]]): Future[Seq[CommittableMessage[Array[Byte], StitchedSpan]]] = {
    val promise = Promise[Seq[CommittableMessage[Array[Byte], StitchedSpan]]]()

    val stitchedSpans: Seq[StitchedSpan] = records.map(_.record.value())
    val allWrites: Seq[Future[Any]] = writers.map(_.write(stitchedSpans))
    Future.sequence(allWrites).onComplete(_ => promise.success(records))

    promise.future
  }

  private def toImmutable[A](elements: Iterable[A]) = {
    new scala.collection.immutable.Iterable[A] {
      override def iterator: Iterator[A] = elements.toIterator
    }
  }
}
