#pragma once

#include "../../protocol/flock_protocol.h"
#include <furi.h>
#include <furi_hal.h>

// ============================================================================
// NFC Scanner (Passive)
// ============================================================================
// Passive NFC detection that monitors for nearby NFC tags/cards.
// Can run alongside other scanners (doesn't use radio).
// Detects:
// - NFC tags (NTAG, MIFARE, etc.)
// - Payment cards (detects presence, not data)
// - Access cards
// - Potential skimmers (unexpected readers)

// Use FlockNfcScanner to avoid conflict with SDK's NfcScanner
typedef struct FlockNfcScanner FlockNfcScanner;

// ============================================================================
// NFC Types
// ============================================================================

typedef enum {
    FlockNfcTypeUnknown = 0,
    FlockNfcTypeA,           // ISO14443A (MIFARE, NTAG)
    FlockNfcTypeB,           // ISO14443B
    FlockNfcTypeF,           // FeliCa
    FlockNfcTypeV,           // ISO15693 (vicinity)
} FlockNfcType;

// ============================================================================
// Card Types
// ============================================================================

typedef enum {
    FlockNfcCardUnknown = 0,
    FlockNfcCardMifareClassic1K,
    FlockNfcCardMifareClassic4K,
    FlockNfcCardMifareUltralight,
    FlockNfcCardMifareDESFire,
    FlockNfcCardMifarePlus,
    FlockNfcCardNTAG213,
    FlockNfcCardNTAG215,
    FlockNfcCardNTAG216,
    FlockNfcCardPayment,     // Credit/debit card
    FlockNfcCardTransit,     // Transit card
    FlockNfcCardAccess,      // Access control card
    FlockNfcCardPhone,       // Phone emulating NFC
} FlockNfcCardType;

// ============================================================================
// Extended Detection Info
// ============================================================================

typedef struct {
    FlockNfcDetection base;
    FlockNfcCardType card_type;
    uint32_t first_seen;
    uint32_t last_seen;
    uint8_t detection_count;
} FlockNfcDetectionExtended;

// ============================================================================
// Callback
// ============================================================================

typedef void (*FlockNfcScanCallback)(
    const FlockNfcDetectionExtended* detection,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_cards;
    bool detect_tags;
    bool detect_phones;
    bool continuous_poll;        // Keep polling vs single detect

    FlockNfcScanCallback callback;
    void* callback_context;
} FlockNfcScannerConfig;

// ============================================================================
// Statistics
// ============================================================================

typedef struct {
    uint32_t total_detections;
    uint32_t unique_uids;
    uint32_t cards_detected;
    uint32_t tags_detected;
    uint32_t phones_detected;
} FlockNfcScannerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate NFC scanner.
 */
FlockNfcScanner* flock_nfc_scanner_alloc(void);

/**
 * Free NFC scanner.
 */
void flock_nfc_scanner_free(FlockNfcScanner* scanner);

/**
 * Configure the scanner.
 */
void flock_nfc_scanner_configure(
    FlockNfcScanner* scanner,
    const FlockNfcScannerConfig* config);

/**
 * Start NFC polling.
 */
bool flock_nfc_scanner_start(FlockNfcScanner* scanner);

/**
 * Stop NFC polling.
 */
void flock_nfc_scanner_stop(FlockNfcScanner* scanner);

/**
 * Check if scanner is running.
 */
bool flock_nfc_scanner_is_running(FlockNfcScanner* scanner);

/**
 * Get scanner statistics.
 */
void flock_nfc_scanner_get_stats(FlockNfcScanner* scanner, FlockNfcScannerStats* stats);

/**
 * Identify card type from SAK and ATQA.
 */
FlockNfcCardType flock_nfc_scanner_identify_card(uint8_t sak, const uint8_t* atqa, uint8_t uid_len);

/**
 * Get card type name.
 */
const char* flock_nfc_scanner_get_card_name(FlockNfcCardType type);

/**
 * Get NFC type name.
 */
const char* flock_nfc_scanner_get_type_name(FlockNfcType type);
