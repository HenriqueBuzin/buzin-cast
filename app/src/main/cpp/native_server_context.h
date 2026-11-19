#ifndef NATIVE_SERVER_CONTEXT_H
#define NATIVE_SERVER_CONTEXT_H

#include <memory>
extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "android_raop_callbacks.h"
}
class LogSink;

/* Private native state shared by the bridge and its coverage-only fixture. */
typedef struct {
    raop_t *raop;
    dnssd_t *dnssd;
    android_callback_ctx_t cb_ctx;
    raop_callbacks_t callbacks;
    char hw_addr[6];
    std::shared_ptr<LogSink> log;
} server_ctx_t;

#endif
