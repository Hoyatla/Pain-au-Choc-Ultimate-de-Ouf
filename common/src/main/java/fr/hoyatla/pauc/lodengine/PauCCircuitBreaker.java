package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic circuit breaker that disables a subsystem after too many exceptions in a time window.
 * Ported from Distant Horizons {@code BatchGenerationEnvironment} exception circuit breaker pattern (LGPL v3).
 *
 * <p>Usage: call {@link #recordSuccess()} or {@link #recordFailure()} after each operation.
 * Check {@link #isTripped()} before attempting the operation.</p>
 */
public final class PauCCircuitBreaker {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum State { CLOSED, OPEN }

    private final String name;
    private final int maxFailures;
    private final long windowMs;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private volatile State state = State.CLOSED;

    /**
     * @param name         human-readable name for logging (e.g., "RealSurfaceGen")
     * @param maxFailures  number of failures in the window to trip the breaker
     * @param windowMs     time window in milliseconds
     */
    public PauCCircuitBreaker(String name, int maxFailures, long windowMs) {
        this.name = name;
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
    }

    /** Default: 16 failures in 1 second. */
    public PauCCircuitBreaker(String name) {
        this(name, 16, 1000);
    }

    public boolean isTripped() { return state == State.OPEN; }
    public State getState() { return state; }

    public void recordSuccess() {
        // reset on success if not tripped
    }

    public void recordFailure() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > windowMs) {
            // new window
            if (windowStart.compareAndSet(start, now)) {
                failureCount.set(1);
            } else {
                failureCount.incrementAndGet();
            }
        } else {
            int count = failureCount.incrementAndGet();
            if (count >= maxFailures) {
                state = State.OPEN;
                LOGGER.warn("PauCCircuitBreaker [{}]: tripped after {} failures in {}ms — feature disabled", name, count, windowMs);
            }
        }
    }

    /** Manually reset the circuit breaker. */
    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        windowStart.set(System.currentTimeMillis());
        LOGGER.info("PauCCircuitBreaker [{}]: manually reset", name);
    }
}
