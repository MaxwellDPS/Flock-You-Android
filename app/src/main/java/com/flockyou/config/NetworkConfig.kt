package com.flockyou.config

import com.flockyou.BuildConfig

/**
 * Centralized network URL configuration.
 * All URLs can be overridden via BuildConfig fields, which can be set
 * via gradle.properties for OEM customization.
 *
 * Usage:
 * - Default values are defined in build.gradle.kts defaultConfig
 * - OEMs can override via gradle.properties or command line:
 *   ./gradlew assembleOemRelease -PURL_GITHUB_REPO="https://custom.repo.com"
 *
 * URL Categories:
 * - External Services: GitHub, model downloads
 * - Map Tiles: OpenStreetMap tile servers
 * - Network Checks: Tor verification, DNS checks
 * - Data Sources: OUI database, etc.
 */
object NetworkConfig {

    // ================================================================
    // External Service URLs
    // ================================================================

    /**
     * GitHub repository URL for source code link.
     */
    val GITHUB_REPO_URL: String
        get() = BuildConfig.URL_GITHUB_REPO

    // ================================================================
    // AI Model Download URLs (Hugging Face)
    // ================================================================

    /**
     * Gemma 3 1B model (litert-community, INT4 QAT).
     * Official repository with GPU/CPU support.
     */
    val AI_MODEL_GEMMA3_1B_URL: String
        get() = BuildConfig.URL_AI_MODEL_GEMMA3_1B

    /**
     * Gemma 2B CPU model (t-ghosh repository, INT4).
     * Public repository, no authentication required.
     */
    val AI_MODEL_GEMMA_2B_CPU_URL: String
        get() = BuildConfig.URL_AI_MODEL_GEMMA_2B_CPU

    /**
     * Gemma 2 2B model (t-ghosh repository, INT8).
     * Higher quality, larger size.
     */
    val AI_MODEL_GEMMA_2B_GPU_URL: String
        get() = BuildConfig.URL_AI_MODEL_GEMMA_2B_GPU

    // ================================================================
    // Map Tile Server URLs
    // ================================================================

    /**
     * OpenStreetMap tile servers (A, B, C for load balancing).
     * These can be replaced with custom tile servers for OEM deployments.
     */
    val MAP_TILE_SERVERS: List<String>
        get() = listOf(
            BuildConfig.URL_MAP_TILE_A,
            BuildConfig.URL_MAP_TILE_B,
            BuildConfig.URL_MAP_TILE_C
        )

    /**
     * Primary map tile server URL.
     */
    val MAP_TILE_SERVER_A: String
        get() = BuildConfig.URL_MAP_TILE_A

    /**
     * Secondary map tile server URL.
     */
    val MAP_TILE_SERVER_B: String
        get() = BuildConfig.URL_MAP_TILE_B

    /**
     * Tertiary map tile server URL.
     */
    val MAP_TILE_SERVER_C: String
        get() = BuildConfig.URL_MAP_TILE_C

    // ================================================================
    // Network Check URLs
    // ================================================================

    /**
     * Tor Project check API for verifying Tor connectivity.
     */
    val TOR_CHECK_URL: String
        get() = BuildConfig.URL_TOR_CHECK

    /**
     * IP geolocation API for looking up exit node location.
     * Note: Uses HTTP (not HTTPS) as required by ip-api.com free tier.
     */
    val IP_LOOKUP_URL: String
        get() = BuildConfig.URL_IP_LOOKUP

    /**
     * DNS check endpoints for network RTT measurement.
     * Multiple endpoints for reliability.
     */
    val DNS_CHECK_URLS: List<String>
        get() = listOf(
            BuildConfig.URL_DNS_CHECK_CLOUDFLARE,
            BuildConfig.URL_DNS_CHECK_GOOGLE,
            BuildConfig.URL_DNS_CHECK_OPENDNS
        )

    /**
     * Cloudflare DNS check URL.
     */
    val DNS_CHECK_CLOUDFLARE: String
        get() = BuildConfig.URL_DNS_CHECK_CLOUDFLARE

    /**
     * Google DNS check URL.
     */
    val DNS_CHECK_GOOGLE: String
        get() = BuildConfig.URL_DNS_CHECK_GOOGLE

    /**
     * OpenDNS check URL.
     */
    val DNS_CHECK_OPENDNS: String
        get() = BuildConfig.URL_DNS_CHECK_OPENDNS

    // ================================================================
    // Data Source URLs
    // ================================================================

    /**
     * IEEE OUI database CSV URL for MAC address manufacturer lookup.
     */
    val OUI_DATABASE_URL: String
        get() = BuildConfig.URL_OUI_DATABASE
}
