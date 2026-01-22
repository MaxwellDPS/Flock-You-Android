#pragma once

#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_usb.h>
#include <furi_hal_usb_cdc.h>
#include <gui/gui.h>
#include <gui/view_dispatcher.h>
#include <gui/scene_manager.h>
#include <gui/modules/widget.h>
#include <gui/modules/submenu.h>
#include <gui/modules/popup.h>
#include <notification/notification_messages.h>

// Forward declarations
typedef struct FlockBridgeApp FlockBridgeApp;
typedef struct FlockBtSerial FlockBtSerial;
typedef struct FlockUsbCdc FlockUsbCdc;
typedef struct FlockWifiScanner FlockWifiScanner;
typedef struct FlockSubGhzScanner FlockSubGhzScanner;
typedef struct FlockBleScanner FlockBleScanner;
typedef struct FlockIrScanner FlockIrScanner;
typedef struct FlockNfcScanner FlockNfcScanner;
typedef struct FlockWipsEngine FlockWipsEngine;
typedef struct ExternalRadioManager ExternalRadioManager;
typedef struct DetectionScheduler DetectionScheduler;
typedef struct CliVcp CliVcp;

// ============================================================================
// Connection Mode
// ============================================================================

typedef enum {
    FlockConnectionNone,
    FlockConnectionBluetooth,
    FlockConnectionUsb,
} FlockConnectionMode;

// ============================================================================
// Radio Source Mode (for user settings)
// ============================================================================

typedef enum {
    FlockRadioSourceAuto,       // Prefer external if available
    FlockRadioSourceInternal,   // Force internal only
    FlockRadioSourceExternal,   // Force external only
    FlockRadioSourceBoth,       // Use both simultaneously
} FlockRadioSourceMode;

// User settings for radio selection
// Packed to ensure consistent binary layout across platforms
typedef struct __attribute__((packed)) {
    FlockRadioSourceMode subghz_source;
    FlockRadioSourceMode ble_source;
    FlockRadioSourceMode wifi_source;  // External only (no internal WiFi)
    bool enable_subghz;
    bool enable_ble;
    bool enable_wifi;
    bool enable_ir;
    bool enable_nfc;
} FlockRadioSettings;

// ============================================================================
// View IDs
// ============================================================================

typedef enum {
    FlockBridgeViewMenu,        // Main navigation menu (submenu)
    FlockBridgeViewMain,        // Info/detail widget display (shared by scanner scenes)
    FlockBridgeViewStatus,      // Status display widget
    FlockBridgeViewSettings,    // Settings submenu
    FlockBridgeViewPopup,       // Popup for alerts and confirmations
} FlockBridgeView;

// ============================================================================
// Scene IDs
// ============================================================================

typedef enum {
    FlockBridgeSceneMain,
    FlockBridgeSceneStatus,
    FlockBridgeSceneWifiScan,
    FlockBridgeSceneSubGhzScan,
    FlockBridgeSceneBleScan,
    FlockBridgeSceneIrScan,
    FlockBridgeSceneNfcScan,
    FlockBridgeSceneWips,
    FlockBridgeSceneSettings,
    FlockBridgeSceneConnection,
    FlockBridgeSceneCount,
} FlockBridgeScene;

// ============================================================================
// Custom Events
// ============================================================================

typedef enum {
    // Connection events
    FlockBridgeEventBtConnected,
    FlockBridgeEventBtDisconnected,
    FlockBridgeEventBtDataReceived,
    FlockBridgeEventUsbConnected,
    FlockBridgeEventUsbDisconnected,
    FlockBridgeEventUsbDataReceived,

    // Scan events
    FlockBridgeEventWifiScanComplete,
    FlockBridgeEventSubGhzDetection,
    FlockBridgeEventBleScanComplete,
    FlockBridgeEventIrDetection,
    FlockBridgeEventNfcDetection,
    FlockBridgeEventWipsAlert,
    FlockBridgeEventRefreshStatus,
} FlockBridgeCustomEvent;

// ============================================================================
// USB CDC Handler Structure
// ============================================================================

struct FlockUsbCdc {
    FuriHalUsbInterface* usb_if_prev;
    FuriThread* rx_thread;
    FuriStreamBuffer* rx_stream;
    FuriStreamBuffer* tx_stream;
    FuriMutex* mutex;
    FuriSemaphore* rx_semaphore;  // Semaphore to signal RX thread when data arrives
    bool connected;
    bool running;
    bool paused;  // True when temporarily stopped for IR scanning

    // CLI VCP handle - must disable before taking CDC
    CliVcp* cli_vcp;

    // Callback for received data
    void (*data_callback)(void* context, uint8_t* data, size_t length);
    void* callback_context;
};

// ============================================================================
// Main Application Structure
// ============================================================================

struct FlockBridgeApp {
    // GUI Components
    Gui* gui;
    ViewDispatcher* view_dispatcher;
    SceneManager* scene_manager;

    // Views
    Widget* widget_main;
    Widget* widget_status;
    Submenu* submenu_main;     // Main navigation menu
    Submenu* submenu_settings; // Settings menu
    Popup* popup;              // For alerts and confirmations

    // Connection Mode
    FlockConnectionMode connection_mode;
    FlockConnectionMode preferred_connection;

    // Bluetooth Serial
    FlockBtSerial* bt_serial;
    bool bt_connected;

    // USB CDC
    FlockUsbCdc* usb_cdc;
    bool usb_connected;

    // External Radio (ESP32/CC1101/nRF24)
    ExternalRadioManager* external_radio;
    bool external_radio_connected;

    // Detection Scheduler (manages all scanners)
    DetectionScheduler* detection_scheduler;

    // WiFi Scanner (via ESP32 board)
    FlockWifiScanner* wifi_scanner;
    bool wifi_board_connected;

    // Sub-GHz Scanner
    FlockSubGhzScanner* subghz_scanner;
    bool subghz_ready;

    // BLE Scanner (internal Flipper BLE)
    FlockBleScanner* ble_scanner;
    bool ble_ready;

    // IR Scanner
    FlockIrScanner* ir_scanner;
    bool ir_ready;

    // NFC Scanner
    FlockNfcScanner* nfc_scanner;
    bool nfc_ready;

    // WIPS Engine
    FlockWipsEngine* wips_engine;

    // Radio Settings (user preferences)
    FlockRadioSettings radio_settings;

    // Statistics
    uint32_t wifi_scan_count;
    uint32_t subghz_detection_count;
    uint32_t ble_scan_count;
    uint32_t ir_detection_count;
    uint32_t nfc_detection_count;
    uint32_t wips_alert_count;
    uint32_t messages_sent;
    uint32_t messages_received;

    // State
    bool scanning_active;
    uint32_t uptime_start;

    // Buffers - sized for typical messages while respecting Flipper RAM limits
    // Note: Messages larger than buffer will be rejected with error
    uint8_t tx_buffer[512];   // TX buffer for outgoing messages
    uint8_t rx_buffer[512];   // RX buffer for incoming messages
    size_t rx_buffer_len;
    uint32_t rx_buffer_timestamp;  // Tick when partial data arrived (for timeout)

    // Notifications
    NotificationApp* notifications;

    // Status update timer
    FuriTimer* status_timer;

    // Mutex for thread safety
    FuriMutex* mutex;
};

// ============================================================================
// Application Lifecycle
// ============================================================================

FlockBridgeApp* flock_bridge_app_alloc(void);
void flock_bridge_app_free(FlockBridgeApp* app);

// ============================================================================
// Connection Management
// ============================================================================

/**
 * Send data to the connected device (auto-selects BT or USB)
 */
bool flock_bridge_send_data(FlockBridgeApp* app, const uint8_t* data, size_t length);

/**
 * Get the current connection status string
 */
const char* flock_bridge_get_connection_status(FlockBridgeApp* app);

/**
 * Switch connection mode
 */
void flock_bridge_set_connection_mode(FlockBridgeApp* app, FlockConnectionMode mode);

// ============================================================================
// USB CDC Functions
// ============================================================================

FlockUsbCdc* flock_usb_cdc_alloc(void);
void flock_usb_cdc_free(FlockUsbCdc* usb);
bool flock_usb_cdc_start(FlockUsbCdc* usb);
void flock_usb_cdc_stop(FlockUsbCdc* usb);
bool flock_usb_cdc_send(FlockUsbCdc* usb, const uint8_t* data, size_t length);
void flock_usb_cdc_set_callback(
    FlockUsbCdc* usb,
    void (*callback)(void* context, uint8_t* data, size_t length),
    void* context);

// ============================================================================
// Scene Handlers
// ============================================================================

extern void (*const flock_bridge_scene_on_enter_handlers[])(void*);
extern bool (*const flock_bridge_scene_on_event_handlers[])(void*, SceneManagerEvent);
extern void (*const flock_bridge_scene_on_exit_handlers[])(void*);
extern const SceneManagerHandlers flock_bridge_scene_handlers;

// Navigation callback
bool flock_bridge_navigation_event_callback(void* context);

// Main entry point
int32_t flock_bridge_app(void* p);

// ============================================================================
// Radio Settings Functions
// ============================================================================

/**
 * Load radio settings from storage.
 */
bool flock_bridge_load_settings(FlockBridgeApp* app);

/**
 * Save radio settings to storage.
 */
bool flock_bridge_save_settings(FlockBridgeApp* app);

/**
 * Apply radio settings to the detection scheduler.
 */
void flock_bridge_apply_radio_settings(FlockBridgeApp* app);

/**
 * Get the name of a radio source mode.
 */
const char* flock_bridge_get_source_name(FlockRadioSourceMode mode);
