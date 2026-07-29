package com.example.vinehelper

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DetailsClickWebViewTest {
    private var webView: WebView? = null

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            webView?.destroy()
            webView = null
        }
    }

    @Test
    fun staleDom_returnsNotFoundWithoutCreatingButton() {
        loadHtml(
            """
            <html><body>
              <span class="vvp-details-btn">
                <input data-asin="OTHER" data-recommendation-id="other-rec" onclick="window.clicks += 1">
              </span>
              <script>window.clicks = 0;</script>
            </body></html>
            """.trimIndent()
        )

        assertEquals("\"not_found\"", evaluate(buildDetailsClickScript("TARGET", "target-rec")))
        assertEquals("\"1:0\"", evaluate("document.querySelectorAll('.vvp-details-btn input').length + ':' + window.clicks"))
    }

    @Test
    fun realAmazonShapedButton_isClickedWithAllRequiredMetadata() {
        loadHtml(
            """
            <html><body>
              <span class="vvp-details-btn">
                <input
                  data-asin="TARGET"
                  data-recommendation-id="target-rec"
                  data-recommendation-type="VINE_FOR_ALL"
                  data-is-parent-asin="false"
                  data-is-pre-release="false"
                  onclick="window.payload = [
                    this.dataset.recommendationId,
                    this.dataset.recommendationType,
                    this.dataset.asin,
                    this.dataset.isParentAsin,
                    this.dataset.isPreRelease
                  ].join('|')">
              </span>
              <script>window.payload = '';</script>
            </body></html>
            """.trimIndent()
        )

        assertEquals("\"clicked_existing\"", evaluate(buildDetailsClickScript("TARGET", "target-rec")))
        val payload = evaluate("window.payload")
        assertTrue(payload.contains("target-rec|VINE_FOR_ALL|TARGET|false|false"))
    }

    @Test
    fun sameAsin_prefersExactRecommendationId() {
        loadHtml(
            """
            <html><body>
              <span class="vvp-details-btn">
                <input data-asin="TARGET" data-recommendation-id="wrong-rec"
                  onclick="window.clickedRecommendation = this.dataset.recommendationId">
              </span>
              <span class="vvp-details-btn">
                <input data-asin="TARGET" data-recommendation-id="target-rec"
                  onclick="window.clickedRecommendation = this.dataset.recommendationId">
              </span>
              <script>window.clickedRecommendation = '';</script>
            </body></html>
            """.trimIndent()
        )

        assertEquals("\"clicked_existing\"", evaluate(buildDetailsClickScript("TARGET", "target-rec")))
        assertEquals("\"target-rec\"", evaluate("window.clickedRecommendation"))
    }
    @Test
    fun disabledTarget_waitsUntilButtonIsEnabled() {
        loadHtml(
            """
            <html><body>
              <span class="a-button a-button-disabled vvp-details-btn">
                <input disabled data-asin="TARGET" data-recommendation-id="target-rec"
                  onclick="window.clicks += 1">
              </span>
              <script>window.clicks = 0;</script>
            </body></html>
            """.trimIndent()
        )

        assertEquals("\"not_found\"", evaluate(buildDetailsClickScript("TARGET", "target-rec")))
        assertEquals("0", evaluate("window.clicks"))
        evaluate(
            "var b=document.querySelector('input');" +
                "b.disabled=false;b.closest('.a-button').classList.remove('a-button-disabled');"
        )
        assertEquals("\"clicked_existing\"", evaluate(buildDetailsClickScript("TARGET", "target-rec")))
        assertEquals("1", evaluate("window.clicks"))
    }

    @Test
    fun modalImageAddedAfterClick_getsFallbackImageQuickly() {
        val fallback = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
        loadHtml(
            """
            <html><body>
              <span class="vvp-details-btn">
                <input data-asin="TARGET" data-recommendation-id="target-rec" onclick="
                  setTimeout(function() {
                    var modal = document.createElement('div');
                    modal.id = 'vvp-product-details-modal';
                    var image = document.createElement('img');
                    modal.appendChild(image);
                    document.body.appendChild(modal);
                  }, 100);
                ">
              </span>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "\"clicked_existing\"",
            evaluate(buildDetailsClickScript("TARGET", "target-rec", fallback))
        )
        Thread.sleep(350)
        assertEquals("\"$fallback\"", evaluate("document.querySelector('#vvp-product-details-modal img').src"))
    }
    private fun loadHtml(html: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL("https://www.amazon.com/vine/vine-items", html, "text/html", "UTF-8", null)
            }
        }
        assertTrue("Fixture WebView did not finish loading", loaded.await(10, TimeUnit.SECONDS))
    }

    private fun evaluate(script: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String>()
        val evaluated = CountDownLatch(1)
        instrumentation.runOnMainSync {
            requireNotNull(webView).evaluateJavascript(script) {
                result.set(it)
                evaluated.countDown()
            }
        }
        assertTrue("JavaScript did not finish", evaluated.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
