/*
 *  Copyright 2017 Expedia, Inc.
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *       Unless required by applicable law or agreed to in writing, software
 *       distributed under the License is distributed on an "AS IS" BASIS,
 *       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *       See the License for the specific language governing permissions and
 *       limitations under the License.
 */

package com.expedia.www.haystack.trace.reader.readers

import com.codahale.metrics.Meter
import com.expedia.open.tracing.Span
import com.expedia.open.tracing.api.{FieldNames, _}
import com.expedia.www.haystack.trace.reader.config.entities.{TraceTransformersConfiguration, TraceValidatorsConfiguration}
import com.expedia.www.haystack.trace.reader.exceptions.SpanNotFoundException
import com.expedia.www.haystack.trace.reader.metrics.MetricsSupport
import com.expedia.www.haystack.trace.reader.readers.utils.{AuxiliaryTags, PartialSpanUtils}
import com.expedia.www.haystack.trace.reader.readers.utils.TagExtractors._
import com.expedia.www.haystack.trace.reader.stores.TraceStore
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object TraceReader extends MetricsSupport {
  private val LOGGER: Logger = LoggerFactory.getLogger(s"${classOf[TraceReader]}.search.trace.rejection")
  private val traceRejectedCounter: Meter = metricRegistry.meter("search.trace.rejection")
}

class TraceReader(traceStore: TraceStore, validatorsConfig: TraceValidatorsConfiguration, transformersConfig: TraceTransformersConfiguration)
                 (implicit val executor: ExecutionContextExecutor)
  extends TraceProcessor(validatorsConfig.validators, transformersConfig.preTransformers, transformersConfig.postTransformers) {

  def getTrace(request: TraceRequest): Future[Trace] = {
    traceStore
      .getTrace(request.getTraceId)
      .flatMap(process(_) match {
        case Success(span) => Future.successful(span)
        case Failure(ex) => Future.failed(ex)
      })
  }

  def getRawTrace(request: TraceRequest): Future[Trace] = {
    traceStore.getTrace(request.getTraceId)
  }

  def getRawSpan(request: SpanRequest): Future[Span] = {
    traceStore
      .getTrace(request.getTraceId)
      .flatMap(trace => {
        val spanOption = trace.getChildSpansList
          .find(span => span.getSpanId.equals(request.getSpanId))

        spanOption match {
          case Some(span) => Future.successful(span)
          case None => Future.failed(new SpanNotFoundException)
        }
      })
  }

  def searchTraces(request: TracesSearchRequest): Future[TracesSearchResult] = {
    traceStore
      .searchTraces(request)
      .map(
        traces => {
          TracesSearchResult
            .newBuilder()
            .addAllTraces(traces.flatMap(transformTraceIgnoringInvalid))
            .build()
        })
  }

  private def transformTraceIgnoringInvalid(trace: Trace): Option[Trace] = {
    process(trace) match {
      case Success(t) => Some(t)
      case Failure(ex) =>
        TraceReader.LOGGER.warn(s"invalid trace=${trace.getTraceId} is rejected", ex)
        TraceReader.traceRejectedCounter.mark()
        None
    }
  }

  def getFieldNames: Future[FieldNames] = {
    traceStore
      .getFieldNames()
      .map(
        FieldNames
          .newBuilder()
          .addAllNames(_)
          .build())
  }

  def getFieldValues(request: FieldValuesRequest): Future[FieldValues] = {
    traceStore
      .getFieldValues(request)
      .map(
        FieldValues
          .newBuilder()
          .addAllValues(_)
          .build())
  }

  def getTraceCallGraph(request: TraceRequest): Future[TraceCallGraph] = {
    traceStore
      .getTrace(request.getTraceId)
      .flatMap(process(_) match {
        case Success(trace) => Future.successful(buildTraceCallGraph(trace))
        case Failure(ex) => Future.failed(ex)
      })
  }

  private def buildTraceCallGraph(trace: Trace): TraceCallGraph = {
    val calls = trace.getChildSpansList
      .filter(containsTag(_, AuxiliaryTags.IS_MERGED_SPAN))
      .map(span => {
        val from = CallNode.newBuilder()
          .setServiceName(extractTagStringValue(span, AuxiliaryTags.CLIENT_SERVICE_NAME))
          .setOperationName(extractTagStringValue(span, AuxiliaryTags.CLIENT_OPERATION_NAME))
          .setInfrastructureProvider(extractTagStringValue(span, AuxiliaryTags.CLIENT_INFRASTRUCTURE_PROVIDER))
          .setInfrastructureLocation(extractTagStringValue(span, AuxiliaryTags.CLIENT_INFRASTRUCTURE_LOCATION))
          
        val to = CallNode.newBuilder()
          .setServiceName(extractTagStringValue(span, AuxiliaryTags.SERVER_SERVICE_NAME))
          .setOperationName(extractTagStringValue(span, AuxiliaryTags.SERVER_OPERATION_NAME))
          .setInfrastructureProvider(extractTagStringValue(span, AuxiliaryTags.SERVER_INFRASTRUCTURE_PROVIDER))
          .setInfrastructureLocation(extractTagStringValue(span, AuxiliaryTags.SERVER_INFRASTRUCTURE_LOCATION))

        Call.newBuilder()
          .setFrom(from)
          .setTo(to)
          .setNetworkDelta(extractTagLongValue(span, AuxiliaryTags.NETWORK_DELTA))
          .build()
      })

    TraceCallGraph
      .newBuilder()
      .addAllCalls(calls)
      .build()
  }
}
