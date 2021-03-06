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

package com.expedia.www.haystack.trace.indexer.unit

import com.datastax.driver.core.ConsistencyLevel
import com.datastax.driver.core.exceptions.UnavailableException
import com.expedia.www.haystack.trace.indexer.config.ProjectConfiguration
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.scalatest.{FunSpec, Matchers}

class ConfigurationLoaderSpec extends FunSpec with Matchers {

  val project = new ProjectConfiguration()
  describe("Configuration loader") {

    it("should load the health status config from base.conf") {
      project.healthStatusFilePath shouldEqual "/app/isHealthy"
    }

    it("should load the span buffer config only from base.conf") {
      val config = project.spanAccumulateConfig
      config.pollIntervalMillis shouldBe 2000L
      config.maxEntriesAllStores shouldBe 20000
      config.bufferingWindowMillis shouldBe 10000L
    }

    it("should load the kafka config from base.conf and one stream property from env variable") {
      val kafkaConfig = project.kafkaConfig
      kafkaConfig.produceTopic shouldBe "span-buffer"
      kafkaConfig.consumeTopic shouldBe "spans"
      kafkaConfig.numStreamThreads shouldBe 2
      kafkaConfig.commitOffsetRetries shouldBe 3
      kafkaConfig.commitBackoffInMillis shouldBe 200

      kafkaConfig.maxWakeups shouldBe 5
      kafkaConfig.wakeupTimeoutInMillis shouldBe 5000

      kafkaConfig.consumerProps.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG) shouldBe "kafkasvc:9092"
      kafkaConfig.consumerProps.getProperty(ConsumerConfig.GROUP_ID_CONFIG) shouldBe "haystack-trace-indexer"
      kafkaConfig.consumerProps.getProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG) shouldBe "earliest"
      kafkaConfig.consumerProps.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG) shouldBe "false"
      kafkaConfig.consumerProps.getProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG) shouldBe "org.apache.kafka.common.serialization.StringDeserializer"
      kafkaConfig.consumerProps.getProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG) shouldBe "com.expedia.www.haystack.trace.indexer.serde.SpanDeserializer"

      kafkaConfig.consumerCloseTimeoutInMillis shouldBe 30000

      kafkaConfig.producerProps.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG) shouldBe "kafkasvc:9092"
      kafkaConfig.producerProps.getProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG) shouldBe "org.apache.kafka.common.serialization.ByteArraySerializer"
      kafkaConfig.producerProps.getProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG) shouldBe "org.apache.kafka.common.serialization.StringSerializer"
    }

    it("should load the cassandra config from base.conf and few properties overridden from env variable") {
      val cassandraWriteConfig = project.cassandraWriteConfig
      val clientConfig = cassandraWriteConfig.clientConfig

      cassandraWriteConfig.consistencyLevel shouldEqual ConsistencyLevel.ONE
      clientConfig.autoDiscoverEnabled shouldBe false
      // this will fail if run inside an editor, we override this config using env variable inside pom.xml
      clientConfig.endpoints should contain allOf("cass1", "cass2")
      clientConfig.autoCreateSchema shouldBe Some("cassandra_cql_schema")
      clientConfig.awsNodeDiscovery shouldBe empty
      clientConfig.socket.keepAlive shouldBe true
      clientConfig.socket.maxConnectionPerHost shouldBe 100
      clientConfig.socket.readTimeoutMills shouldBe 5000
      clientConfig.socket.connectionTimeoutMillis shouldBe 10000
      cassandraWriteConfig.recordTTLInSec shouldBe 86400
      cassandraWriteConfig.maxInFlightRequests shouldBe 100
      cassandraWriteConfig.retryConfig.maxRetries shouldBe 10
      cassandraWriteConfig.retryConfig.backOffInMillis shouldBe 250
      cassandraWriteConfig.retryConfig.backoffFactor shouldBe 2

      // test consistency level on error
      val writeError = new UnavailableException(ConsistencyLevel.ONE, 0, 0)
      cassandraWriteConfig.writeConsistencyLevel(writeError) shouldEqual ConsistencyLevel.ANY
      cassandraWriteConfig.writeConsistencyLevel(null) shouldEqual ConsistencyLevel.ONE
      cassandraWriteConfig.writeConsistencyLevel(new RuntimeException) shouldEqual ConsistencyLevel.ONE
    }

    it("should load the elastic search config from base.conf and one property overridden from env variable") {
      val elastic = project.elasticSearchConfig
      elastic.endpoint shouldBe "http://elasticsearch:9200"
      elastic.maxInFlightBulkRequests shouldBe 10
      elastic.maxDocsInBulk shouldBe 100
      elastic.maxBulkDocSizeInBytes shouldBe 1000000
      elastic.indexTemplateJson shouldBe Some("some_template_json")
      elastic.consistencyLevel shouldBe "one"
      elastic.readTimeoutMillis shouldBe 5000
      elastic.connectionTimeoutMillis shouldBe 10000
      elastic.indexNamePrefix shouldBe "haystack-test"
      elastic.indexType shouldBe "spans"
      elastic.retryConfig.maxRetries shouldBe 10
      elastic.retryConfig.backOffInMillis shouldBe 1000
      elastic.retryConfig.backoffFactor shouldBe 2
      elastic.indexHourBucket shouldBe 6
    }
  }
}
