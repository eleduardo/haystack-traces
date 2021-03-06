package com.expedia.www.haystack.trace.indexer.unit

import java.util
import java.util.Collections

import com.codahale.metrics.Timer
import com.expedia.www.haystack.trace.commons.retries.RetryOperation
import com.expedia.www.haystack.trace.indexer.writers.es.TraceIndexResultHandler
import com.google.gson.Gson
import io.searchbox.core.BulkResult
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException
import org.scalatest.easymock.EasyMockSugar
import org.scalatest.{FunSpec, Matchers}

class TraceIndexResultHandlerSpec extends FunSpec with Matchers with EasyMockSugar {

  describe("Trace Index Result Handler") {
    it("should complete with success if no failures reported") {
      val retryCallback = mock[RetryOperation.Callback]
      val timer = mock[Timer.Context]
      val bulkResult = mock[BulkResult]

      expecting {
        retryCallback.onResult(bulkResult)
        timer.close()
        bulkResult.getFailedItems.andReturn(Collections.emptyList()).anyTimes()
      }

      whenExecuting(retryCallback, timer, bulkResult) {
        val handler = new TraceIndexResultHandler(timer, retryCallback)
        handler.completed(bulkResult)
        TraceIndexResultHandler.esWriteFailureMeter.getCount shouldBe 0
      }
    }

    it("should complete with success but mark the failures if happen") {
      val retryCallback = mock[RetryOperation.Callback]
      val timer = mock[Timer.Context]
      val bulkResult = mock[BulkResult]
      val outer = new BulkResult(new Gson())
      val resultItem = new outer.BulkResultItem("op", "index", "type", "1", 400,
        "error", 1, "errorType", "errorReason")

      expecting {
        retryCallback.onResult(bulkResult)
        timer.close()
        bulkResult.getFailedItems.andReturn(util.Arrays.asList(resultItem)).anyTimes()
      }

      whenExecuting(retryCallback, timer, bulkResult) {
        val handler = new TraceIndexResultHandler(timer, retryCallback)
        val initialFailures = TraceIndexResultHandler.esWriteFailureMeter.getCount
        handler.completed(bulkResult)
        TraceIndexResultHandler.esWriteFailureMeter.getCount - initialFailures shouldBe 1
      }
    }

    it("should report failure and mark the number of failures, and perform retry on any exception") {
      val retryCallback = mock[RetryOperation.Callback]
      val timer = mock[Timer.Context]
      val bulkResult = mock[BulkResult]

      val error = new RuntimeException
      expecting {
        retryCallback.onError(error, retry = true)
        timer.close()
      }

      whenExecuting(retryCallback, timer, bulkResult) {
        val handler = new TraceIndexResultHandler(timer, retryCallback)
        val initialFailures = TraceIndexResultHandler.esWriteFailureMeter.getCount
        handler.failed(error)
        TraceIndexResultHandler.esWriteFailureMeter.getCount - initialFailures shouldBe 1
      }
    }

    it("should report failure and mark the number of failures and perform function on elastic search specific exception") {
      val retryCallback = mock[RetryOperation.Callback]
      val timer = mock[Timer.Context]
      val bulkResult = mock[BulkResult]

      val error = new EsRejectedExecutionException("too many requests")

      expecting {
        retryCallback.onError(error, retry = true)
        timer.close()
      }

      whenExecuting(retryCallback, timer, bulkResult) {
        val handler = new TraceIndexResultHandler(timer, retryCallback)
        val initialFailures = TraceIndexResultHandler.esWriteFailureMeter.getCount
        handler.failed(error)
        TraceIndexResultHandler.esWriteFailureMeter.getCount - initialFailures shouldBe 1
      }
    }
  }
}
