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

    @Test
    fun detailsImageCss_appliesLargeContainedImageOnDeviceWebView() {
        loadHtml(
            """
            <html><head></head><body>
              <div id="vvp-product-details-img-container">
                <img id="vvp-product-details-modal--hero-image">
              </div>
            </body></html>
            """.trimIndent()
        )
        evaluate(
            """
            var style = document.createElement('style');
            style.textContent = ${javascriptStringLiteral(buildDetailsImageCss())};
            document.head.appendChild(style);
            """.trimIndent()
        )

        assertEquals(
            "\"220px|contain|block\"",
            evaluate(
                """
                var image = document.getElementById('vvp-product-details-modal--hero-image');
                var css = getComputedStyle(image);
                css.maxHeight + '|' + css.objectFit + '|' + css.display;
                """.trimIndent()
            )
        )
    }

    @Test
    fun scraperImageExtraction_usesAmazonLazyImageWhenPlaceholderIsEmpty() {
        val expected = "https://images-na.ssl-images-amazon.com/images/I/real-product.jpg"
        loadHtml(
            """
            <html><body>
              <div class="vvp-item-tile">
                <img
                  src="https://images-na.ssl-images-amazon.com/images/G/01/transparent-pixel.gif"
                  data-lazy-src="$expected">
              </div>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "\"$expected\"",
            evaluate(
                """
                (function() {
                  var tile = document.querySelector('.vvp-item-tile');
                  $SCRAPER_IMAGE_EXTRACTION_JS
                  return imgUrl;
                })();
                """.trimIndent()
            )
        )
    }

    @Test
    fun secondDetailsClick_waitsForAndConfirmsTheNewProduct() {
        loadHtml(
            """
            <html><body>
              <span class="vvp-details-btn">
                <input data-asin="FIRSTASIN1" data-recommendation-id="first-rec"
                  onclick="showProduct('FIRSTASIN1', 'First Product')">
              </span>
              <span class="vvp-details-btn">
                <input data-asin="SECONDASN2" data-recommendation-id="second-rec"
                  onclick="showProduct('SECONDASN2', 'Second Product')">
              </span>
              <div class="a-popover" id="popover" style="display:none">
                <div class="a-popover-header">
                  <button class="a-button-close"
                    onclick="document.getElementById('popover').style.display='none'">Close</button>
                </div>
                <div id="vvp-product-details-modal">
                  <h3 id="vvp-product-details-modal--product-title"></h3>
                  <span id="vvp-join-btn"><input type="button" value="Request"></span>
                </div>
              </div>
              <script>
                document.getElementById('vvp-product-details-modal').getBoundingClientRect = function() {
                  var visible = document.getElementById('popover').style.display !== 'none';
                  return {
                    width: visible ? 320 : 0,
                    height: visible ? 320 : 0
                  };
                };
                function showProduct(asin, title) {
                  var modal = document.getElementById('vvp-product-details-modal');
                  modal.setAttribute('data-asin', asin);
                  document.getElementById('vvp-product-details-modal--product-title').textContent = title;
                  document.getElementById('popover').style.display = 'block';
                }
              </script>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "\"clicked_existing\"",
            evaluate(buildDetailsClickScript("FIRSTASIN1", "first-rec", "", "First Product"))
        )
        Thread.sleep(250)
        assertEquals(
            "\"modal_ready\"",
            evaluate(buildDetailsModalReadyScript("FIRSTASIN1", "First Product"))
        )

        assertEquals(
            "\"clicked_existing\"",
            evaluate(buildDetailsClickScript("SECONDASN2", "second-rec", "", "Second Product"))
        )
        assertEquals(
            "\"not_ready\"",
            evaluate(buildDetailsModalReadyScript("SECONDASN2", "Second Product"))
        )
        Thread.sleep(350)
        assertEquals("\"Second Product\"", evaluate("document.querySelector('#vvp-product-details-modal--product-title').textContent"))
        assertEquals(
            "\"modal_ready\"",
            evaluate(buildDetailsModalReadyScript("SECONDASN2", "Second Product"))
        )
        assertEquals(
            "\"stale_modal\"",
            evaluate(buildDetailsModalReadyScript("FIRSTASIN1", "First Product"))
        )
    }

    @Test
    fun mockPortal_repeatedDetailsClicksAreConfirmedWithoutFalseFailure() {
        loadHtml(MockStoreProvider.getMockHtml())
        evaluate(
            """
            document.getElementById('vvp-product-details-modal').getBoundingClientRect = function() {
              var visible = getComputedStyle(document.getElementById('popover')).display !== 'none';
              return {
                width: visible ? 320 : 0,
                height: visible ? 320 : 0
              };
            };
            """.trimIndent()
        )

        assertEquals(
            "\"clicked_existing\"",
            evaluate(
                buildDetailsClickScript(
                    "MOCKRFY001",
                    "rec_rfy_1",
                    "",
                    "Premium Wireless Mechanical Keyboard (RGB, Blue Switches)"
                )
            )
        )
        Thread.sleep(250)
        assertEquals(
            "\"modal_ready\"",
            evaluate(
                buildDetailsModalReadyScript(
                    "MOCKRFY001",
                    "Premium Wireless Mechanical Keyboard (RGB, Blue Switches)"
                )
            )
        )

        assertEquals(
            "\"clicked_existing\"",
            evaluate(
                buildDetailsClickScript(
                    "MOCKRFY002",
                    "rec_rfy_2",
                    "",
                    "Ergonomic Vertical Mouse (Wireless, Rechargeable)"
                )
            )
        )
        Thread.sleep(350)
        assertEquals(
            "\"modal_ready\"",
            evaluate(
                buildDetailsModalReadyScript(
                    "MOCKRFY002",
                    "Ergonomic Vertical Mouse (Wireless, Rechargeable)"
                )
            )
        )
    }

    @Test
    fun detailsModalReady_acceptsVisibleAmazonPopoverWhenModalHostHasZeroWidth() {
        loadHtml(
            """
            <html><body>
              <div id="a-popover-3" class="a-popover">
                <div id="vvp-product-details-modal">
                  <div id="vvp-product-details-modal--content">
                    <div id="vvp-product-details-modal--main">
                      <a id="vvp-product-details-modal--product-title">
                        Anti-Spill Non-Tipping Cup Holder for Boats and Kayaks
                      </a>
                    </div>
                    <span id="vvp-product-details-modal--request-btn">Request product</span>
                  </div>
                </div>
              </div>
              <script>
                document.getElementById('vvp-product-details-modal').getBoundingClientRect = function() {
                  return { width: 0, height: 350 };
                };
                document.getElementById('a-popover-3').getBoundingClientRect = function() {
                  return { width: 550, height: 886 };
                };
              </script>
            </body></html>
            """.trimIndent()
        )

        assertEquals(
            "\"modal_ready\"",
            evaluate(
                buildDetailsModalReadyScript(
                    "B0H6JSC1ZB",
                    "Anti-Spill Non-Tipping Cup Holder for Boats and Kayaks"
                )
            )
        )
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
