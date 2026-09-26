package dev.comfyfluffy.caustica.nvidia.ngx;

/**
 * Init-attempt state for the shared NGX runtime. Not thread-safe; every access is guarded by
 * {@link NgxRuntime}'s monitor.
 *
 * <p>A failed attempt latches so init is not retried every frame. Each new world activation allows
 * exactly one retry of a failed init; a successful init is never undone by a later failure.
 */
final class NgxInitLatch {
    private enum State { UNATTEMPTED, READY, FAILED }

    private State state = State.UNATTEMPTED;
    private NgxRuntime.Initialized ready;

    /** The successful initialization, or {@code null} while unattempted or failed. */
    NgxRuntime.Initialized ready() {
        return state == State.READY ? ready : null;
    }

    boolean failed() {
        return state == State.FAILED;
    }

    void markReady(NgxRuntime.Initialized initialization) {
        state = State.READY;
        ready = initialization;
    }

    /** Latches a failed attempt unless an earlier attempt already succeeded. */
    void markFailed() {
        if (state != State.READY) {
            state = State.FAILED;
        }
    }

    /** A new world activation may retry a failed init; a ready init is kept. */
    void beginActivation() {
        if (state == State.FAILED) {
            state = State.UNATTEMPTED;
        }
    }

    void clear() {
        state = State.UNATTEMPTED;
        ready = null;
    }
}
