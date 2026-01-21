#include "bt_serial.h"
#include <bt/bt_service/bt.h>
#include <furi_hal_bt.h>
#include <profiles/serial_profile.h>
#include <services/serial_service.h>

#define TAG "FlockBtSerial"
#define BT_SERIAL_BUFFER_SIZE 512

// ============================================================================
// Bluetooth Serial Structure
// ============================================================================

struct FlockBtSerial {
    Bt* bt;
    FuriHalBleProfileBase* profile;
    bool connected;
    bool running;
    bool paused;  // True when temporarily stopped for BLE scanning

    FuriStreamBuffer* rx_stream;
    FuriMutex* mutex;

    // Callback for received data
    void (*data_callback)(void* context, uint8_t* data, size_t length);
    void* callback_context;

    // Callback for connection state changes
    void (*state_callback)(void* context, bool connected);
    void* state_callback_context;
};

// ============================================================================
// Bluetooth Callbacks
// ============================================================================

static uint16_t bt_serial_event_callback(SerialServiceEvent event, void* context) {
    FlockBtSerial* bt = context;
    if (!bt) return 0;

    if (event.event == SerialServiceEventTypeDataReceived) {
        if (event.data.buffer && event.data.size > 0) {
            // Thread-safe callback access
            furi_mutex_acquire(bt->mutex, FuriWaitForever);
            void (*callback)(void*, uint8_t*, size_t) = bt->data_callback;
            void* ctx = bt->callback_context;
            furi_mutex_release(bt->mutex);

            if (callback) {
                callback(ctx, event.data.buffer, event.data.size);
            }

            // Also store in stream buffer for later retrieval
            furi_stream_buffer_send(bt->rx_stream, event.data.buffer, event.data.size, 0);
        }
        return event.data.size;
    } else if (event.event == SerialServiceEventTypeDataSent) {
        // Data was sent successfully
        FURI_LOG_D(TAG, "Data sent: %d bytes", event.data.size);
    } else if (event.event == SerialServiceEventTypesBleResetRequest) {
        FURI_LOG_W(TAG, "BLE reset requested");
    }

    return 0;
}

static void bt_status_callback(BtStatus status, void* context) {
    FlockBtSerial* bt = context;
    if (!bt) return;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

    bool was_connected = bt->connected;

    switch (status) {
    case BtStatusAdvertising:
        FURI_LOG_I(TAG, "Bluetooth advertising");
        bt->connected = false;
        break;
    case BtStatusConnected:
        FURI_LOG_I(TAG, "Bluetooth connected");
        bt->connected = true;
        break;
    case BtStatusOff:
        FURI_LOG_I(TAG, "Bluetooth off");
        bt->connected = false;
        break;
    case BtStatusUnavailable:
        FURI_LOG_I(TAG, "Bluetooth unavailable");
        bt->connected = false;
        break;
    default:
        break;
    }

    // Notify state change callback
    bool is_connected = bt->connected;
    void (*callback)(void*, bool) = bt->state_callback;
    void* ctx = bt->state_callback_context;

    furi_mutex_release(bt->mutex);

    // Call callback outside mutex to prevent deadlocks
    if (callback && was_connected != is_connected) {
        callback(ctx, is_connected);
    }
}

// ============================================================================
// Public API
// ============================================================================

FlockBtSerial* flock_bt_serial_alloc(void) {
    FlockBtSerial* bt = malloc(sizeof(FlockBtSerial));
    if (!bt) return NULL;

    memset(bt, 0, sizeof(FlockBtSerial));

    bt->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    bt->rx_stream = furi_stream_buffer_alloc(BT_SERIAL_BUFFER_SIZE, 1);

    if (!bt->mutex || !bt->rx_stream) {
        FURI_LOG_E(TAG, "Failed to allocate resources");
        flock_bt_serial_free(bt);
        return NULL;
    }

    bt->connected = false;
    bt->running = false;
    bt->profile = NULL;

    FURI_LOG_I(TAG, "Bluetooth Serial allocated");
    return bt;
}

void flock_bt_serial_free(FlockBtSerial* bt) {
    if (!bt) return;

    flock_bt_serial_stop(bt);

    if (bt->rx_stream) {
        furi_stream_buffer_free(bt->rx_stream);
    }

    if (bt->mutex) {
        furi_mutex_free(bt->mutex);
    }

    free(bt);
    FURI_LOG_I(TAG, "Bluetooth Serial freed");
}

bool flock_bt_serial_start(FlockBtSerial* bt) {
    if (!bt || bt->running) return false;

    FURI_LOG_I(TAG, "Starting Bluetooth Serial");

    // Open Bluetooth service
    bt->bt = furi_record_open(RECORD_BT);
    if (!bt->bt) {
        FURI_LOG_E(TAG, "Failed to open BT record");
        return false;
    }

    // Set status callback
    bt_set_status_changed_callback(bt->bt, bt_status_callback, bt);

    // Start the serial profile
    bt->profile = bt_profile_start(bt->bt, ble_profile_serial, NULL);
    if (!bt->profile) {
        FURI_LOG_E(TAG, "Failed to start BT serial profile");
        bt_set_status_changed_callback(bt->bt, NULL, NULL);
        furi_record_close(RECORD_BT);
        bt->bt = NULL;
        return false;
    }

    // Set the serial event callback
    ble_profile_serial_set_event_callback(
        bt->profile,
        BT_SERIAL_BUFFER_SIZE,
        bt_serial_event_callback,
        bt);

    bt->running = true;
    FURI_LOG_I(TAG, "Bluetooth Serial started");
    return true;
}

void flock_bt_serial_stop(FlockBtSerial* bt) {
    if (!bt || !bt->running) return;

    FURI_LOG_I(TAG, "Stopping Bluetooth Serial");

    // Clear event callback
    if (bt->profile) {
        ble_profile_serial_set_event_callback(bt->profile, 0, NULL, NULL);
    }

    // Restore default BT profile
    if (bt->bt) {
        bt_profile_restore_default(bt->bt);
    }

    // Clear status callback
    if (bt->bt) {
        bt_set_status_changed_callback(bt->bt, NULL, NULL);
    }

    // Close Bluetooth service
    if (bt->bt) {
        furi_record_close(RECORD_BT);
        bt->bt = NULL;
    }

    bt->profile = NULL;
    bt->running = false;
    bt->connected = false;
    FURI_LOG_I(TAG, "Bluetooth Serial stopped");
}

bool flock_bt_serial_send(FlockBtSerial* bt, const uint8_t* data, size_t length) {
    if (!bt || !bt->running || !bt->profile || !data || length == 0) {
        return false;
    }

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

    if (!bt->connected) {
        furi_mutex_release(bt->mutex);
        return false;
    }

    furi_mutex_release(bt->mutex);

    // Send data through BLE serial profile
    // Note: ble_profile_serial_tx expects non-const data
    // Send in chunks if larger than max packet size
    size_t sent = 0;
    while (sent < length) {
        size_t chunk_size = length - sent;
        if (chunk_size > BLE_PROFILE_SERIAL_PACKET_SIZE_MAX) {
            chunk_size = BLE_PROFILE_SERIAL_PACKET_SIZE_MAX;
        }

        // Cast away const - the function doesn't modify the data
        bool result = ble_profile_serial_tx(bt->profile, (uint8_t*)(data + sent), (uint16_t)chunk_size);
        if (!result) {
            FURI_LOG_E(TAG, "Failed to send BT data");
            return false;
        }
        sent += chunk_size;
    }

    return true;
}

bool flock_bt_serial_is_connected(FlockBtSerial* bt) {
    if (!bt) return false;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    bool connected = bt->connected;
    furi_mutex_release(bt->mutex);

    return connected;
}

void flock_bt_serial_set_callback(
    FlockBtSerial* bt,
    void (*callback)(void* context, uint8_t* data, size_t length),
    void* context) {

    if (!bt) return;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    bt->data_callback = callback;
    bt->callback_context = context;
    furi_mutex_release(bt->mutex);
}

void flock_bt_serial_set_state_callback(
    FlockBtSerial* bt,
    void (*callback)(void* context, bool connected),
    void* context) {

    if (!bt) return;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    bt->state_callback = callback;
    bt->state_callback_context = context;
    furi_mutex_release(bt->mutex);
}

bool flock_bt_serial_pause(FlockBtSerial* bt) {
    if (!bt) return false;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

    // Already paused or not running
    if (bt->paused || !bt->running) {
        furi_mutex_release(bt->mutex);
        return true;
    }

    FURI_LOG_I(TAG, "Pausing Bluetooth Serial for BLE scanning");

    // Remember if we were connected (client will need to reconnect after resume)
    bool was_connected = bt->connected;
    if (was_connected) {
        FURI_LOG_W(TAG, "BLE client will be disconnected during scan");
    }

    // Stop the serial profile event callback
    if (bt->profile) {
        ble_profile_serial_set_event_callback(bt->profile, 0, NULL, NULL);
    }

    // Restore default BT profile (stops serial, allows scanning)
    if (bt->bt) {
        bt_profile_restore_default(bt->bt);
    }

    bt->profile = NULL;
    bt->connected = false;
    bt->paused = true;

    furi_mutex_release(bt->mutex);

    // Notify state change if we were connected
    if (was_connected && bt->state_callback) {
        bt->state_callback(bt->state_callback_context, false);
    }

    FURI_LOG_I(TAG, "Bluetooth Serial paused");
    return true;
}

bool flock_bt_serial_resume(FlockBtSerial* bt) {
    if (!bt) return false;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

    // Not paused or not running
    if (!bt->paused || !bt->running) {
        furi_mutex_release(bt->mutex);
        return !bt->paused;  // Return true if already resumed
    }

    FURI_LOG_I(TAG, "Resuming Bluetooth Serial after BLE scanning");

    // Restart the serial profile
    bt->profile = bt_profile_start(bt->bt, ble_profile_serial, NULL);
    if (!bt->profile) {
        FURI_LOG_E(TAG, "Failed to restart BT serial profile");
        furi_mutex_release(bt->mutex);
        return false;
    }

    // Re-register the serial event callback
    ble_profile_serial_set_event_callback(
        bt->profile,
        BT_SERIAL_BUFFER_SIZE,
        bt_serial_event_callback,
        bt);

    bt->paused = false;

    furi_mutex_release(bt->mutex);

    FURI_LOG_I(TAG, "Bluetooth Serial resumed - advertising");
    return true;
}

bool flock_bt_serial_is_paused(FlockBtSerial* bt) {
    if (!bt) return false;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    bool paused = bt->paused;
    furi_mutex_release(bt->mutex);

    return paused;
}

bool flock_bt_serial_is_running(FlockBtSerial* bt) {
    if (!bt) return false;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    bool running = bt->running && !bt->paused;
    furi_mutex_release(bt->mutex);

    return running;
}
