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

package com.expedia.www.haystack.trace.provider.providers.transformers

import com.expedia.open.tracing.Span
import com.expedia.open.tracing.internal.Trace

import scala.collection.JavaConversions._

/**
  * takes a sequence of [[TraceTransformer]] and apply transform functions on the chain
  *
  * transformer functions takes [[List]] of [[Span]]s and generates a [[List]] of [[Span]]s
  * [[TraceTransformationHandler]] takes a [[Seq]] of [[TraceTransformer]] and applies chaining on them,
  * providing response [[List]] of a transformer to the next one
  *
  * @param transformerSeq
  */
class TraceTransformationHandler(transformerSeq: Seq[TraceTransformer]) {
  private val transformerChain =
    Function.chain(transformerSeq.foldLeft(Seq[List[Span] => List[Span]]())((seq, transformer) => seq :+ transformer.transform _))

  def transform(trace: Trace): Trace = {
    val transformedSpans = transformerChain(trace.getChildSpansList.toList)

    Trace
      .newBuilder()
      .setTraceId(trace.getTraceId)
      .addAllChildSpans(transformedSpans)
      .build()
  }
}