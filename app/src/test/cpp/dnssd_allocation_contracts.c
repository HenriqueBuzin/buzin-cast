#include "dnssd.h"
#include <stdlib.h>
#include <string.h>

enum Allocation { Context, Name, Address, AllocationCount };

typedef struct {
    enum Allocation reject;
    unsigned attempts[AllocationCount];
    unsigned releases[AllocationCount];
    void *live[AllocationCount];
} AllocationContract;

// This standalone contract runs on main after the suite's workers have joined.
// Allocator hooks cannot use emulated TLS, which may itself allocate memory.
static AllocationContract *active;

void *__real_calloc(size_t count, size_t size);
void *__real_malloc(size_t size);
char *__real_strndup(const char *value, size_t length);
void __real_free(void *pointer);

static bool reject(enum Allocation kind) {
    if (!active) return false;
    active->attempts[kind]++;
    return active->reject == kind;
}

static void *record(enum Allocation kind, void *pointer) {
    if (active) active->live[kind] = pointer;
    return pointer;
}

void *__wrap_calloc(size_t count, size_t size) {
    return reject(Context) ? NULL : record(Context, __real_calloc(count, size));
}

char *__wrap_strndup(const char *value, size_t length) {
    return reject(Name) ? NULL : record(Name, __real_strndup(value, length));
}

void *__wrap_malloc(size_t size) {
    return reject(Address) ? NULL : record(Address, __real_malloc(size));
}

void __wrap_free(void *pointer) {
    if (active && pointer) {
        for (int kind = 0; kind < AllocationCount; kind++) {
            if (active->live[kind] == pointer) {
                active->live[kind] = NULL;
                active->releases[kind]++;
                break;
            }
        }
    }
    __real_free(pointer);
}

static int checkAllocationFailures(const char *name, int nameLength, const char *address,
                                   int addressLength, int *error) {
    for (enum Allocation kind = Context; kind < AllocationCount; kind++) {
        AllocationContract contract = {.reject = kind};
        if (error) *error = -1;
        active = &contract;
        dnssd_t *failed = dnssd_init(name, nameLength, address, addressLength, error, 0);
        active = NULL;
        if (failed) {
            dnssd_destroy(failed);
            return __LINE__;
        }
        if (error && *error != DNSSD_ERROR_OUTOFMEM) return __LINE__;
        for (enum Allocation resource = Context; resource < AllocationCount; resource++) {
            const bool attempted = resource == Context || kind != Context;
            const bool allocated = attempted && resource != kind;
            if (contract.attempts[resource] != attempted || contract.releases[resource] != allocated ||
                contract.live[resource] != NULL) return __LINE__;
        }
    }
    return 0;
}

int dnssdAllocationFailureStatus(void) {
    const char name[] = "DNS allocation contract";
    const char address[] = {2, 4, 6, 8, 10, 12};
    int error = -1;
    int status = checkAllocationFailures(name, sizeof(name) - 1, address, sizeof(address), &error);
    if (status) return status;
    status = checkAllocationFailures(name, sizeof(name) - 1, address, sizeof(address), NULL);
    if (status) return status;

    error = -1;
    dnssd_t *restored = dnssd_init(name, sizeof(name) - 1, address, sizeof(address), &error, 0);
    if (!restored) return __LINE__;
    int length = 0;
    const char *storedName = dnssd_get_name(restored, &length);
    const bool nameMatches = length == sizeof(name) - 1 && strcmp(storedName, name) == 0;
    const char *storedAddress = dnssd_get_hw_addr(restored, &length);
    const bool addressMatches = length == sizeof(address) && memcmp(storedAddress, address, sizeof(address)) == 0;
    dnssd_destroy(restored);
    return error == DNSSD_ERROR_NOERROR && nameMatches && addressMatches ? 0 : __LINE__;
}
