#include "nfc_scanner.h"
#include <nfc/nfc.h>
#include <nfc/nfc_poller.h>
#include <nfc/nfc_scanner.h>
#include <nfc/protocols/iso14443_3a/iso14443_3a.h>
#include <nfc/protocols/iso14443_3a/iso14443_3a_poller.h>
#include <string.h>
#include <stdlib.h>

#define TAG "NfcScanner"

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

struct NfcScanner {
    NfcScannerConfig config;
    NfcScannerStats stats;

    // NFC
    Nfc* nfc;
    NfcPoller* poller;

    // State
    bool running;

    // UID history
    UidHistoryEntry uid_history[MAX_UID_HISTORY];
    uint8_t history_count;

    // Thread and sync
    FuriThread* worker_thread;
    FuriMutex* mutex;
    volatile bool should_stop;
};

// ============================================================================
// Card/Type Name Tables
// ============================================================================

const char* nfc_scanner_get_card_name(NfcCardType type) {
    switch (type) {
    case NfcCardMifareClassic1K: return "MIFARE Classic 1K";
    case NfcCardMifareClassic4K: return "MIFARE Classic 4K";
    case NfcCardMifareUltralight: return "MIFARE Ultralight";
    case NfcCardMifareDESFire: return "MIFARE DESFire";
    case NfcCardMifarePlus: return "MIFARE Plus";
    case NfcCardNTAG213: return "NTAG213";
    case NfcCardNTAG215: return "NTAG215";
    case NfcCardNTAG216: return "NTAG216";
    case NfcCardPayment: return "Payment Card";
    case NfcCardTransit: return "Transit Card";
    case NfcCardAccess: return "Access Card";
    case NfcCardPhone: return "Phone/Emulated";
    default: return "Unknown";
    }
}

const char* nfc_scanner_get_type_name(NfcType type) {
    switch (type) {
    case NfcTypeA: return "ISO14443A";
    case NfcTypeB: return "ISO14443B";
    case NfcTypeF: return "FeliCa";
    case NfcTypeV: return "ISO15693";
    default: return "Unknown";
    }
}

// ============================================================================
// Card Identification
// ============================================================================

NfcCardType nfc_scanner_identify_card(uint8_t sak, const uint8_t* atqa, uint8_t uid_len) {
    // SAK (Select Acknowledge) byte tells us the card type
    // https://www.nxp.com/docs/en/application-note/AN10833.pdf

    if (!atqa) return NfcCardUnknown;

    // MIFARE Classic 1K
    if (sak == 0x08) {
        return NfcCardMifareClassic1K;
    }

    // MIFARE Classic 4K
    if (sak == 0x18) {
        return NfcCardMifareClassic4K;
    }

    // MIFARE Ultralight / NTAG
    if (sak == 0x00) {
        // Need to check ATQA to differentiate
        if (atqa[0] == 0x44 && atqa[1] == 0x00) {
            // Could be Ultralight or NTAG
            // Would need to read page 0 to determine exact type
            return NfcCardMifareUltralight;
        }
    }

    // MIFARE DESFire
    if (sak == 0x20) {
        // Could also be MIFARE Plus in SL3 or payment card
        // Check if 7-byte UID (random) - likely payment
        if (uid_len == 7) {
            return NfcCardPayment;
        }
        return NfcCardMifareDESFire;
    }

    // MIFARE Plus
    if (sak == 0x10 || sak == 0x11) {
        return NfcCardMifarePlus;
    }

    // Phone/emulated card (usually has SAK with bit 6 set)
    if (sak & 0x40) {
        return NfcCardPhone;
    }

    // Transit cards often use DESFire or proprietary
    // Would need deeper inspection

    return NfcCardUnknown;
}

// ============================================================================
// UID History Management
// ============================================================================

static bool check_uid_cooldown(NfcScanner* scanner, const uint8_t* uid, uint8_t uid_len, NfcDetectionExtended* out) {
    uint32_t now = furi_get_tick();

    // Look for existing entry
    for (int i = 0; i < MAX_UID_HISTORY; i++) {
        UidHistoryEntry* entry = &scanner->uid_history[i];
        if (entry->valid && entry->uid_len == uid_len &&
            memcmp(entry->uid, uid, uid_len) == 0) {

            // Found existing entry
            bool should_report = (now - entry->last_seen) >= UID_COOLDOWN_MS;

            entry->last_seen = now;
            entry->detection_count++;

            if (out) {
                out->first_seen = entry->last_seen - (entry->detection_count * 1000); // Approximate
                out->last_seen = now;
                out->detection_count = entry->detection_count;
            }

            return should_report;
        }
    }

    // New UID - add to history
    int free_slot = -1;
    uint32_t oldest_time = UINT32_MAX;
    int oldest_slot = 0;

    for (int i = 0; i < MAX_UID_HISTORY; i++) {
        if (!scanner->uid_history[i].valid) {
            free_slot = i;
            break;
        }
        if (scanner->uid_history[i].last_seen < oldest_time) {
            oldest_time = scanner->uid_history[i].last_seen;
            oldest_slot = i;
        }
    }

    if (free_slot < 0) {
        free_slot = oldest_slot;  // Overwrite oldest
    }

    UidHistoryEntry* entry = &scanner->uid_history[free_slot];
    memcpy(entry->uid, uid, uid_len);
    entry->uid_len = uid_len;
    entry->last_seen = now;
    entry->detection_count = 1;
    entry->valid = true;
    scanner->history_count++;

    if (out) {
        out->first_seen = now;
        out->last_seen = now;
        out->detection_count = 1;
    }

    scanner->stats.unique_uids++;
    return true;  // New UID, always report
}

// ============================================================================
// NFC Poller Callback
// ============================================================================

static NfcCommand nfc_poller_callback(NfcGenericEvent event, void* context) {
    NfcScanner* scanner = context;
    if (!scanner || !scanner->running) return NfcCommandStop;

    // Handle ISO14443-3A events
    if (event.protocol == NfcProtocolIso14443_3a) {
        const Iso14443_3aPollerEvent* iso_event = event.event_data;

        if (iso_event->type == Iso14443_3aPollerEventTypeReady) {
            const Iso14443_3aData* data = nfc_poller_get_data(scanner->nfc, NfcProtocolIso14443_3a);

            if (data) {
                furi_mutex_acquire(scanner->mutex, FuriWaitForever);

                NfcDetectionExtended detection = {0};

                // Copy UID
                detection.base.uid_len = data->uid_len;
                memcpy(detection.base.uid, data->uid, data->uid_len);

                // Copy ATQA and SAK
                detection.base.atqa[0] = data->atqa[0];
                detection.base.atqa[1] = data->atqa[1];
                detection.base.sak = data->sak;
                detection.base.type = NfcTypeA;

                // Identify card type
                detection.card_type = nfc_scanner_identify_card(
                    data->sak, data->atqa, data->uid_len);

                // Get type name
                strncpy(detection.base.type_name,
                    nfc_scanner_get_card_name(detection.card_type),
                    sizeof(detection.base.type_name) - 1);

                // Check cooldown and update history
                bool should_report = check_uid_cooldown(
                    scanner, data->uid, data->uid_len, &detection);

                if (should_report) {
                    scanner->stats.total_detections++;

                    // Update type-specific stats
                    switch (detection.card_type) {
                    case NfcCardPayment:
                    case NfcCardMifareClassic1K:
                    case NfcCardMifareClassic4K:
                    case NfcCardMifareDESFire:
                        scanner->stats.cards_detected++;
                        break;
                    case NfcCardPhone:
                        scanner->stats.phones_detected++;
                        break;
                    default:
                        scanner->stats.tags_detected++;
                        break;
                    }

                    // Invoke callback
                    if (scanner->config.callback) {
                        scanner->config.callback(&detection, scanner->config.callback_context);
                    }

                    FURI_LOG_I(TAG, "NFC detected: %s (UID len: %d, SAK: 0x%02X)",
                        detection.base.type_name, detection.base.uid_len, detection.base.sak);
                }

                furi_mutex_release(scanner->mutex);
            }
        }
    }

    // Continue polling if configured for continuous mode
    if (scanner->config.continuous_poll && !scanner->should_stop) {
        return NfcCommandContinue;
    }

    return NfcCommandStop;
}

// ============================================================================
// Worker Thread
// ============================================================================

static int32_t nfc_scanner_worker(void* context) {
    NfcScanner* scanner = context;

    FURI_LOG_I(TAG, "NFC scanner worker started");

    while (!scanner->should_stop) {
        // Start polling
        nfc_poller_start(scanner->poller, nfc_poller_callback, scanner);

        // Wait for poll to complete or stop signal
        while (nfc_poller_is_running(scanner->poller) && !scanner->should_stop) {
            furi_delay_ms(100);
        }

        if (!scanner->should_stop && scanner->config.continuous_poll) {
            // Brief pause between polls
            furi_delay_ms(500);
        } else {
            break;
        }
    }

    nfc_poller_stop(scanner->poller);

    FURI_LOG_I(TAG, "NFC scanner worker stopped");
    return 0;
}

// ============================================================================
// Public API
// ============================================================================

NfcScanner* nfc_scanner_alloc(void) {
    NfcScanner* scanner = malloc(sizeof(NfcScanner));
    if (!scanner) return NULL;

    memset(scanner, 0, sizeof(NfcScanner));

    scanner->mutex = furi_mutex_alloc(FuriMutexTypeNormal);

    // Default config
    scanner->config.detect_cards = true;
    scanner->config.detect_tags = true;
    scanner->config.detect_phones = true;
    scanner->config.continuous_poll = true;

    // Open NFC
    scanner->nfc = nfc_alloc();
    scanner->poller = nfc_poller_alloc(scanner->nfc, NfcProtocolIso14443_3a);

    FURI_LOG_I(TAG, "NFC scanner allocated");
    return scanner;
}

void nfc_scanner_free(NfcScanner* scanner) {
    if (!scanner) return;

    nfc_scanner_stop(scanner);

    if (scanner->poller) {
        nfc_poller_free(scanner->poller);
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

void nfc_scanner_configure(NfcScanner* scanner, const NfcScannerConfig* config) {
    if (!scanner || !config) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(&scanner->config, config, sizeof(NfcScannerConfig));
    furi_mutex_release(scanner->mutex);
}

bool nfc_scanner_start(NfcScanner* scanner) {
    if (!scanner || scanner->running) return false;

    FURI_LOG_I(TAG, "Starting NFC scanner");

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);

    scanner->running = true;
    scanner->should_stop = false;

    // Start worker thread
    scanner->worker_thread = furi_thread_alloc_ex(
        "NfcScanWorker", 2048, nfc_scanner_worker, scanner);
    furi_thread_start(scanner->worker_thread);

    furi_mutex_release(scanner->mutex);

    return true;
}

void nfc_scanner_stop(NfcScanner* scanner) {
    if (!scanner || !scanner->running) return;

    FURI_LOG_I(TAG, "Stopping NFC scanner");

    scanner->should_stop = true;

    if (scanner->worker_thread) {
        furi_thread_join(scanner->worker_thread);
        furi_thread_free(scanner->worker_thread);
        scanner->worker_thread = NULL;
    }

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    scanner->running = false;
    furi_mutex_release(scanner->mutex);
}

bool nfc_scanner_is_running(NfcScanner* scanner) {
    if (!scanner) return false;
    return scanner->running;
}

void nfc_scanner_get_stats(NfcScanner* scanner, NfcScannerStats* stats) {
    if (!scanner || !stats) return;

    furi_mutex_acquire(scanner->mutex, FuriWaitForever);
    memcpy(stats, &scanner->stats, sizeof(NfcScannerStats));
    furi_mutex_release(scanner->mutex);
}
