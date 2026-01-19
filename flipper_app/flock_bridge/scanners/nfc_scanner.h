#pragma once

#include "../protocol/flock_protocol.h"
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

typedef struct NfcScanner NfcScanner;

// ============================================================================
// NFC Types
// ============================================================================

typedef enum {
    NfcTypeUnknown = 0,
    NfcTypeA,           // ISO14443A (MIFARE, NTAG)
    NfcTypeB,           // ISO14443B
    NfcTypeF,           // FeliCa
    NfcTypeV,           // ISO15693 (vicinity)
} NfcType;

// ============================================================================
// Card Types
// ============================================================================

typedef enum {
    NfcCardUnknown = 0,
    NfcCardMifareClassic1K,
    NfcCardMifareClassic4K,
    NfcCardMifareUltralight,
    NfcCardMifareDESFire,
    NfcCardMifarePlus,
    NfcCardNTAG213,
    NfcCardNTAG215,
    NfcCardNTAG216,
    NfcCardPayment,     // Credit/debit card
    NfcCardTransit,     // Transit card
    NfcCardAccess,      // Access control card
    NfcCardPhone,       // Phone emulating NFC
} NfcCardType;

// ============================================================================
// Extended Detection Info
// ============================================================================

typedef struct {
    FlockNfcDetection base;
    NfcCardType card_type;
    uint32_t first_seen;
    uint32_t last_seen;
    uint8_t detection_count;
} NfcDetectionExtended;

// ============================================================================
// Callback
// ============================================================================

typedef void (*NfcScanCallback)(
    const NfcDetectionExtended* detection,
    void* context);

// ============================================================================
// Configuration
// ============================================================================

typedef struct {
    bool detect_cards;
    bool detect_tags;
    bool detect_phones;
    bool continuous_poll;        // Keep polling vs single detect

    NfcScanCallback callback;
    void* callback_context;
} NfcScannerConfig;

// ============================================================================
// Statistics
// ============================================================================

typedef struct {
    uint32_t total_detections;
    uint32_t unique_uids;
    uint32_t cards_detected;
    uint32_t tags_detected;
    uint32_t phones_detected;
} NfcScannerStats;

// ============================================================================
// API
// ============================================================================

/**
 * Allocate NFC scanner.
 */
NfcScanner* nfc_scanner_alloc(void);

/**
 * Free NFC scanner.
 */
void nfc_scanner_free(NfcScanner* scanner);

/**
 * Configure the scanner.
 */
void nfc_scanner_configure(
    NfcScanner* scanner,
    const NfcScannerConfig* config);

/**
 * Start NFC polling.
 */
bool nfc_scanner_start(NfcScanner* scanner);

/**
 * Stop NFC polling.
 */
void nfc_scanner_stop(NfcScanner* scanner);

/**
 * Check if scanner is running.
 */
bool nfc_scanner_is_running(NfcScanner* scanner);

/**
 * Get scanner statistics.
 */
void nfc_scanner_get_stats(NfcScanner* scanner, NfcScannerStats* stats);

/**
 * Identify card type from SAK and ATQA.
 */
NfcCardType nfc_scanner_identify_card(uint8_t sak, const uint8_t* atqa, uint8_t uid_len);

/**
 * Get card type name.
 */
const char* nfc_scanner_get_card_name(NfcCardType type);

/**
 * Get NFC type name.
 */
const char* nfc_scanner_get_type_name(NfcType type);
