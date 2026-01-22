package com.flockyou.data

/**
 * Protection presets for quick configuration of detection settings.
 * These presets allow users to quickly switch between different sensitivity levels
 * without manually configuring individual patterns and thresholds.
 */
enum class ProtectionPreset(
    val displayName: String,
    val description: String
) {
    ESSENTIAL(
        displayName = "Essential",
        description = "Critical surveillance only - IMSI catchers, StingRay, body cams, Flock cameras"
    ),
    BALANCED(
        displayName = "Balanced",
        description = "Recommended settings with standard thresholds for everyday use"
    ),
    PARANOID(
        displayName = "Paranoid",
        description = "Maximum protection - all patterns enabled with sensitive thresholds"
    ),
    CUSTOM(
        displayName = "Custom",
        description = "User-modified settings"
    );

    /**
     * Returns the set of cellular patterns that should be enabled for this preset.
     */
    fun getEnabledCellularPatterns(): Set<CellularPattern> = when (this) {
        ESSENTIAL -> setOf(
            CellularPattern.ENCRYPTION_DOWNGRADE,  // Critical: StingRay/IMSI catcher indicator
            CellularPattern.SUSPICIOUS_NETWORK_ID,  // Critical: Fake cell tower detection
            CellularPattern.UNKNOWN_CELL_TOWER      // Critical: Unknown tower detection
        )
        BALANCED -> CellularPattern.values().filter { it.defaultEnabled }.toSet()
        PARANOID -> CellularPattern.values().toSet()
        CUSTOM -> CellularPattern.values().filter { it.defaultEnabled }.toSet() // Default for custom
    }

    /**
     * Returns the set of satellite patterns that should be enabled for this preset.
     */
    fun getEnabledSatellitePatterns(): Set<SatellitePattern> = when (this) {
        ESSENTIAL -> setOf(
            SatellitePattern.FORCED_HANDOFF,        // Critical: Forced satellite handoff
            SatellitePattern.DOWNGRADE_TO_SATELLITE // Critical: Forced downgrade
        )
        BALANCED -> SatellitePattern.values().filter { it.defaultEnabled }.toSet()
        PARANOID -> SatellitePattern.values().toSet()
        CUSTOM -> SatellitePattern.values().filter { it.defaultEnabled }.toSet()
    }

    /**
     * Returns the set of BLE patterns that should be enabled for this preset.
     */
    fun getEnabledBlePatterns(): Set<BlePattern> = when (this) {
        ESSENTIAL -> setOf(
            BlePattern.FLOCK_SAFETY_ALPR,   // Critical: Flock Safety ALPR cameras
            BlePattern.HARRIS_STINGRAY,      // Critical: StingRay detection
            BlePattern.AXON_DEVICES,         // Critical: Body cameras
            BlePattern.CELLEBRITE,           // Critical: Phone forensics
            BlePattern.GRAYKEY              // Critical: Phone unlocking
        )
        BALANCED -> BlePattern.values().filter { it.defaultEnabled }.toSet()
        PARANOID -> BlePattern.values().toSet()
        CUSTOM -> BlePattern.values().filter { it.defaultEnabled }.toSet()
    }

    /**
     * Returns the set of WiFi patterns that should be enabled for this preset.
     */
    fun getEnabledWifiPatterns(): Set<WifiPattern> = when (this) {
        ESSENTIAL -> setOf(
            WifiPattern.STINGRAY_WIFI,   // Critical: StingRay WiFi signatures
            WifiPattern.BODY_CAM_WIFI,   // Critical: Body camera hotspots
            WifiPattern.SURVEILLANCE_VAN // Critical: Surveillance vehicle detection
        )
        BALANCED -> WifiPattern.values().filter { it.defaultEnabled }.toSet()
        PARANOID -> WifiPattern.values().toSet()
        CUSTOM -> WifiPattern.values().filter { it.defaultEnabled }.toSet()
    }

    /**
     * Returns a threshold multiplier for this preset.
     * ESSENTIAL = 1.5 (conservative/less sensitive, fewer false positives)
     * BALANCED = 1.0 (default thresholds)
     * PARANOID = 0.7 (sensitive/more alerts, more false positives)
     * CUSTOM = 1.0 (default)
     *
     * For thresholds where LOWER = more sensitive (e.g., signal spike threshold):
     * - Apply multiplier directly: threshold * multiplier
     *
     * For thresholds where HIGHER = more sensitive (e.g., min RSSI):
     * - Use inverse relationship appropriately
     */
    fun getThresholdMultiplier(): Float = when (this) {
        ESSENTIAL -> 1.5f   // Conservative: 50% higher thresholds = fewer alerts
        BALANCED -> 1.0f    // Default thresholds
        PARANOID -> 0.7f    // Sensitive: 30% lower thresholds = more alerts
        CUSTOM -> 1.0f      // Default for custom
    }

    /**
     * Returns adjusted cellular thresholds for this preset.
     */
    fun getCellularThresholds(): CellularThresholds {
        val multiplier = getThresholdMultiplier()
        val default = CellularThresholds()
        return CellularThresholds(
            signalSpikeThreshold = (default.signalSpikeThreshold * multiplier).toInt(),
            rapidSwitchCountStationary = (default.rapidSwitchCountStationary * multiplier).toInt().coerceAtLeast(1),
            rapidSwitchCountMoving = (default.rapidSwitchCountMoving * multiplier).toInt().coerceAtLeast(2),
            trustedCellThreshold = (default.trustedCellThreshold * multiplier).toInt().coerceAtLeast(1),
            minAnomalyIntervalMs = (default.minAnomalyIntervalMs * multiplier).toLong(),
            movementSpeedThreshold = default.movementSpeedThreshold // Keep same
        )
    }

    /**
     * Returns adjusted satellite thresholds for this preset.
     */
    fun getSatelliteThresholds(): SatelliteThresholds {
        val multiplier = getThresholdMultiplier()
        val default = SatelliteThresholds()
        return SatelliteThresholds(
            unexpectedSatelliteThresholdMs = (default.unexpectedSatelliteThresholdMs * multiplier).toLong(),
            rapidHandoffThresholdMs = (default.rapidHandoffThresholdMs * multiplier).toLong(),
            minSignalForTerrestrial = default.minSignalForTerrestrial, // Keep same
            rapidSwitchingWindowMs = (default.rapidSwitchingWindowMs * multiplier).toLong(),
            rapidSwitchingCount = (default.rapidSwitchingCount * multiplier).toInt().coerceAtLeast(1)
        )
    }

    /**
     * Returns adjusted BLE thresholds for this preset.
     * Note: For RSSI values, PARANOID should be more negative (detect weaker signals)
     * and ESSENTIAL should be less negative (only detect stronger/closer signals).
     */
    fun getBleThresholds(): BleThresholds {
        val multiplier = getThresholdMultiplier()
        val default = BleThresholds()

        // For RSSI: lower multiplier means more sensitive (more negative values)
        // PARANOID (0.7): -80 * 0.7 = -56, but we want MORE negative, so we invert
        // ESSENTIAL (1.5): should be LESS negative (closer signals only)
        val rssiAdjustment = when (this) {
            PARANOID -> -10  // More sensitive: extend range by 10 dBm
            ESSENTIAL -> 10  // Less sensitive: reduce range by 10 dBm
            else -> 0
        }

        return BleThresholds(
            minRssiForAlert = (default.minRssiForAlert + rssiAdjustment).coerceIn(-100, -30),
            proximityAlertRssi = (default.proximityAlertRssi + rssiAdjustment).coerceIn(-80, -20),
            trackingDurationMs = (default.trackingDurationMs * multiplier).toLong(),
            minSeenCountForTracking = (default.minSeenCountForTracking * multiplier).toInt().coerceAtLeast(1)
        )
    }

    /**
     * Returns adjusted WiFi thresholds for this preset.
     */
    fun getWifiThresholds(): WifiThresholds {
        val multiplier = getThresholdMultiplier()
        val default = WifiThresholds()

        val signalAdjustment = when (this) {
            PARANOID -> -10  // More sensitive: detect weaker signals
            ESSENTIAL -> 10  // Less sensitive: only strong signals
            else -> 0
        }

        return WifiThresholds(
            minSignalForAlert = (default.minSignalForAlert + signalAdjustment).coerceIn(-90, -40),
            strongSignalThreshold = (default.strongSignalThreshold + signalAdjustment).coerceIn(-70, -30),
            trackingDurationMs = (default.trackingDurationMs * multiplier).toLong(),
            minSeenCountForTracking = (default.minSeenCountForTracking * multiplier).toInt().coerceAtLeast(1),
            minTrackingDistanceMeters = (default.minTrackingDistanceMeters * multiplier)
        )
    }

    /**
     * Returns whether hidden network RF anomaly detection should be enabled for this preset.
     */
    fun getEnableHiddenNetworkRfAnomaly(): Boolean = when (this) {
        ESSENTIAL -> false  // High false positive rate
        BALANCED -> false   // High false positive rate
        PARANOID -> true    // Enable everything
        CUSTOM -> false     // Default
    }

    companion object {
        /**
         * Determines which preset best matches the given settings.
         * Returns CUSTOM if the settings don't match any predefined preset.
         */
        fun detectPreset(settings: DetectionSettings): ProtectionPreset {
            // Check if matches ESSENTIAL
            if (matchesPreset(settings, ESSENTIAL)) return ESSENTIAL

            // Check if matches PARANOID
            if (matchesPreset(settings, PARANOID)) return PARANOID

            // Check if matches BALANCED
            if (matchesPreset(settings, BALANCED)) return BALANCED

            // No match - custom settings
            return CUSTOM
        }

        private fun matchesPreset(settings: DetectionSettings, preset: ProtectionPreset): Boolean {
            return settings.enabledCellularPatterns == preset.getEnabledCellularPatterns() &&
                    settings.enabledSatellitePatterns == preset.getEnabledSatellitePatterns() &&
                    settings.enabledBlePatterns == preset.getEnabledBlePatterns() &&
                    settings.enabledWifiPatterns == preset.getEnabledWifiPatterns() &&
                    settings.enableHiddenNetworkRfAnomaly == preset.getEnableHiddenNetworkRfAnomaly()
        }
    }
}
