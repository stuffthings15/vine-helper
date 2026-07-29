package com.example.vinehelper.ui.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.vinehelper.MainViewModel
import com.example.vinehelper.VineItem
import com.example.vinehelper.theme.VineHelperTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val item = VineItem(
        id = "test-id",
        title = "Test Vine Product",
        imgUrl = "",
        href = "",
        asin = "B000TEST01",
        recId = "test-rec",
        queue = "potluck",
        timestampAdded = System.currentTimeMillis()
    )

    @Test
    fun feedCard_displaysProductAndOpensDetails() {
        val detailsClicked = AtomicBoolean(false)
        composeTestRule.setContent {
            VineHelperTheme {
                FeedItemCard(
                    item = item,
                    tick = System.currentTimeMillis(),
                    onAmazonClick = {},
                    onSeeDetailsClick = { detailsClicked.set(true) },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Test Vine Product").fetchSemanticsNode()
        composeTestRule.onNodeWithText("See Details").performClick()
        assertTrue(detailsClicked.get())
    }

    @Test
    fun feedCard_dismissButtonInvokesDismiss() {
        val dismissed = AtomicBoolean(false)
        composeTestRule.setContent {
            VineHelperTheme {
                FeedItemCard(
                    item = item,
                    tick = System.currentTimeMillis(),
                    onAmazonClick = {},
                    onSeeDetailsClick = {},
                    onDismiss = { dismissed.set(true) }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue(dismissed.get())
    }

    @Test
    fun mainScreen_detachesBrowserFromFeed_andRoutesCategoryClick() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val activeTab = mutableStateOf(0)
        val selectedQueue = AtomicReference<String>()
        val webViewsInitialized = MutableStateFlow(false)
        val initializationProgress = MutableStateFlow(0f)

        composeTestRule.setContent {
            VineHelperTheme {
                MainScreen(
                    viewModel = viewModel,
                    visibleWebView = null,
                    onSeeDetailsClick = { _, _, _ -> },
                    onPickCustomSound = {},
                    onLoginClick = {},
                    onCloseApp = {},
                    onTestNotification = {},
                    onManualRefresh = {},
                    onCategoryClick = { queue ->
                        selectedQueue.set(queue)
                        activeTab.value = 1
                    },
                    activeTabState = activeTab,
                    webViewsInitializedFlow = webViewsInitialized,
                    initializationProgressFlow = initializationProgress
                )
            }
        }

        composeTestRule.onNodeWithText("Vine Helper Feed").fetchSemanticsNode()
        assertTrue(composeTestRule.onAllNodesWithText("Amazon Vine Portal").fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithText("RFY").performClick()
        composeTestRule.waitForIdle()

        assertEquals("potluck", selectedQueue.get())
        assertTrue(composeTestRule.onAllNodesWithText("Vine Helper Feed").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText("Amazon Vine Portal").fetchSemanticsNode()
    }
}