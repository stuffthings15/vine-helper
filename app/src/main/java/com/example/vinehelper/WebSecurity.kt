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

internal fun buildDetailsImageCss(): String = """
    #vvp-product-details-img-container {
        width: clamp(160px, 42vw, 220px) !important;
        min-width: clamp(160px, 42vw, 220px) !important;
        height: clamp(160px, 42vw, 220px) !important;
        margin: 0 !important;
        display: flex !important;
        align-items: center !important;
        justify-content: center !important;
        flex-shrink: 0 !important;
    }
    #vvp-product-details-img-container img,
    #vvp-product-details-modal--hero-image,
    #vine-helper-details-image {
        width: 100% !important;
        height: clamp(160px, 42vw, 220px) !important;
        max-width: 220px !important;
        max-height: 220px !important;
        object-fit: contain !important;
        display: block !important;
        margin: 0 auto !important;
        float: none !important;
    }
""".trimIndent()

internal fun buildDetailsClickScript(
    asin: String,
    recId: String,
    fallbackImageUrl: String = "",
    expectedTitle: String = ""
): String {
    val asinLiteral = javascriptStringLiteral(asin)
    val recIdLiteral = javascriptStringLiteral(recId)
    val fallbackImageLiteral = javascriptStringLiteral(fallbackImageUrl)
    val expectedTitleLiteral = javascriptStringLiteral(expectedTitle)
    return """
        (function() {
            const asin = $asinLiteral;
            const recId = $recIdLiteral;
            const fallbackImage = $fallbackImageLiteral;
            const expectedTitle = $expectedTitleLiteral;
            const normalizeText = function(value) {
                return (value || '')
                    .toLowerCase()
                    .replace(/[^a-z0-9]+/g, ' ')
                    .trim()
                    .replace(/\s+/g, ' ');
            };
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
                existingModal.getBoundingClientRect().width > 0 &&
                existingModal.getBoundingClientRect().height > 0;
            const existingTitle = existingModal ? existingModal.querySelector(
                '#vvp-product-details-modal--product-title, .vvp-details-title'
            ) : null;
            window.vineHelperDetailsRequest = {
                asin: asin,
                recId: recId,
                expectedTitle: expectedTitle,
                previousTitle: normalizeText(existingTitle ? existingTitle.textContent : ''),
                startedAt: Date.now()
            };
            let clickDelay = 0;
            if (modalIsVisible) {
                const closeButton = existingModal.querySelector(
                    '#vvp-product-details-modal--close-btn, .a-button-close, [data-action="a-popover-close"], button[aria-label="Close"]'
                ) || document.querySelector(
                    '#vvp-product-details-modal--close-btn, .a-popover-header .a-button-close'
                );
                if (closeButton) closeButton.click();
                clickDelay = 80;
            }

            let imageObserver = null;
            let imageAttempts = 0;
            const applyFallbackImage = function() {
                if (!fallbackImage) return;
                const modal = document.querySelector(
                    '#vvp-product-details-modal, .vvp-details-modal'
                );
                if (!modal) {
                    imageAttempts += 1;
                    if (imageAttempts < 80) setTimeout(applyFallbackImage, 100);
                    return;
                }
                const modalTitle = modal.querySelector(
                    '#vvp-product-details-modal--product-title, .vvp-details-title'
                );
                const actualTitle = normalizeText(modalTitle ? modalTitle.textContent : '');
                const targetTitle = normalizeText(expectedTitle);
                const asinMatches = Array.from(modal.querySelectorAll('[data-asin], a[href*="/dp/"]')).some(function(node) {
                    const dataAsin = (node.getAttribute('data-asin') || '').toUpperCase();
                    const href = node.getAttribute('href') || '';
                    return dataAsin === asin.toUpperCase() ||
                        href.toUpperCase().indexOf('/DP/' + asin.toUpperCase()) >= 0;
                }) || (modal.getAttribute('data-asin') || '').toUpperCase() === asin.toUpperCase();
                const titleMatches = !!targetTitle && !!actualTitle &&
                    (actualTitle === targetTitle ||
                        actualTitle.indexOf(targetTitle) >= 0 ||
                        targetTitle.indexOf(actualTitle) >= 0);
                if (!asinMatches && targetTitle && !titleMatches) {
                    imageAttempts += 1;
                    if (imageAttempts < 80) setTimeout(applyFallbackImage, 100);
                    return;
                }
                let image = modal.querySelector(
                    'img#vvp-product-details-modal--hero-image, #vvp-product-details-img-container img, #vvp-product-details-modal img'
                );
                if (!image) {
                    const title = modal.querySelector(
                        '#vvp-product-details-modal--product-title, #vvp-product-details-modal .a-link-normal'
                    );
                    if (title && title.parentElement) {
                        image = document.createElement('img');
                        image.id = 'vine-helper-details-image';
                        image.alt = '';
                        image.style.objectFit = 'contain';
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

            const clickTargetWhenModalCloses = function(attempt) {
                const currentModal = document.querySelector(
                    '#vvp-product-details-modal, .vvp-details-modal'
                );
                const currentStyle = currentModal ? window.getComputedStyle(currentModal) : null;
                const currentModalVisible = !!currentModal &&
                    currentStyle.display !== 'none' &&
                    currentStyle.visibility !== 'hidden' &&
                    currentModal.getAttribute('aria-hidden') !== 'true' &&
                    currentModal.getBoundingClientRect().width > 0 &&
                    currentModal.getBoundingClientRect().height > 0;
                if (currentModalVisible && attempt < 12) {
                    setTimeout(function() {
                        clickTargetWhenModalCloses(attempt + 1);
                    }, 60);
                    return;
                }
                if (button.isConnected && !button.disabled) {
                    button.click();
                    if (fallbackImage) setTimeout(applyFallbackImage, 0);
                }
            };
            if (clickDelay === 0) {
                clickTargetWhenModalCloses(0);
            } else {
                setTimeout(function() {
                    clickTargetWhenModalCloses(0);
                }, clickDelay);
            }
            return 'clicked_existing';
        })();
    """.trimIndent()
}

internal fun didClickExistingDetailsButton(rawJavascriptResult: String?): Boolean =
    rawJavascriptResult == "\"clicked_existing\""

internal fun buildDetailsModalReadyScript(asin: String = "", expectedTitle: String = ""): String {
    val asinLiteral = javascriptStringLiteral(asin)
    val expectedTitleLiteral = javascriptStringLiteral(expectedTitle)
    return """
        (function() {
            const asin = $asinLiteral;
            const expectedTitle = $expectedTitleLiteral;
            const normalizeText = function(value) {
                return (value || '')
                    .toLowerCase()
                    .replace(/[^a-z0-9]+/g, ' ')
                    .trim()
                    .replace(/\s+/g, ' ');
            };
            const modalContent = document.querySelector(
                '#vvp-product-details-modal--content'
            );
            const modalHost = document.querySelector(
                '#vvp-product-details-modal, .vvp-details-modal'
            );
            const modal = modalContent
                ? (modalContent.closest('.a-popover') || modalHost || modalContent)
                : modalHost;
            if (!modal) return 'not_ready';
            const style = window.getComputedStyle(modal);
            const isVisible = style.display !== 'none' &&
                style.visibility !== 'hidden' &&
                modal.getAttribute('aria-hidden') !== 'true' &&
                modal.getBoundingClientRect().width > 0 &&
                modal.getBoundingClientRect().height > 0;
            if (!isVisible) return 'not_ready';

            const identityRoot = modalContent || modal;
            const title = identityRoot.querySelector(
                '#vvp-product-details-modal--product-title, .vvp-details-title'
            );
            const requestButton = identityRoot.querySelector(
                '#vvp-join-btn input, #vvp-join-btn button, #vvp-product-details-modal--request-btn'
            );
            const actualTitle = normalizeText(title ? title.textContent : '');
            const targetTitle = normalizeText(expectedTitle);
            if (!actualTitle && !requestButton) return 'not_ready';

            const asinMatches = !!asin && (
                (modal.getAttribute('data-asin') || '').toUpperCase() === asin.toUpperCase() ||
                (modalHost && (modalHost.getAttribute('data-asin') || '').toUpperCase() === asin.toUpperCase()) ||
                Array.from(identityRoot.querySelectorAll('[data-asin], a[href*="/dp/"]')).some(function(node) {
                    const dataAsin = (node.getAttribute('data-asin') || '').toUpperCase();
                    const href = node.getAttribute('href') || '';
                    return dataAsin === asin.toUpperCase() ||
                        href.toUpperCase().indexOf('/DP/' + asin.toUpperCase()) >= 0;
                })
            );

            const targetTokens = targetTitle.split(' ').filter(function(token) {
                return token.length >= 3;
            }).slice(0, 10);
            const matchingTokens = targetTokens.filter(function(token) {
                return actualTitle.split(' ').indexOf(token) >= 0;
            }).length;
            const tokenThreshold = Math.max(1, Math.ceil(targetTokens.length * 0.6));
            const titleMatches = !!targetTitle && !!actualTitle && (
                actualTitle === targetTitle ||
                (actualTitle.length >= 12 && actualTitle.indexOf(targetTitle) >= 0) ||
                (targetTitle.length >= 12 && targetTitle.indexOf(actualTitle) >= 0) ||
                (targetTokens.length > 0 && matchingTokens >= tokenThreshold)
            );

            const request = window.vineHelperDetailsRequest || {};
            const previousTitle = normalizeText(request.previousTitle || '');
            const changedFromPrevious = !!actualTitle &&
                (!previousTitle || actualTitle !== previousTitle);
            const requestMatches = asinMatches ||
                (titleMatches && changedFromPrevious) ||
                (!asin && !targetTitle && (!!actualTitle || requestButton)) ||
                (!targetTitle && request.asin === asin && changedFromPrevious);

            return requestMatches ? 'modal_ready' : 'stale_modal';
        })();
    """.trimIndent()
}

internal fun isDetailsModalReady(rawJavascriptResult: String?): Boolean =
    rawJavascriptResult == "\"modal_ready\""
