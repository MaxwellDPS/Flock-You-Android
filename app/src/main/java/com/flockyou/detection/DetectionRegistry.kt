package com.flockyou.detection

import android.util.Log
import com.flockyou.data.model.DetectionProtocol
import com.flockyou.data.model.DeviceType
import com.flockyou.detection.handler.DetectionHandler
import com.flockyou.detection.handler.DeviceTypeProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry that coordinates all detection handlers.
 *
 * This registry maintains mappings from protocols and device types to their
 * respective handlers, enabling efficient lookup and routing of detection tasks.
 *
 * Features:
 * - Protocol-based handler lookup
 * - Device type-based handler lookup
 * - Runtime handler registration for extensibility
 * - Device type profile retrieval
 *
 * Usage:
 * ```
 * // Get handler for a specific protocol
 * val bleHandler = registry.getHandler(DetectionProtocol.BLUETOOTH_LE)
 *
 * // Get handler that can detect a device type
 * val airtagHandler = registry.getHandlerForDeviceType(DeviceType.AIRTAG)
 *
 * // Get profile information for a device type
 * val profile = registry.getProfile(DeviceType.STINGRAY_IMSI)
 * ```
 */
@Singleton
class DetectionRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards DetectionHandler<*>>
) {
    companion object {
        private const val TAG = "DetectionRegistry"
    }

    /**
     * Map of protocol to handler.
     * Each protocol should have at most one primary handler.
     */
    private val handlersByProtocol: MutableMap<DetectionProtocol, DetectionHandler<*>> = mutableMapOf()

    /**
     * Map of device type to handler.
     * Multiple device types may map to the same handler.
     */
    private val handlersByDeviceType: MutableMap<DeviceType, DetectionHandler<*>> = mutableMapOf()

    /**
     * Set of all registered handlers (both injected and runtime-registered).
     */
    private val allHandlers: MutableSet<DetectionHandler<*>> = mutableSetOf()

    /**
     * Custom handlers registered at runtime.
     */
    private val customHandlers: MutableSet<DetectionHandler<*>> = mutableSetOf()

    init {
        // Build indexes from injected handlers
        handlers.forEach { handler ->
            registerHandlerInternal(handler, isCustom = false)
        }

        Log.i(TAG, "DetectionRegistry initialized with ${allHandlers.size} handlers")
        Log.d(TAG, "Protocols: ${handlersByProtocol.keys.map { it.name }}")
        Log.d(TAG, "Device types: ${handlersByDeviceType.size} mappings")
    }

    /**
     * Get the handler for a specific detection protocol.
     *
     * @param protocol The protocol to get a handler for
     * @return The handler for this protocol, or null if none registered
     */
    fun getHandler(protocol: DetectionProtocol): DetectionHandler<*>? {
        return handlersByProtocol[protocol]
    }

    /**
     * Get a handler that can detect the given device type.
     *
     * @param deviceType The device type to find a handler for
     * @return A handler capable of detecting this device type, or null if none found
     */
    fun getHandlerForDeviceType(deviceType: DeviceType): DetectionHandler<*>? {
        return handlersByDeviceType[deviceType]
    }

    /**
     * Get all registered handlers.
     *
     * @return List of all handlers (both injected and custom)
     */
    fun getAllHandlers(): List<DetectionHandler<*>> {
        return allHandlers.toList()
    }

    /**
     * Get the device type profile for a specific device type.
     *
     * The profile contains metadata about the device type including
     * which handler is responsible for detecting it.
     *
     * @param deviceType The device type to get a profile for
     * @return The profile, or null if no handler supports this device type
     */
    fun getProfile(deviceType: DeviceType): DeviceTypeProfile? {
        val handler = handlersByDeviceType[deviceType] ?: return null
        return handler.getProfile(deviceType)
    }

    /**
     * Register a custom handler at runtime.
     *
     * This allows for extensibility where users or plugins can add
     * new detection capabilities without modifying the core app.
     *
     * @param handler The handler to register
     */
    fun registerCustomHandler(handler: DetectionHandler<*>) {
        registerHandlerInternal(handler, isCustom = true)
        customHandlers.add(handler)
        Log.i(TAG, "Registered custom handler: ${handler.displayName} for protocol ${handler.protocol}")
    }

    /**
     * Unregister a handler by its protocol.
     *
     * This removes the handler from all indexes. Primarily intended
     * for removing custom handlers.
     *
     * @param protocol The protocol of the handler to unregister
     */
    fun unregisterHandler(protocol: DetectionProtocol) {
        val handler = handlersByProtocol.remove(protocol)
        if (handler != null) {
            // Remove from device type mappings
            val deviceTypesToRemove = handlersByDeviceType.entries
                .filter { it.value == handler }
                .map { it.key }
            deviceTypesToRemove.forEach { handlersByDeviceType.remove(it) }

            // Remove from handler sets
            allHandlers.remove(handler)
            customHandlers.remove(handler)

            Log.i(TAG, "Unregistered handler for protocol: $protocol (${handler.displayName})")
        } else {
            Log.w(TAG, "No handler found to unregister for protocol: $protocol")
        }
    }

    /**
     * Get all custom (runtime-registered) handlers.
     *
     * @return List of custom handlers
     */
    fun getCustomHandlers(): List<DetectionHandler<*>> {
        return customHandlers.toList()
    }

    /**
     * Check if a handler exists for the given protocol.
     *
     * @param protocol The protocol to check
     * @return true if a handler is registered for this protocol
     */
    fun hasHandler(protocol: DetectionProtocol): Boolean {
        return protocol in handlersByProtocol
    }

    /**
     * Check if any handler can detect the given device type.
     *
     * @param deviceType The device type to check
     * @return true if a handler can detect this device type
     */
    fun canDetect(deviceType: DeviceType): Boolean {
        return deviceType in handlersByDeviceType
    }

    /**
     * Get all device types that can be detected.
     *
     * @return Set of all detectable device types
     */
    fun getDetectableDeviceTypes(): Set<DeviceType> {
        return handlersByDeviceType.keys.toSet()
    }

    /**
     * Get all supported protocols.
     *
     * @return Set of protocols that have registered handlers
     */
    fun getSupportedProtocols(): Set<DetectionProtocol> {
        return handlersByProtocol.keys.toSet()
    }

    /**
     * Get handlers grouped by protocol.
     *
     * @return Map of protocol to handler
     */
    fun getHandlersByProtocol(): Map<DetectionProtocol, DetectionHandler<*>> {
        return handlersByProtocol.toMap()
    }

    /**
     * Start monitoring on all handlers.
     */
    fun startAllHandlers() {
        allHandlers.forEach { handler ->
            try {
                handler.startMonitoring()
                Log.d(TAG, "Started handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Stop monitoring on all handlers.
     */
    fun stopAllHandlers() {
        allHandlers.forEach { handler ->
            try {
                handler.stopMonitoring()
                Log.d(TAG, "Stopped handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Update location on all handlers.
     */
    fun updateLocationOnAllHandlers(latitude: Double, longitude: Double) {
        allHandlers.forEach { handler ->
            try {
                handler.updateLocation(latitude, longitude)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update location on handler ${handler.displayName}", e)
            }
        }
    }

    /**
     * Destroy all handlers and clean up resources.
     */
    fun destroyAllHandlers() {
        allHandlers.forEach { handler ->
            try {
                handler.destroy()
                Log.d(TAG, "Destroyed handler: ${handler.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy handler ${handler.displayName}", e)
            }
        }
        allHandlers.clear()
        customHandlers.clear()
        handlersByProtocol.clear()
        handlersByDeviceType.clear()
    }

    /**
     * Internal method to register a handler and build indexes.
     */
    private fun registerHandlerInternal(handler: DetectionHandler<*>, isCustom: Boolean) {
        // Add to all handlers
        allHandlers.add(handler)

        // Map by protocol
        val existingProtocolHandler = handlersByProtocol[handler.protocol]
        if (existingProtocolHandler != null && !isCustom) {
            Log.w(TAG, "Protocol ${handler.protocol} already has handler ${existingProtocolHandler.displayName}, " +
                    "replacing with ${handler.displayName}")
        }
        handlersByProtocol[handler.protocol] = handler

        // Map by device types
        handler.supportedDeviceTypes.forEach { deviceType ->
            val existingDeviceHandler = handlersByDeviceType[deviceType]
            if (existingDeviceHandler != null && existingDeviceHandler != handler) {
                Log.d(TAG, "Device type $deviceType already mapped to ${existingDeviceHandler.displayName}, " +
                        "adding ${handler.displayName} as alternative")
            }
            handlersByDeviceType[deviceType] = handler
        }

        Log.d(TAG, "Registered handler: ${handler.displayName} " +
                "(protocol=${handler.protocol}, deviceTypes=${handler.supportedDeviceTypes.size}, custom=$isCustom)")
    }
}
