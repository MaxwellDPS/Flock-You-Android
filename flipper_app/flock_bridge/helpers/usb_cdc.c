#include "../flock_bridge_app.h"
#include <furi_hal_usb_cdc.h>

#define TAG "FlockUsbCdc"
#define USB_CDC_RX_BUFFER_SIZE 512
#define USB_CDC_TX_BUFFER_SIZE 512

// ============================================================================
// USB CDC Callbacks
// ============================================================================

// Forward declaration
static int32_t usb_cdc_rx_thread(void* context);

// Note: CDC callback structure changed in newer firmware
// These callbacks are adapted to the new CdcCallbacks structure
static void usb_cdc_ctrl_line_callback(void* context, CdcCtrlLine ctrl_line) {
    FlockUsbCdc* usb = context;

    // DTR (Data Terminal Ready) indicates connection state
    bool connected = (ctrl_line & CdcCtrlLineDTR) != 0;

    if (connected != usb->connected) {
        usb->connected = connected;
        FURI_LOG_I(TAG, "USB CDC ctrl_line: DTR=%d RTS=%d, connected=%d",
            (ctrl_line & CdcCtrlLineDTR) ? 1 : 0,
            (ctrl_line & CdcCtrlLineRTS) ? 1 : 0,
            connected);
    }
}

// State callback - called when USB connection state changes
static void usb_cdc_state_callback(void* context, CdcState state) {
    FlockUsbCdc* usb = context;
    FURI_LOG_I(TAG, "USB CDC state changed: %s",
        state == CdcStateConnected ? "Connected" : "Disconnected");

    if (usb) {
        usb->connected = (state == CdcStateConnected);
    }
}

// RX endpoint callback - called when data arrives on the USB CDC endpoint
// Note: This is called from USB interrupt context - only do minimal work here
static void usb_cdc_rx_ep_callback(void* context) {
    FlockUsbCdc* usb = context;
    if (!usb) return;

    // Just mark that data is available - the polling thread will handle it
    // Don't call furi_hal_cdc_receive here as it may interfere with the polling thread
    FURI_LOG_D(TAG, "RX EP callback - data available");
}

// ============================================================================
// USB CDC RX Thread (with fallback polling)
// ============================================================================

static int32_t usb_cdc_rx_thread(void* context) {
    FlockUsbCdc* usb = context;
    uint8_t buffer[64];
    uint32_t poll_count = 0;

    FURI_LOG_I(TAG, "USB CDC RX thread started (polling channel 0)");

    while (usb->running) {
        // Log periodically
        if (poll_count % 500 == 0) {
            FURI_LOG_D(TAG, "RX poll #%lu, connected=%d", poll_count, usb->connected);
        }
        poll_count++;

        // Poll for data on channel 1
        int32_t received = furi_hal_cdc_receive(0, buffer, sizeof(buffer));
        if (received > 0) {
            FURI_LOG_I(TAG, "RX poll: %ld bytes: %02X %02X %02X %02X",
                (long)received,
                received > 0 ? buffer[0] : 0,
                received > 1 ? buffer[1] : 0,
                received > 2 ? buffer[2] : 0,
                received > 3 ? buffer[3] : 0);

            // Auto-set connected
            usb->connected = true;

            // Write to stream buffer
            furi_stream_buffer_send(usb->rx_stream, buffer, received, 0);

            // Thread-safe callback
            furi_mutex_acquire(usb->mutex, FuriWaitForever);
            void (*callback)(void*, uint8_t*, size_t) = usb->data_callback;
            void* ctx = usb->callback_context;
            furi_mutex_release(usb->mutex);

            if (callback) {
                callback(ctx, buffer, received);
            }
        } else {
            // Small delay to prevent busy-waiting
            furi_delay_ms(10);
        }
    }

    FURI_LOG_I(TAG, "USB CDC RX thread stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

FlockUsbCdc* flock_usb_cdc_alloc(void) {
    FlockUsbCdc* usb = malloc(sizeof(FlockUsbCdc));
    if (!usb) return NULL;

    memset(usb, 0, sizeof(FlockUsbCdc));

    // Allocate mutex
    usb->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Allocate stream buffers
    usb->rx_stream = furi_stream_buffer_alloc(USB_CDC_RX_BUFFER_SIZE, 1);
    usb->tx_stream = furi_stream_buffer_alloc(USB_CDC_TX_BUFFER_SIZE, 1);

    if (!usb->mutex || !usb->rx_stream || !usb->tx_stream) {
        FURI_LOG_E(TAG, "Failed to allocate resources");
        flock_usb_cdc_free(usb);
        return NULL;
    }

    usb->connected = false;
    usb->running = false;

    FURI_LOG_I(TAG, "USB CDC allocated");
    return usb;
}

void flock_usb_cdc_free(FlockUsbCdc* usb) {
    if (!usb) return;

    // Stop if running
    flock_usb_cdc_stop(usb);

    // Free stream buffers
    if (usb->rx_stream) {
        furi_stream_buffer_free(usb->rx_stream);
    }
    if (usb->tx_stream) {
        furi_stream_buffer_free(usb->tx_stream);
    }

    // Free mutex
    if (usb->mutex) {
        furi_mutex_free(usb->mutex);
    }

    free(usb);
    FURI_LOG_I(TAG, "USB CDC freed");
}

bool flock_usb_cdc_start(FlockUsbCdc* usb) {
    if (!usb || usb->running) return false;

    FURI_LOG_I(TAG, "Starting USB CDC");

    // Save current USB interface
    usb->usb_if_prev = furi_hal_usb_get_config();
    FURI_LOG_I(TAG, "Previous USB config: %p", usb->usb_if_prev);

    // Unlock USB if locked
    furi_hal_usb_unlock();

    // Set USB mode to CDC single FIRST
    FURI_LOG_I(TAG, "Switching to USB CDC single mode...");
    if (!furi_hal_usb_set_config(&usb_cdc_single, NULL)) {
        FURI_LOG_E(TAG, "Failed to set USB CDC config");
        return false;
    }

    // Wait for USB re-enumeration
    FURI_LOG_I(TAG, "Waiting for USB re-enumeration...");
    furi_delay_ms(500);

    // Configure CDC callbacks AFTER setting config (channel 0)
    static CdcCallbacks cdc_callbacks = {
        .tx_ep_callback = NULL,
        .rx_ep_callback = usb_cdc_rx_ep_callback,
        .state_callback = usb_cdc_state_callback,
        .ctrl_line_callback = usb_cdc_ctrl_line_callback,
    };
    furi_hal_cdc_set_callbacks(0, &cdc_callbacks, usb);
    FURI_LOG_I(TAG, "CDC callbacks configured on channel 0");

    // Verify the mode switch
    FuriHalUsbInterface* current = furi_hal_usb_get_config();
    if (current != &usb_cdc_single) {
        FURI_LOG_E(TAG, "USB mode switch verification failed: %p != %p", current, &usb_cdc_single);
    } else {
        FURI_LOG_I(TAG, "USB mode switch successful (single CDC)");
    }

    // Start RX thread
    usb->running = true;
    usb->rx_thread = furi_thread_alloc_ex("FlockUsbCdcRx", 1024, usb_cdc_rx_thread, usb);
    furi_thread_start(usb->rx_thread);

    // Send a startup beacon to verify TX path works
    uint8_t beacon[] = {0x01, 0x00, 0x00, 0x00}; // Heartbeat message
    furi_hal_cdc_send(0, beacon, sizeof(beacon));
    FURI_LOG_I(TAG, "Sent startup beacon");

    FURI_LOG_I(TAG, "USB CDC started successfully");
    return true;
}

void flock_usb_cdc_stop(FlockUsbCdc* usb) {
    if (!usb || !usb->running) return;

    FURI_LOG_I(TAG, "Stopping USB CDC");

    // Stop RX thread
    usb->running = false;
    if (usb->rx_thread) {
        furi_thread_join(usb->rx_thread);
        furi_thread_free(usb->rx_thread);
        usb->rx_thread = NULL;
    }

    // Clear callbacks on channel 0
    furi_hal_cdc_set_callbacks(0, NULL, NULL);

    // Restore previous USB interface
    if (usb->usb_if_prev) {
        furi_hal_usb_set_config(usb->usb_if_prev, NULL);
        usb->usb_if_prev = NULL;
    }

    usb->connected = false;
    FURI_LOG_I(TAG, "USB CDC stopped");
}

bool flock_usb_cdc_send(FlockUsbCdc* usb, const uint8_t* data, size_t length) {
    if (!usb || !data || length == 0) {
        return false;
    }

    // Send data via CDC channel 1
    // Note: furi_hal_cdc_send returns void in newer firmware
    // We need to copy to non-const buffer as the API requires non-const
    size_t sent = 0;
    uint8_t tx_buf[64];
    while (sent < length) {
        size_t to_send = length - sent;
        if (to_send > 64) to_send = 64; // CDC max packet size

        memcpy(tx_buf, data + sent, to_send);
        furi_hal_cdc_send(0, tx_buf, to_send);
        sent += to_send;
    }

    FURI_LOG_D(TAG, "TX: %zu bytes via channel 1", length);
    return true;
}

void flock_usb_cdc_set_callback(
    FlockUsbCdc* usb,
    void (*callback)(void* context, uint8_t* data, size_t length),
    void* context) {

    if (!usb) return;

    furi_mutex_acquire(usb->mutex, FuriWaitForever);
    usb->data_callback = callback;
    usb->callback_context = context;
    furi_mutex_release(usb->mutex);
}

// ============================================================================
// App-level Connection Management
// ============================================================================

bool flock_bridge_send_data(FlockBridgeApp* app, const uint8_t* data, size_t length) {
    if (!app || !data || length == 0) return false;

    furi_mutex_acquire(app->mutex, FuriWaitForever);

    bool result = false;

    // BT serial send function (implemented in bt_serial.c)
    extern bool flock_bt_serial_send(FlockBtSerial* bt, const uint8_t* data, size_t length);

    // Try to send via the active connection
    if (app->connection_mode == FlockConnectionUsb && app->usb_connected) {
        result = flock_usb_cdc_send(app->usb_cdc, data, length);
    } else if (app->connection_mode == FlockConnectionBluetooth && app->bt_connected) {
        result = flock_bt_serial_send(app->bt_serial, data, length);
    } else {
        // Try USB first, then BT
        if (app->usb_connected) {
            result = flock_usb_cdc_send(app->usb_cdc, data, length);
        } else if (app->bt_connected) {
            result = flock_bt_serial_send(app->bt_serial, data, length);
        }
    }

    if (result) {
        app->messages_sent++;
    }

    furi_mutex_release(app->mutex);
    return result;
}

const char* flock_bridge_get_connection_status(FlockBridgeApp* app) {
    if (!app) return "Error";

    if (app->usb_connected && app->bt_connected) {
        return "USB + BT";
    } else if (app->usb_connected) {
        return "USB Connected";
    } else if (app->bt_connected) {
        return "BT Connected";
    } else {
        return "Disconnected";
    }
}

void flock_bridge_set_connection_mode(FlockBridgeApp* app, FlockConnectionMode mode) {
    if (!app) return;

    furi_mutex_acquire(app->mutex, FuriWaitForever);
    app->preferred_connection = mode;

    // Update active connection mode based on availability
    if (mode == FlockConnectionUsb && app->usb_connected) {
        app->connection_mode = FlockConnectionUsb;
    } else if (mode == FlockConnectionBluetooth && app->bt_connected) {
        app->connection_mode = FlockConnectionBluetooth;
    } else if (app->usb_connected) {
        app->connection_mode = FlockConnectionUsb;
    } else if (app->bt_connected) {
        app->connection_mode = FlockConnectionBluetooth;
    } else {
        app->connection_mode = FlockConnectionNone;
    }

    furi_mutex_release(app->mutex);

    FURI_LOG_I(TAG, "Connection mode set to: %d (active: %d)", mode, app->connection_mode);
}
