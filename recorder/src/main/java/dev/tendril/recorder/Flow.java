package dev.tendril.recorder;

import java.util.concurrent.atomic.AtomicLong;

/** Ties the outgoing calls made while serving a request to that request (same thread). */
final class Flow {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private Flow() {
    }

    static long begin() {
        long id = COUNTER.incrementAndGet();
        CURRENT.set(id);
        return id;
    }

    static void end() {
        CURRENT.remove();
    }

    /** The flow of the current thread, or 0 when there is none. */
    static long current() {
        Long id = CURRENT.get();
        return id == null ? 0 : id;
    }
}
