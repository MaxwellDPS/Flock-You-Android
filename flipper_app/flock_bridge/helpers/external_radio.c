#include "external_radio_internal.h"

// ============================================================================
// Public API - Allocation and Deallocation
// ============================================================================

ExternalRadioManager* external_radio_alloc(void) {
    ExternalRadioManager* manager = malloc(sizeof(ExternalRadioManager));
    if (!manager) return NULL;

    memset(manager, 0, sizeof(ExternalRadioManager));

    manager->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    manager->response_sem = furi_semaphore_alloc(1, 0);

    // Default config
    manager->config.serial_id = FuriHalSerialIdUsart;
    manager->config.baud_rate = 115200;

    manager->state = ExternalRadioStateDisconnected;
    manager->rx_state = RxStateWaitStart;

    FURI_LOG_I(TAG, "External radio manager allocated");
    return manager;
}

void external_radio_free(ExternalRadioManager* manager) {
    if (!manager) return;

    external_radio_stop(manager);

    if (manager->response_sem) {
        furi_semaphore_free(manager->response_sem);
    }
    if (manager->mutex) {
        furi_mutex_free(manager->mutex);
    }

    free(manager);
    FURI_LOG_I(TAG, "External radio manager freed");
}

// ============================================================================
// Public API - Configuration
// ============================================================================

void external_radio_configure(
    ExternalRadioManager* manager,
    const ExternalRadioConfig* config) {

    if (!manager || !config) return;

    furi_mutex_acquire(manager->mutex, FuriWaitForever);
    memcpy(&manager->config, config, sizeof(ExternalRadioConfig));
    furi_mutex_release(manager->mutex);
}

// ============================================================================
// Public API - Lifecycle (Start/Stop)
// ============================================================================

bool external_radio_start(ExternalRadioManager* manager) {
    if (!manager) {
        FURI_LOG_E(TAG, "external_radio_start: manager is NULL");
        return false;
    }

    if (manager->running) {
        FURI_LOG_W(TAG, "external_radio_start: already running");
        return true;  // Already running is not an error
    }

    FURI_LOG_I(TAG, "Starting external radio manager (serial_id=%d, baud=%lu)",
        manager->config.serial_id, (unsigned long)manager->config.baud_rate);

    // Acquire serial
    manager->serial = furi_hal_serial_control_acquire(manager->config.serial_id);
    if (!manager->serial) {
        FURI_LOG_E(TAG, "Failed to acquire serial (id=%d) - may be in use by another app",
            manager->config.serial_id);
        manager->state = ExternalRadioStateError;
        return false;
    }

    // Initialize serial
    furi_hal_serial_init(manager->serial, manager->config.baud_rate);
    furi_hal_serial_async_rx_start(
        manager->serial,
        external_radio_serial_callback,
        manager,
        false);

    manager->serial_active = true;
    manager->state = ExternalRadioStateConnecting;
    manager->rx_state = RxStateWaitStart;
    manager->last_rx_time = furi_get_tick();
    manager->last_heartbeat = furi_get_tick();
    manager->detected_type = ExternalRadioNone;  // Will be set when radio responds

    // Clear any stale sync state
    manager->sync_waiting = false;
    manager->sync_response_buf = NULL;
    manager->sync_response_len = NULL;

    // Start worker thread
    manager->running = true;
    manager->should_stop = false;

    manager->worker_thread = furi_thread_alloc_ex(
        "ExtRadioWorker", 2048, external_radio_worker, manager);
    if (!manager->worker_thread) {
        FURI_LOG_E(TAG, "Failed to allocate worker thread");
        // Clean up serial
        furi_hal_serial_async_rx_stop(manager->serial);
        furi_hal_serial_deinit(manager->serial);
        furi_hal_serial_control_release(manager->serial);
        manager->serial = NULL;
        manager->serial_active = false;
        manager->running = false;
        manager->state = ExternalRadioStateError;
        return false;
    }

    furi_thread_start(manager->worker_thread);

    FURI_LOG_I(TAG, "External radio manager started (worker thread running)");
    return true;
}

void external_radio_stop(ExternalRadioManager* manager) {
    if (!manager) return;

    if (!manager->running) {
        FURI_LOG_D(TAG, "external_radio_stop: not running");
        return;
    }

    FURI_LOG_I(TAG, "Stopping external radio manager");

    // Signal worker thread to stop
    manager->should_stop = true;

    // Release any waiting sync commands
    if (manager->sync_waiting && manager->response_sem) {
        FURI_LOG_D(TAG, "Releasing blocked sync command");
        furi_semaphore_release(manager->response_sem);
    }

    // Wait for worker thread to finish
    if (manager->worker_thread) {
        FURI_LOG_D(TAG, "Waiting for worker thread to finish...");
        furi_thread_join(manager->worker_thread);
        furi_thread_free(manager->worker_thread);
        manager->worker_thread = NULL;
        FURI_LOG_D(TAG, "Worker thread finished");
    }

    // Stop and release serial
    if (manager->serial_active && manager->serial) {
        FURI_LOG_D(TAG, "Releasing serial interface");
        furi_hal_serial_async_rx_stop(manager->serial);
        furi_hal_serial_deinit(manager->serial);
        furi_hal_serial_control_release(manager->serial);
        manager->serial = NULL;
        manager->serial_active = false;
    }

    // Clear state
    manager->running = false;
    manager->state = ExternalRadioStateDisconnected;
    manager->detected_type = ExternalRadioNone;
    manager->sync_waiting = false;
    manager->sync_response_buf = NULL;
    manager->sync_response_len = NULL;
    manager->rx_state = RxStateWaitStart;

    FURI_LOG_I(TAG, "External radio manager stopped");
}

// ============================================================================
// Public API - State Queries
// ============================================================================

bool external_radio_is_connected(ExternalRadioManager* manager) {
    if (!manager) return false;
    return manager->state == ExternalRadioStateConnected;
}

ExternalRadioType external_radio_get_type(ExternalRadioManager* manager) {
    if (!manager) return ExternalRadioNone;
    return manager->detected_type;
}

ExternalRadioState external_radio_get_state(ExternalRadioManager* manager) {
    if (!manager) return ExternalRadioStateDisconnected;
    return manager->state;
}

bool external_radio_get_info(ExternalRadioManager* manager, ExtRadioInfo* info) {
    if (!manager || !info) return false;
    if (manager->state != ExternalRadioStateConnected) return false;

    memcpy(info, &manager->radio_info, sizeof(ExtRadioInfo));
    return true;
}

uint32_t external_radio_get_capabilities(ExternalRadioManager* manager) {
    if (!manager) return 0;
    if (manager->state != ExternalRadioStateConnected) return 0;
    return manager->radio_info.capabilities;
}
