package com.example.vinehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSecurityTest {
    @Test
    fun trustedAmazonUrl_acceptsOnlyExpectedHttpsHosts() {
        assertTrue(isTrustedAmazonUrl("https://www.amazon.com/vine/vine-items"))
        assertTrue(isTrustedAmazonUrl("https://amazon.com/ap/signin"))
        assertFalse(isTrustedAmazonUrl("http://www.amazon.com/vine/vine-items"))
        assertFalse(isTrustedAmazonUrl("https://amazon.com.attacker.example/vine"))
        assertFalse(isTrustedAmazonUrl("https://smile.amazon.com/vine"))
        assertFalse(isTrustedAmazonUrl("javascript:alert(1)"))
    }

    @Test
    fun queueUrl_encodesTheSelectedCategory() {
        assertEquals(
            "https://www.amazon.com/vine/vine-items?queue=last%20chance%2Fspecial",
            buildQueueUrl("last chance/special")
        )
    }

    @Test
    fun javascriptStringLiteral_escapesExecutableCharacters() {
        assertEquals("\"abc\"", javascriptStringLiteral("abc"))
        assertEquals("\"a\\\";alert(1);//\"", javascriptStringLiteral("a\";alert(1);//"))
        assertEquals("\"line\\nnext\\\\path\"", javascriptStringLiteral("line\nnext\\path"))
    }

    @Test
    fun queueNames_areStable() {
        assertEquals("RFY", getQueueDisplayName("potluck"))
        assertEquals("AFA", getQueueDisplayName("last_chance"))
        assertEquals("AI", getQueueDisplayName("encore"))
        assertEquals("other", getQueueDisplayName("other"))
    }

    @Test
    fun matchingQueueUrl_requiresTrustedAmazonQueuePage() {
        val target = buildQueueUrl("last_chance")

        assertTrue(isMatchingQueueUrl(target, "last_chance"))
        assertFalse(isMatchingQueueUrl(target, "potluck"))
        assertTrue(isMatchingQueueUrl("https://www.amazon.com/vine/vine-items", "potluck"))
        assertFalse(
            isMatchingQueueUrl(
                "https://www.amazon.com/vine/vine-items",
                "last_chance"
            )
        )
        assertFalse(
            isMatchingQueueUrl(
                "https://amazon.com.attacker.example/vine/vine-items?queue=last_chance",
                "last_chance"
            )
        )
    }

    @Test
    fun productSearchUrl_targetsOnlyTheRequestedAmazonItem() {
        val target = buildQueueProductUrl("encore", "B000TEST01")

        assertTrue(isMatchingProductSearchUrl(target, "encore", "B000TEST01"))
        assertFalse(isMatchingProductSearchUrl(target, "encore", "B000OTHER"))
        assertFalse(isMatchingProductSearchUrl(target, "potluck", "B000TEST01"))
        assertFalse(
            isMatchingProductSearchUrl(
                "https://amazon.com.attacker.example/vine/vine-items?queue=encore&search=B000TEST01",
                "encore",
                "B000TEST01"
            )
        )
    }

    @Test
    fun titleSearchUrl_usesShortSafeProductTerms() {
        assertEquals(
            "https://www.amazon.com/vine/vine-items?queue=encore&search=One%20two%20three%20four%20five%20six%20seven%20eight%20nine%20ten",
            buildQueueTitleSearchUrl(
                "encore",
                "One, two! three four five six seven eight nine ten eleven twelve"
            )
        )
    }

    @Test
    fun detailsClickScript_neverFabricatesAmazonButtons() {
        val script = buildDetailsClickScript("B000TEST01", "test-rec")

        assertTrue(script.contains("document.querySelectorAll('.vvp-details-btn input')"))
        assertTrue(script.contains("document.querySelectorAll('.vvp-item-tile')"))
        assertTrue(script.contains("tile.querySelector('.vvp-item-product-title-container a')"))
        assertTrue(script.contains("(node.getAttribute('data-recommendation-id') || '') === recId"))
        assertFalse(script.contains("=== recId &&"))
        assertTrue(script.contains("toUpperCase() === asin.toUpperCase()"))
        assertTrue(script.contains("clicked_existing"))
        assertTrue(script.contains("not_found"))
        assertFalse(script.contains("createElement('button')"))
        assertTrue(script.contains("createElement('img')"))
        assertFalse(script.contains("appendChild"))
        assertFalse(script.contains("openMockModal"))
    }

    @Test
    fun detailsClickScript_setsFallbackImageWithoutFabricatingElements() {
        val script = buildDetailsClickScript(
            "B000TEST01",
            "test-rec",
            "https://images.example.test/product.jpg",
            "Target Product"
        )
        assertTrue(script.contains("image.src = fallbackImage"))
        assertTrue(script.contains("img#vvp-product-details-modal--hero-image"))
        assertTrue(script.contains("new Image()"))
        assertTrue(script.contains("new MutationObserver"))
        assertTrue(script.contains("vvp-product-details-modal--close-btn"))
        assertTrue(script.contains("image.loading = 'eager'"))
        assertTrue(script.contains("image.decoding = 'sync'"))
        assertFalse(script.contains("createElement('button')"))
        assertTrue(script.contains("createElement('img')"))
    }

    @Test
    fun detailsImageCss_makesAmazonAndFallbackImagesProminent() {
        val css = buildDetailsImageCss()

        assertTrue(css.contains("#vvp-product-details-img-container"))
        assertTrue(css.contains("#vine-helper-details-image"))
        assertTrue(css.contains("clamp(160px, 42vw, 220px)"))
        assertTrue(css.contains("object-fit: contain"))
    }

    @Test
    fun detailsClickResult_acceptsOnlyExistingButtonSuccess() {
        assertTrue(didClickExistingDetailsButton("\"clicked_existing\""))
        assertFalse(didClickExistingDetailsButton("\"not_found\""))
        assertFalse(didClickExistingDetailsButton(null))
    }

    @Test
    fun detailsModalReady_requiresVisibleRequestedProductContent() {
        val script = buildDetailsModalReadyScript("B000TEST01", "Target Product")

        assertTrue(script.contains("#vvp-product-details-modal"))
        assertTrue(script.contains("#vvp-product-details-modal--product-title"))
        assertTrue(script.contains("#vvp-join-btn input"))
        assertTrue(script.contains("[data-asin]"))
        assertTrue(script.contains("/dp/"))
        assertTrue(script.contains("stale_modal"))
        assertTrue(isDetailsModalReady("\"modal_ready\""))
        assertFalse(isDetailsModalReady("\"not_ready\""))
        assertFalse(isDetailsModalReady("\"stale_modal\""))
    }

    @Test
    fun scraperImageExtraction_supportsAmazonLazyImageAttributes() {
        assertTrue(SCRAPER_IMAGE_EXTRACTION_JS.contains("data-lazy-src"))
        assertTrue(SCRAPER_IMAGE_EXTRACTION_JS.contains("data-a-dynamic-image"))
        assertTrue(SCRAPER_IMAGE_EXTRACTION_JS.contains("data-srcset"))
        assertTrue(SCRAPER_IMAGE_EXTRACTION_JS.contains("backgroundImage"))
        assertTrue(SCRAPER_IMAGE_EXTRACTION_JS.contains("picture source"))
    }
}
