package com.obsinity.service.core.config;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Global, lock-free in-memory snapshot of all config.
 * Readers are wait-free via a single AtomicReference swap.
 */
@Component
public class ConfigRegistry {
    private final AtomicReference<RegistrySnapshot> ref = new AtomicReference<>(RegistrySnapshot.empty());

    /** Returns the current immutable snapshot. */
    public RegistrySnapshot current() {
        return ref.get();
    }

    /** Atomically replaces the current snapshot. Package-private on purpose. */
    void swap(RegistrySnapshot next) {
        ref.set(next);
    }
}
