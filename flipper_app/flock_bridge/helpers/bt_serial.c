#include "bt_serial.h"
#include <bt/bt_service/bt.h>
#include <furi_hal_bt.h>
#include <furi_hal_bt_serial.h>

#define TAG "FlockBtSerial"
#define BT_SERIAL_BUFFER_SIZE 512

// ============================================================================
// Bluetooth Serial Structure
// ============================================================================

struct FlockBtSerial {
    Bt* bt;
    bool connected;
    bool running;

    FuriStreamBuffer* rx_stream;
    FuriMutex* mutex;

    // Callback for received data
    void (*data_callback)(void* context, uint8_t* data, size_t length);
    void* callback_context;
};

// ============================================================================
// Bluetooth Callbacks
// ============================================================================

static void bt_serial_callback(uint8_t* data, size_t size, void* context) {
    FlockBtSerial* bt = context;
    if (!bt || !data || size == 0) return;

    // Thread-safe callback access
    furi_mutex_acquire(bt->mutex, FuriWaitForever);
    void (*callback)(void*, uint8_t*, size_t) = bt->data_callback;
    void* ctx = bt->callback_context;
    furi_mutex_release(bt->mutex);

    if (callback) {
        callback(ctx, data, size);
    }
}

static void bt_status_callback(BtStatus status, void* context) {
    FlockBtSerial* bt = context;
    if (!bt) return;

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

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
    default:
        break;
    }

    furi_mutex_release(bt->mutex);
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

    // Start serial service
    bt_set_profile(bt->bt, BtProfileSerial);
    furi_hal_bt_serial_set_event_callback(BT_SERIAL_BUFFER_SIZE, bt_serial_callback, bt);

    bt->running = true;
    FURI_LOG_I(TAG, "Bluetooth Serial started");
    return true;
}

void flock_bt_serial_stop(FlockBtSerial* bt) {
    if (!bt || !bt->running) return;

    FURI_LOG_I(TAG, "Stopping Bluetooth Serial");

    // Clear callbacks
    furi_hal_bt_serial_set_event_callback(0, NULL, NULL);
    bt_set_status_changed_callback(bt->bt, NULL, NULL);

    // Close Bluetooth service
    if (bt->bt) {
        furi_record_close(RECORD_BT);
        bt->bt = NULL;
    }

    bt->running = false;
    bt->connected = false;
    FURI_LOG_I(TAG, "Bluetooth Serial stopped");
}

bool flock_bt_serial_send(FlockBtSerial* bt, const uint8_t* data, size_t length) {
    if (!bt || !bt->running || !data || length == 0) {
        return false;
    }

    furi_mutex_acquire(bt->mutex, FuriWaitForever);

    if (!bt->connected) {
        furi_mutex_release(bt->mutex);
        return false;
    }

    furi_mutex_release(bt->mutex);

    // Send data via BT serial
    return furi_hal_bt_serial_tx(data, length);
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
