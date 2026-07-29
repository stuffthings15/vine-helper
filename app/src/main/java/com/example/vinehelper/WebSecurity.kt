package com.example.vinehelper

import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Locale

internal fun isTrustedAmazonUrl(url: String): Boolean {
    return try {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.US) ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "amazon.com" || host == "www.amazon.com")
    } catch (_: Exception) {
        false
    }
}

internal fun javascriptStringLiteral(value: String): String {
    val escaped = StringBuilder(value.length + 2).append('"')
    value.forEach { character ->
        when (character) {
            '"' -> escaped.append("\\\"")
            '\\' -> escaped.append("\\\\")
            '\b' -> escaped.append("\\b")
            '\u000C' -> escaped.append("\\f")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            '\u2028' -> escaped.append("\\u2028")
            '\u2029' -> escaped.append("\\u2029")
            else -> if (character.code < 0x20) {
                escaped.append("\\u%04x".format(character.code))
            } else {
                escaped.append(character)
            }
        }
    }
    return escaped.append('"').toString()
}
internal fun buildQueueUrl(queue: String): String {
    val encodedQueue = URLEncoder.encode(queue, "UTF-8").replace("+", "%20")
    return "https://www.amazon.com/vine/vine-items?queue=$encodedQueue"
}

internal fun buildQueueProductUrl(queue: String, asin: String): String {
    val encodedQueue = URLEncoder.encode(queue, "UTF-8").replace("+", "%20")
    val encodedAsin = URLEncoder.encode(asin, "UTF-8").replace("+", "%20")
    return "https://www.amazon.com/vine/vine-items?queue=$encodedQueue&search=$encodedAsin"
}

internal fun buildQueueTitleSearchUrl(queue: String, title: String): String {
    val searchTerms = title
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(10)
        .joinToString(" ")
        .take(120)
    if (searchTerms.isBlank()) return buildQueueUrl(queue)

    val encodedQueue = URLEncoder.encode(queue, "UTF-8").replace("+", "%20")
    val encodedSearch = URLEncoder.encode(searchTerms, "UTF-8").replace("+", "%20")
    return "https://www.amazon.com/vine/vine-items?queue=$encodedQueue&search=$encodedSearch"
}

internal fun isMatchingQueueUrl(url: String, queue: String): Boolean {
    if (!isTrustedAmazonUrl(url)) return false
    return try {
        val uri = URI(url)
        if (uri.path != "/vine/vine-items") return false
        val parameters = uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.isEmpty() || pieces[0].isEmpty()) {
                    null
                } else {
                    val key = URLDecoder.decode(pieces[0], "UTF-8")
                    val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                    key to value
                }
            }
            .toMap()
        (parameters["queue"] ?: "potluck") == queue
    } catch (_: Exception) {
        false
    }
}

internal fun isMatchingProductSearchUrl(url: String, queue: String, asin: String): Boolean {
    if (!isMatchingQueueUrl(url, queue)) return false
    return try {
        val parameters = URI(url).rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.isEmpty() || pieces[0].isEmpty()) null else {
                    URLDecoder.decode(pieces[0], "UTF-8") to
                        URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                }
            }
            .toMap()
        parameters["search"].orEmpty().equals(asin, ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}

internal fun buildDetailsClickScript(asin: String, recId: String, fallbackImageUrl: String = ""): String {
    val asinLiteral = javascriptStringLiteral(asin)
    val recIdLiteral = javascriptStringLiteral(recId)
    val fallbackImageLiteral = javascriptStringLiteral(fallbackImageUrl)
    return """
        (function() {
            const asin = $asinLiteral;
            const recId = $recIdLiteral;
            const fallbackImage = $fallbackImageLiteral;
            const buttons = Array.from(document.querySelectorAll('.vvp-details-btn input'));
            const exactRecommendation = recId ? buttons.find(function(node) {
                return (node.getAttribute('data-recommendation-id') || '') === recId;
            }) : null;
            const exactAsinButton = buttons.find(function(node) {
                return (node.getAttribute('data-asin') || '').toUpperCase() === asin.toUpperCase();
            }) || null;
            const exactTile = Array.from(document.querySelectorAll('.vvp-item-tile')).find(function(tile) {
                const link = tile.querySelector('.vvp-item-product-title-container a');
                const href = link ? (link.getAttribute('href') || '') : '';
                const match = href.match(/\/dp\/([A-Z0-9]{10})(?:[/?]|$)/i);
                return match && match[1].toUpperCase() === asin.toUpperCase();
            }) || null;
            const tileButton = exactTile ? exactTile.querySelector('.vvp-details-btn input') : null;
            const button = exactRecommendation || exactAsinButton || tileButton;

            const buttonWrapper = button ? button.closest('.a-button') : null;
            const isDisabled = !button ||
                !button.isConnected ||
                button.disabled ||
                button.getAttribute('aria-disabled') === 'true' ||
                (buttonWrapper && buttonWrapper.classList.contains('a-button-disabled'));
            if (isDisabled) {
                return 'not_found';
            }

            const existingModal = document.querySelector(
                '#vvp-product-details-modal, .vvp-details-modal'
            );
            const modalStyle = existingModal ? window.getComputedStyle(existingModal) : null;
            const modalIsVisible = !!existingModal &&
                modalStyle.display !== 'none' &&
                modalStyle.visibility !== 'hidden' &&
                existingModal.getAttribute('aria-hidden') !== 'true' &&
                existingModal.getBoundingClientRect().width > 0;
            let clickDelay = 0;
            if (modalIsVisible) {
                const closeButton = existingModal.querySelector(
                    '#vvp-product-details-modal--close-btn, .a-button-close, [data-action="a-popover-close"], button[aria-label="Close"]'
                ) || document.querySelector(
                    '#vvp-product-details-modal--close-btn, .a-popover-header .a-button-close'
                );
                if (closeButton) closeButton.click();
                clickDelay = 120;
            }

            let imageObserver = null;
            let imageAttempts = 0;
            const applyFallbackImage = function() {
                if (!fallbackImage) return;
                let image = document.querySelector(
                    'img#vvp-product-details-modal--hero-image, #vvp-product-details-img-container img, #vvp-product-details-modal img'
                );
                if (!image) {
                    const title = document.querySelector(
                        '#vvp-product-details-modal--product-title, #vvp-product-details-modal .a-link-normal'
                    );
                    if (title && title.parentElement) {
                        image = document.createElement('img');
                        image.id = 'vine-helper-details-image';
                        image.alt = '';
                        image.style.width = '88px';
                        image.style.height = '88px';
                        image.style.objectFit = 'contain';
                        image.style.float = 'left';
                        image.style.margin = '0 12px 8px 0';
                        title.parentElement.insertBefore(image, title);
                    }
                }
                if (image) {
                    const missingImage = !image.getAttribute('src') ||
                        (image.complete && image.naturalWidth === 0) ||
                        (imageAttempts >= 2 && image.naturalWidth === 0);
                    if (missingImage) {
                        image.removeAttribute('srcset');
                        image.removeAttribute('data-src');
                        image.loading = 'eager';
                        image.decoding = 'sync';
                        image.src = fallbackImage;
                    }
                    if (!image.dataset.vineHelperFallbackBound) {
                        image.dataset.vineHelperFallbackBound = 'true';
                        image.addEventListener('error', function() {
                            image.removeAttribute('srcset');
                            image.removeAttribute('data-src');
                            image.src = fallbackImage;
                        });
                    }
                }
                imageAttempts += 1;
                if (imageAttempts < 80) setTimeout(applyFallbackImage, 100);
            };

            if (fallbackImage) {
                const preloader = new Image();
                preloader.src = fallbackImage;
                imageObserver = new MutationObserver(applyFallbackImage);
                imageObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src', 'srcset', 'data-src']
                });
                setTimeout(function() {
                    if (imageObserver) imageObserver.disconnect();
                }, 8000);
            }

            setTimeout(function() {
                if (button.isConnected && !button.disabled) {
                    button.click();
                    if (fallbackImage) setTimeout(applyFallbackImage, 0);
                }
            }, clickDelay);
            return 'clicked_existing';
        })();
    """.trimIndent()
}

internal fun didClickExistingDetailsButton(rawJavascriptResult: String?): Boolean =
    rawJavascriptResult == "\"clicked_existing\""

internal fun buildDetailsModalReadyScript(): String = """
    (function() {
        const modal = document.querySelector(
            '#vvp-product-details-modal, .vvp-details-modal'
        );
        if (!modal) return 'not_ready';
        const style = window.getComputedStyle(modal);
        const isVisible = style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            modal.getAttribute('aria-hidden') !== 'true' &&
            modal.getBoundingClientRect().width > 0 &&
            modal.getBoundingClientRect().height > 0;
        if (!isVisible) return 'not_ready';

        const title = modal.querySelector(
            '#vvp-product-details-modal--product-title, .vvp-details-title'
        );
        const requestButton = modal.querySelector(
            '#vvp-join-btn input, #vvp-join-btn button'
        );
        return ((title && title.textContent.trim()) || requestButton)
            ? 'modal_ready'
            : 'not_ready';
    })();
""".trimIndent()

internal fun isDetailsModalReady(rawJavascriptResult: String?): Boolean =
    rawJavascriptResult == "\"modal_ready\""
