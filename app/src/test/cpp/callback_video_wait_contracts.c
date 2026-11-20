#include "android_raop_callbacks.h"
#include <errno.h>

typedef struct {
    android_callback_ctx_t *context;
    bool ready_after_wake;
    int result;
    int calls;
    bool invalid;
    struct timespec deadline;
} WaitContract;

// Active only in the standalone unit executable, after worker tests finish.
static WaitContract *active_wait;

int __real_pthread_cond_timedwait(pthread_cond_t *, pthread_mutex_t *, const struct timespec *);
int __wrap_pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex,
                                const struct timespec *deadline) {
    WaitContract *contract = active_wait;
    if (!contract) return __real_pthread_cond_timedwait(cond, mutex, deadline);

    android_callback_ctx_t *context = contract->context;
    int lock_state = pthread_mutex_trylock(&context->playback_info_lock);
    if (lock_state == 0) pthread_mutex_unlock(&context->playback_info_lock);
    contract->invalid |= cond != &context->play_ready_cond || mutex != &context->playback_info_lock ||
                         lock_state != EBUSY || context->play_ready || !deadline;
    if (contract->invalid) return ETIMEDOUT;
    if (++contract->calls == 1) {
        contract->deadline = *deadline;
        return 0; // Spurious wake without a ready player.
    }
    contract->invalid |= contract->deadline.tv_sec != deadline->tv_sec ||
                         contract->deadline.tv_nsec != deadline->tv_nsec;
    if (contract->calls > 2) return ETIMEDOUT; // Let the test report an unexpected retry.
    context->play_ready = contract->ready_after_wake;
    return contract->result;
}

int callbackVideoWaitStatus(void) {
    android_callback_ctx_t context = {0};
    if (pthread_mutex_init(&context.playback_info_lock, NULL) != 0) return __LINE__;
    if (pthread_cond_init(&context.play_ready_cond, NULL) != 0) {
        pthread_mutex_destroy(&context.playback_info_lock);
        return __LINE__;
    }
    raop_callbacks_t callbacks = {0};
    android_callbacks_fill(&callbacks, &context);
    const int results[] = {0, ETIMEDOUT, EINVAL};
    int status = 0;
    for (size_t result_index = 0; result_index < sizeof(results) / sizeof(results[0]) && !status; result_index++) {
        const int result = results[result_index];
        WaitContract contract = {.context = &context, .ready_after_wake = result == 0, .result = result};
        context.play_ready = false;
        active_wait = &contract;
        callbacks.on_video_wait_ready(&context);
        active_wait = NULL;
        if (contract.calls != 2 || contract.invalid || context.play_ready != (result == 0)) status = __LINE__;
        if (pthread_mutex_trylock(&context.playback_info_lock) != 0) status = __LINE__;
        else pthread_mutex_unlock(&context.playback_info_lock);
    }
    if (pthread_cond_destroy(&context.play_ready_cond) != 0) status = __LINE__;
    if (pthread_mutex_destroy(&context.playback_info_lock) != 0) status = __LINE__;
    return status;
}
