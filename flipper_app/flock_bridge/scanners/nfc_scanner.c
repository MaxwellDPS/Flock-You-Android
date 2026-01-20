#include "nfc_scanner.h"
#include <lib/nfc/nfc.h>
#include <lib/nfc/nfc_scanner.h>
#include <lib/nfc/protocols/nfc_protocol.h>
#include <string.h>
#include <stdlib.h>

#define TAG "FlockNfcScanner"

// ============================================================================
// UID History for Deduplication
// ============================================================================

#define MAX_UID_HISTORY 32
#define UID_COOLDOWN_MS 5000  // Don't report same UID within 5 seconds

typedef struct {
    uint8_t uid[10];
    uint8_t uid_len;
    uint32_t last_seen;
    uint8_t detection_count;
    bool valid;
} UidHistoryEntry;

// ============================================================================
// Scanner Structure
// ============================================================================

struct FlockNfcScanner {
    FlockNfcScannerConfig config;
    FlockNfcScannerStats stats;

    // SDK NFC instances
    Nfc* nfc;
    NfcScanner* sdk_scanner;

    // State
    bool running;

    // UID history
    UidHistoryEntry uid_history[MAX_UID_HISTORY];
    uint8_t history_count;

    // Sync
    FuriMutex* mutex;
};

// ============================================================================
// Card/Type Name Tables
// ============================================================================

const char* flock_nfc_scanner_get_card_name(FlockNfcCardType type) {
    switch (type) {
    case FlockNfcCardMifareClassic1K: return "MIFARE Classic 1K";
    case FlockNfcCardMifareClassic4K: return "MIFARE Classic 4K";
    case FlockNfcCardMifareUltralight: return "MIFARE Ultralight";
    case FlockNfcCardMifareDESFire: return "MIFARE DESFire";
    case FlockNfcCardMifarePlus: return "MIFARE Plus";
    case FlockNfcCardNTAG213: return "NTAG213";
    case FlockNfcCardNTAG215: return "NTAG215";
    case FlockNfcCardNTAG216: return "NTAG216";
    case FlockNfcCardPayment: return "Payment Card";
    case FlockNfcCardTransit: return "Transit Card";
    case FlockNfcCardAccess: return "Access Card";
    case FlockNfcCardPhone: return "Phone/Emulated";
    default: return "Unknown";
    }
}

const char* flock_nfc_scanner_get_type_name(FlockNfcType type) {
    switch (type) {
    case FlockNfcTypeA: return "ISO14443A";
    case FlockNfcTypeB: return "ISO14443B";
    case FlockNfcTypeF: return "FeliCa";
    case FlockNfcTypeV: return "ISO15693";
    default: return "Unknown";
    }
}

// ============================================================================
// Protocol to Card Type Mapping
// ============================================================================

static FlockNfcCardType protocol_to_card_type(NfcProtocol protocol) {
    switch (protocol) {
    case NfcProtocolMfClassic:
        return FlockNfcCardMifareClassic1K;  // Can't distinguish 1K/4K without more data
    case NfcProtocolMfUltralight:
        return FlockNfcCardMifareUltralight;
    case NfcProtocolMfDesfire:
        return FlockNfcCardMifareDESFire;
    case NfcProtocolMfPlus:
        return FlockNfcCardMifarePlus;
    case NfcProtocolIso14443_3a:
    case NfcProtocolIso14443_4a:
        return FlockNfcCardUnknown;  // Generic ISO14443A
    case NfcProtocolIso14443_3b:
    case NfcProtocolIso14443_4b:
        return FlockNfcCardPayment;  // ISO14443B often payment
    case NfcProtocolFelica:
        return FlockNfcCardTransit;  // FeliCa often transit
    case NfcProtocolIso15693_3:
    case NfcProtocolSlix:
        return FlockNfcCardAccess;  // ISO15693 often access cards
    default:
        return FlockNfcCardUnknown;
    }
}

static FlockNfcType protocol_to_nfc_type(NfcProtocol protocol) {
    switch (protocol) {
    case NfcProtocolIso14443_3a:
    case NfcProtocolIso14443_4a:
    case NfcProtocolMfUltralight:
    case NfcProtocolMfClassic:
    case NfcProtocolMfPlus:
    case NfcProtocolMfDesfire:
        return FlockNfcTypeA;
    case NfcProtocolIso14443_3b:
    case NfcProtocolIso14443_4b:
        return FlockNfcTypeB;
    case NfcProtocolFelica:
        return FlockNfcTypeF;
    case NfcProtocolIso15693_3:
    case NfcProtocolSlix:
        return FlockNfcTypeV;
    default:
        return FlockNfcTypeUnknown;
    }
}

// ============================================================================
// Card Identification
// ============================================================================

FlockNfcCardType flock_nfc_scanner_identify_card(uint8_t sak, const uint8_t* atqa, uint8_t uid_len) {
    // SAK (Select Acknowledge) byte tells us the card type
    // https://www.nxp.com/docs/en/application-note/AN10833.pdf

    if (!atqa) return FlockNfcCardUnknown;

    // MIFARE Classic 1K
    if (sak == 0x08) {
        return FlockNfcCardMifareClassic1K;
    }

    // MIFARE Classic 4K
    if (sak == 0x18) {
        return FlockNfcCardMifareClassic4K;
    }

    // MIFARE Ultralight / NTAG
    if (sak == 0x00) {
        // Need to check ATQA to differentiate
        if (atqa[0] == 0x44 && atqa[1] == 0x00) {
            // Could be Ultralight or NTAG
            // Would need to read page 0 to determine exact type
            return FlockNfcCardMifareUltralight;
        }
    }

    // MIFARE DESFire
    if (sak == 0x20) {
        // Could also be MIFARE Plus in SL3 or payment card
        // Check if 7-byte UID (random) - likely payment
        if (uid_len == 7) {
            return FlockNfcCardPayment;
        }
        return FlockNfcCardMifareDESFire;
    }

    // MIFARE Plus
    if (sak == 0x10 || sak == 0x11) {
        return FlockNfcCardMifarePlus;
    }

    // Phone/emulated card (usually has SAK with bit 6 set)
    if (sak & 0x40) {
        return FlockNfcCardPhone;
    }

    return FlockNfcCardUnknown;
}

// ============================================================================
// NFC Scanner Callback
// ============================================================================

static void nfc_scanner_callback(NfcScannerEvent event, void* context) {
    FlockNfcScanner* scanner = context;
    if (!scanner || !scanner->running) return;

    if (event.type == NfcScannerEventTypeDetected) {
        furi_mutex_acquire(scanner->mutex, FuriWaitForever);

        uint32_t now = furi_get_tick();

        // Process each detected protocol
        for (size_t i = 0; i < event.data.protocol_num; i++) {
            NfcProtocol protocol = event.data.protocols[i];

            FlockNfcDetectionExtended detection = {0};
            detection.base.type = (uint8_t)protocol_to_nfc_type(protocol);
            detection.card_type = protocol_to_card_type(protocol);
            detection.first_seen = now;
            detection.last_seen = now;
            detection.detection_count = 1;

            // Update stats
            scanner->stats.total_detections++;

            if (detection.card_type != FlockNfcCardUnknown) {
                scanner->stats.cards_detected++;
            } else {
                scanner->stats.tags_detected++;
            }

            // Invoke user callback
            if (scanner->config.callback) {
                scanner->config.callback(&detection, scanner->config.callback_context);
            }

            FURI_LOG_I(TAG, "NFC detected: %s (%s)",
                flock_nfc_scanner_get_card_name(detection.card_type),
                flock_nfc_scanner_get_type_name((FlockNfcType)detection.base.type));
        }

        furi_mutex_release(scanner->mutex);
    }
}

// ============================================================================
// Public API
// ============================================================================

FlockNfcScanner* flock_nfc_scanner_alloc(void) {
    FlockNfcScanner* scanner = malloc(sizeof(FlockNfcScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(FlockNfcScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.detect_cards = true;
    scanner->config.detect_tags = true;
    scanner->config.detect_phones = true;
    scanner->config.continuous_poll = true;

    // Allocate NFC instance
    scanner->nfc = nfc_alloc();
    if (!scanner->nfc) {
        FURI_LOG_E(TAG, "Failed to allocate NFC");
        flock_nfc_scanner_free(scanner);
        return NULL;
    }

    // Allocate SDK NFC scanner
    scanner->sdk_scanner = nfc_scanner_alloc(scanner->nfc);
    if (!scanner->sdk_scanner) {
        FURI_LOG_E(TAG, "Failed to allocate NFC scanner");
        flock_nfc_scanner_free(scanner);
        return NULL;
    }

    FURI_LOG_I(TAG, "NFC scanner allocated");
    return scanner;
}

void flock_nfc_scanner_free(FlockNfcScanner* scanner) {
    if (!scanner) return;

    flock_nfc_scanner_stop(scanner);

    if (scanner->sdk_scanner) {
        nfc_scanner_free(scanner->sdk_scanner);
    }
    if (scanner->nfc) {
        nfc_free(scanner->nfc);
    }
    if (scanner->mutex) {
        furi_mutex_free(scanner->mutex);
    }

    free(scanner);
    FURI_LOG_I(TAG, "NFC scanner freed");
}

void flock_nfc_scanner_configure(FlockNfcScanner* scanner, const FlockNfcScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(FlockNfcScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool flock_nfc_scanner_start(FlockNfcScanner* scanner) {
    if (!scanner || scanner->running) return false;
    if (!scanner->sdk_scanner) return false;

    FURI_LOG_I(TAG, "Starting NFC scanner");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->running = true;

    // Start the SDK scanner with our callback
    nfc_scanner_start(scanner->sdk_scanner, nfc_scanner_callback, scanner);

    furi_mutex_release(scanner->mutex);

    return true;
}

void flock_nfc_scanner_stop(FlockNfcScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping NFC scanner");

    // Stop the SDK scanner
    if (scanner->sdk_scanner) {
        nfc_scanner_stop(scanner->sdk_scanner);
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    furi_mutex_release(scanner->mutex);
}

bool flock_nfc_scanner_is_running(FlockNfcScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void flock_nfc_scanner_get_stats(FlockNfcScanner* scanner, FlockNfcScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(FlockNfcScannerStats));
    furi_mutex_release(scanner->mutex);
}
