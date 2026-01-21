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

    // Flipper detection notifications (base ID, actual ID = base + detection hash)
    const val FLIPPER_DETECTION_BASE = 6000

    // Flipper detection notification group summary
    const val FLIPPER_DETECTION_SUMMARY = 6999
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

    // Flipper detection alerts channel (medium-high priority)
    const val FLIPPER_DETECTION = "flockyou_flipper_detection"

    // Flipper critical alerts channel (high priority, can bypass DND)
    const val FLIPPER_CRITICAL = "flockyou_flipper_critical"
}

/**
 * Notification group keys for grouped notifications.
 */
object NotificationGroupKeys {
    // Group key for Flipper detection notifications
    const val FLIPPER_DETECTIONS = "flockyou_flipper_detections_group"
}
