package com.flockyou.robot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Base robot class providing common testing utilities for Compose UI tests.
 * Implements the Robot Pattern for cleaner, more maintainable UI tests.
 */
abstract class BaseRobot(protected val composeTestRule: ComposeTestRule) {
    
    /**
     * Waits until the UI is idle.
     */
    fun waitForIdle() {
        composeTestRule.waitForIdle()
    }
    
    /**
     * Waits for a condition with timeout.
     */
    fun waitUntil(
        timeoutMillis: Long = 5000,
        condition: () -> Boolean
    ) {
        composeTestRule.waitUntil(timeoutMillis) { condition() }
    }
    
    /**
     * Checks if a node with the given text exists.
     */
    fun hasText(text: String): Boolean {
        return try {
            composeTestRule.onNodeWithText(text).assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
    }
    
    /**
     * Checks if a node with the given test tag exists.
     */
    fun hasTestTag(tag: String): Boolean {
        return try {
            composeTestRule.onNodeWithTag(tag).assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
    }
    
    /**
     * Scrolls to a node with the given text.
     */
    fun scrollToText(text: String) {
        composeTestRule.onNodeWithText(text).performScrollTo()
    }
    
    /**
     * Scrolls to a node with the given test tag.
     */
    fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo()
    }
    
    /**
     * Takes a screenshot (for debugging purposes).
     */
    fun captureToImage(tag: String) = 
        composeTestRule.onNodeWithTag(tag).captureToImage()
}

/**
 * Extension function to perform a click action with text.
 */
fun ComposeTestRule.clickOnText(text: String) {
    onNodeWithText(text).performClick()
}

/**
 * Extension function to perform a click action with test tag.
 */
fun ComposeTestRule.clickOnTag(tag: String) {
    onNodeWithTag(tag).performClick()
}

/**
 * Extension function to type text in a text field.
 */
fun ComposeTestRule.typeTextWithTag(tag: String, text: String) {
    onNodeWithTag(tag).performTextInput(text)
}

/**
 * Extension function to clear and type text in a text field.
 */
fun ComposeTestRule.replaceTextWithTag(tag: String, text: String) {
    onNodeWithTag(tag).performTextClearance()
    onNodeWithTag(tag).performTextInput(text)
}

/**
 * Extension function to assert a node with text exists.
 */
fun ComposeTestRule.assertTextExists(text: String, substring: Boolean = false) {
    onNodeWithText(text, substring = substring).assertExists()
}

/**
 * Extension function to assert a node with text does not exist.
 */
fun ComposeTestRule.assertTextDoesNotExist(text: String) {
    onNodeWithText(text).assertDoesNotExist()
}

/**
 * Extension function to assert a node with tag exists.
 */
fun ComposeTestRule.assertTagExists(tag: String) {
    onNodeWithTag(tag).assertExists()
}

/**
 * Extension function to assert a node with tag is displayed.
 */
fun ComposeTestRule.assertTagIsDisplayed(tag: String) {
    onNodeWithTag(tag).assertIsDisplayed()
}

/**
 * Extension function to wait for a node with text to appear.
 */
fun ComposeTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5000) {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * Extension function to wait for a node with tag to appear.
 */
fun ComposeTestRule.waitUntilTagExists(tag: String, timeoutMillis: Long = 5000) {
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
