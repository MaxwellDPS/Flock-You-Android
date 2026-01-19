package com.flockyou.util

/**
 * Centralized notification IDs to prevent conflicts between different
 * notification sources in the app.
 *
 * Each notification ID should be unique across the entire application.
 * Using a centralized file prevents accidental ID collisions that could
 * cause notifications to be overwritten unexpectedly.
 */
object NotificationIds {
    // Scanning service foreground notification
    const val SCANNING_SERVICE = 1001

    // Detection alert notifications (base ID, actual ID = base + detection hash)
    const val DETECTION_ALERT_BASE = 2000

    // Dead man's switch warning notification
    const val DEAD_MAN_SWITCH_WARNING = 9999

    // Nuke in progress notification
    const val NUKE_IN_PROGRESS = 3001

    // Quick wipe tile notification
    const val QUICK_WIPE = 3002

    // Update available notification
    const val UPDATE_AVAILABLE = 4001

    // Permission reminder notification
    const val PERMISSION_REMINDER = 5001
}

/**
 * Centralized notification channel IDs.
 */
object NotificationChannelIds {
    // Main scanning service channel
    const val SCANNING = "flockyou_scanning"

    // Detection alerts channel
    const val DETECTION_ALERTS = "flockyou_detection_alerts"

    // Dead man's switch warning channel
    const val DEAD_MAN_SWITCH = "dead_man_switch_warning"

    // Critical alerts channel (high priority)
    const val CRITICAL_ALERTS = "flockyou_critical"

    // Update notifications channel
    const val UPDATES = "flockyou_updates"
}
