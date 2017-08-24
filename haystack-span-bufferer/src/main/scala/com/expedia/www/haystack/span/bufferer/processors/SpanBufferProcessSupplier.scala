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
package com.expedia.www.haystack.span.bufferer.processors

import com.expedia.open.tracing.Span
import com.expedia.www.haystack.span.bufferer.config.entities.SpanBufferConfiguration
import org.apache.kafka.streams.processor.{Processor, ProcessorSupplier}

class SpanBufferProcessSupplier(config: SpanBufferConfiguration) extends ProcessorSupplier[String, Span] {

  /**
    * @return processor that does the buffering of spans for each unique traceId and emits out the buffered object
    */
  override def get(): Processor[String, Span] = new MeteredSpanBufferingProcessor(config)
}