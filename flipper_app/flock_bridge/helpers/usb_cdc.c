#include "../flock_bridge_app.h"
#include <furi_hal_usb_cdc.h>

#define TAG "FlockUsbCdc"
#define USB_CDC_RX_BUFFER_SIZE 512
#define USB_CDC_TX_BUFFER_SIZE 512
#define FLOCK_CDC_CHANNEL 1  // Use channel 1 for Flock, channel 0 stays with CLI

// Forward declarations
static int32_t usb_cdc_rx_thread(void* context);
static void cdc_rx_callback(void* context);
static void cdc_state_callback(void* context, CdcState state);
static void cdc_ctrl_line_callback(void* context, CdcCtrlLine ctrl_lines);

// CDC callbacks for channel 1
static CdcCallbacks cdc_callbacks = {
    .tx_ep_callback = NULL,
    .rx_ep_callback = cdc_rx_callback,
    .state_callback = cdc_state_callback,
    .ctrl_line_callback = cdc_ctrl_line_callback,
    .config_callback = NULL,
};

// ============================================================================
// CDC Callbacks
// ============================================================================

static void cdc_rx_callback(void* context) {
    FlockUsbCdc* usb = context;
    if (!usb || !usb->running) return;

    // Signal RX thread that data is available
    FURI_LOG_D(TAG, "RX callback triggered");

    // Release the semaphore to wake up the RX thread
    if (usb->rx_semaphore) {
        furi_semaphore_release(usb->rx_semaphore);
    }
}

static void cdc_state_callback(void* context, CdcState state) {
    FlockUsbCdc* usb = context;
    if (!usb) return;

    usb->connected = (state == CdcStateConnected);
    FURI_LOG_I(TAG, "CDC state: %s", usb->connected ? "Connected" : "Disconnected");
}

static void cdc_ctrl_line_callback(void* context, CdcCtrlLine ctrl_lines) {
    FlockUsbCdc* usb = context;
    if (!usb) return;

    bool dtr = (ctrl_lines & CdcCtrlLineDTR) != 0;
    bool rts = (ctrl_lines & CdcCtrlLineRTS) != 0;
    FURI_LOG_I(TAG, "CDC ctrl lines: DTR=%d RTS=%d", dtr, rts);

    // Consider connected if DTR is asserted
    if (dtr) {
        usb->connected = true;
    }
}

// ============================================================================
// USB CDC RX Thread (polling channel 1)
// ============================================================================

static int32_t usb_cdc_rx_thread(void* context) {
    FlockUsbCdc* usb = context;
    uint8_t buffer[64];
    uint32_t poll_count = 0;

    FURI_LOG_I(TAG, "USB CDC RX thread started (channel %d)", FLOCK_CDC_CHANNEL);

    while (usb->running) {
        // Wait for the semaphore signal from the RX callback, with a timeout
        // to allow periodic polling as a fallback and to check if we should stop
        FuriStatus status = furi_semaphore_acquire(usb->rx_semaphore, 100);

        if (!usb->running) break;

        // Log periodically (every ~5 seconds)
        if (poll_count % 50 == 0) {
            FURI_LOG_D(TAG, "RX poll #%lu, connected=%d", poll_count, usb->connected);
        }
        poll_count++;

        // Poll for data on channel 1 (do this whether signaled or timed out)
        // This ensures we don't miss data even if the callback is not working perfectly
        int32_t received = furi_hal_cdc_receive(FLOCK_CDC_CHANNEL, buffer, sizeof(buffer));
        if (received > 0) {
            FURI_LOG_I(TAG, "RX: %ld bytes: %02X %02X %02X %02X",
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
        } else if (status == FuriStatusErrorTimeout) {
            // No data and timeout - just continue waiting
            // This is normal when no data is being received
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

    // Allocate semaphore for RX signaling (binary semaphore: max 1, initial 0)
    usb->rx_semaphore = furi_semaphore_alloc(1, 0);

    // Allocate stream buffers
    usb->rx_stream = furi_stream_buffer_alloc(USB_CDC_RX_BUFFER_SIZE, 1);
    usb->tx_stream = furi_stream_buffer_alloc(USB_CDC_TX_BUFFER_SIZE, 1);

    if (!usb->mutex || !usb->rx_semaphore || !usb->rx_stream || !usb->tx_stream) {
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

    // Free semaphore
    if (usb->rx_semaphore) {
        furi_semaphore_free(usb->rx_semaphore);
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

    FURI_LOG_I(TAG, "Starting USB CDC (dual mode, channel %d)", FLOCK_CDC_CHANNEL);

    // Check if USB is locked
    if (furi_hal_usb_is_locked()) {
        FURI_LOG_W(TAG, "USB is locked, trying to unlock...");
        furi_hal_usb_unlock();
        furi_delay_ms(50);
    }

    // Save current USB interface
    usb->usb_if_prev = furi_hal_usb_get_config();
    FURI_LOG_I(TAG, "Previous USB config: %p (single=%p, dual=%p)",
        usb->usb_if_prev, &usb_cdc_single, &usb_cdc_dual);

    // Switch to dual CDC mode - this gives us channel 0 (CLI) + channel 1 (Flock)
    if (!furi_hal_usb_set_config(&usb_cdc_dual, NULL)) {
        FURI_LOG_E(TAG, "Failed to switch to dual CDC mode (USB might be locked)");
        // Try fallback: use single mode channel 0 (shared with CLI)
        FURI_LOG_W(TAG, "Falling back to single CDC mode channel 0");
        usb->usb_if_prev = NULL;  // Don't restore on stop
        // Continue with channel 0 instead
    } else {
        // Wait for USB re-enumeration
        furi_delay_ms(200);
        FURI_LOG_I(TAG, "Switched to dual CDC mode");
    }

    // Set up callbacks on our channel
    furi_hal_cdc_set_callbacks(FLOCK_CDC_CHANNEL, &cdc_callbacks, usb);
    FURI_LOG_I(TAG, "CDC callbacks registered on channel %d", FLOCK_CDC_CHANNEL);

    // Start RX thread
    usb->running = true;
    usb->rx_thread = furi_thread_alloc_ex("FlockUsbCdcRx", 1024, usb_cdc_rx_thread, usb);
    furi_thread_start(usb->rx_thread);

    // Don't assume connected - wait for actual communication or DTR signal
    usb->connected = false;

    // Give the USB stack a moment to settle
    furi_delay_ms(100);

    // Send multiple startup beacons to verify TX path works
    uint8_t beacon[] = {0x01, 0x00, 0x00, 0x00}; // Heartbeat message
    for (int i = 0; i < 3; i++) {
        furi_hal_cdc_send(FLOCK_CDC_CHANNEL, beacon, sizeof(beacon));
        furi_delay_ms(50);
    }
    FURI_LOG_I(TAG, "Sent startup beacons on channel %d", FLOCK_CDC_CHANNEL);

    FURI_LOG_I(TAG, "USB CDC started successfully (connected=%d)", usb->connected);
    return true;
}

void flock_usb_cdc_stop(FlockUsbCdc* usb) {
    if (!usb || !usb->running) return;

    FURI_LOG_I(TAG, "Stopping USB CDC");

    // Stop RX thread
    usb->running = false;

    // Release semaphore to wake up the RX thread so it can exit
    if (usb->rx_semaphore) {
        furi_semaphore_release(usb->rx_semaphore);
    }

    if (usb->rx_thread) {
        furi_thread_join(usb->rx_thread);
        furi_thread_free(usb->rx_thread);
        usb->rx_thread = NULL;
    }

    // Clear callbacks on channel 1
    furi_hal_cdc_set_callbacks(FLOCK_CDC_CHANNEL, NULL, NULL);

    // Restore previous USB interface (back to single CDC)
    if (usb->usb_if_prev) {
        FURI_LOG_I(TAG, "Restoring USB config to %p", usb->usb_if_prev);
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
    uint32_t max_chunks = (length / 64) + 10;  // Safety limit to prevent infinite loop
    uint32_t chunks_sent = 0;

    while (sent < length && chunks_sent < max_chunks) {
        size_t to_send = length - sent;
        if (to_send > 64) to_send = 64; // CDC max packet size

        memcpy(tx_buf, data + sent, to_send);
        furi_hal_cdc_send(FLOCK_CDC_CHANNEL, tx_buf, to_send);
        sent += to_send;
        chunks_sent++;

        // Yield briefly after each chunk to prevent blocking the system
        // and allow the USB hardware to drain its buffer
        if (sent < length) {
            furi_delay_us(100);  // 100us delay between chunks
        }
    }

    if (sent < length) {
        FURI_LOG_W(TAG, "TX incomplete: sent %zu of %zu bytes", sent, length);
        return false;
    }

    FURI_LOG_D(TAG, "TX: %zu bytes via channel %d", length, FLOCK_CDC_CHANNEL);
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

    // Mutex is FuriMutexTypeRecursive, so safe to acquire even if caller holds it
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
        // Note: LED blink removed from routine data send to avoid overwhelming LED during sustained comms
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

// ============================================================================
// USB CDC Pause/Resume for IR Scanning
// ============================================================================
// On Flipper Zero, dual USB CDC mode uses DMA/timer resources that can
// conflict with the IR receiver. When IR scanning is needed, we temporarily
// pause USB CDC by switching to single CDC mode (which uses fewer resources).

bool flock_usb_cdc_pause(FlockUsbCdc* usb) {
    if (!usb) return false;

    furi_mutex_acquire(usb->mutex, FuriWaitForever);

    // Already paused or not running
    if (usb->paused || !usb->running) {
        furi_mutex_release(usb->mutex);
        return true;
    }

    FURI_LOG_I(TAG, "Pausing USB CDC for IR scanning");

    // Stop the RX thread
    usb->running = false;

    // Release semaphore to wake up the RX thread so it can exit
    if (usb->rx_semaphore) {
        furi_semaphore_release(usb->rx_semaphore);
    }

    furi_mutex_release(usb->mutex);

    // Wait for RX thread to stop (must be done outside mutex)
    if (usb->rx_thread) {
        furi_thread_join(usb->rx_thread);
        furi_thread_free(usb->rx_thread);
        usb->rx_thread = NULL;
    }

    furi_mutex_acquire(usb->mutex, FuriWaitForever);

    // Clear callbacks on channel 1
    furi_hal_cdc_set_callbacks(FLOCK_CDC_CHANNEL, NULL, NULL);

    // Switch back to single CDC mode to free up DMA/timer resources
    // This releases the resources that conflict with IR
    if (usb->usb_if_prev) {
        // We already have the previous config saved
        FURI_LOG_I(TAG, "Switching to single CDC mode (freeing resources)");
        furi_hal_usb_set_config(&usb_cdc_single, NULL);
        furi_delay_ms(50);
    }

    usb->paused = true;
    usb->connected = false;

    furi_mutex_release(usb->mutex);

    FURI_LOG_I(TAG, "USB CDC paused - IR scanner can now run");
    return true;
}

bool flock_usb_cdc_resume(FlockUsbCdc* usb) {
    if (!usb) return false;

    furi_mutex_acquire(usb->mutex, FuriWaitForever);

    // Not paused
    if (!usb->paused) {
        furi_mutex_release(usb->mutex);
        return true;  // Already resumed
    }

    FURI_LOG_I(TAG, "Resuming USB CDC after IR scanning");

    // Switch back to dual CDC mode
    if (!furi_hal_usb_set_config(&usb_cdc_dual, NULL)) {
        FURI_LOG_E(TAG, "Failed to restore dual CDC mode");
        usb->paused = false;  // Mark as not paused to avoid stuck state
        furi_mutex_release(usb->mutex);
        return false;
    }

    // Wait for USB re-enumeration
    furi_delay_ms(100);
    FURI_LOG_I(TAG, "Restored dual CDC mode");

    // Re-register callbacks on channel 1
    furi_hal_cdc_set_callbacks(FLOCK_CDC_CHANNEL, &cdc_callbacks, usb);

    // Restart RX thread
    usb->running = true;
    usb->paused = false;
    usb->rx_thread = furi_thread_alloc_ex("FlockUsbCdcRx", 1024, usb_cdc_rx_thread, usb);
    furi_thread_start(usb->rx_thread);

    // Keep previous connected state (will be set true when DTR received or data comes in)

    furi_mutex_release(usb->mutex);

    FURI_LOG_I(TAG, "USB CDC resumed - IR scanner should stop");
    return true;
}

bool flock_usb_cdc_is_paused(FlockUsbCdc* usb) {
    if (!usb) return false;

    furi_mutex_acquire(usb->mutex, FuriWaitForever);
    bool paused = usb->paused;
    furi_mutex_release(usb->mutex);

    return paused;
}

bool flock_usb_cdc_is_running(FlockUsbCdc* usb) {
    if (!usb) return false;

    furi_mutex_acquire(usb->mutex, FuriWaitForever);
    bool running = usb->running && !usb->paused;
    furi_mutex_release(usb->mutex);

    return running;
}
