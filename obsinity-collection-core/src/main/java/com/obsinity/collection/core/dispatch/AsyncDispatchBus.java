package com.obsinity.collection.core.dispatch;

import com.obsinity.collection.core.receivers.TelemetryHandlerRegistry;
import com.obsinity.collection.core.receivers.TelemetryReceiver;
import com.obsinity.telemetry.model.TelemetryEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-receiver asynchronous dispatch bus using a dedicated single-thread worker and deque.
 */
public final class AsyncDispatchBus implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AsyncDispatchBus.class);

    private final TelemetryHandlerRegistry registry;
    private final Map<TelemetryReceiver, Worker> workers = new ConcurrentHashMap<>();

    public AsyncDispatchBus(TelemetryHandlerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void dispatch(TelemetryEvent holder) {
        if (holder == null) return;
        List<TelemetryReceiver> list = registry.handlers();
        for (TelemetryReceiver r : list) {
            workers.computeIfAbsent(r, Worker::new).offer(holder);
        }
    }

    @Override
    public void close() {
        workers.values().forEach(Worker::shutdown);
        workers.clear();
    }

    private static final class Worker implements Runnable {
        private final TelemetryReceiver receiver;
        private final LinkedBlockingDeque<TelemetryEvent> queue = new LinkedBlockingDeque<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        Worker(TelemetryReceiver receiver) {
            this.receiver = receiver;
            this.thread = new Thread(
                    this, "obsinity-telemetry-worker-" + receiver.getClass().getSimpleName());
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void offer(TelemetryEvent event) {
            queue.offer(event);
        }

        void shutdown() {
            running.set(false);
            thread.interrupt();
        }

        @Override
        public void run() {
            while (running.get()) {
                TelemetryEvent event = null;
                try {
                    event = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (event != null) receiver.handle(event);
                } catch (InterruptedException ie) {
                    // shutdown
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    Throwable root = unwrap(t);
                    log.warn(
                            "Telemetry receiver {} failed to handle event {} due to {}",
                            receiver.getClass().getName(),
                            describe(event),
                            root.getMessage(),
                            root);
                }
            }
        }

        private static String describe(TelemetryEvent event) {
            if (event == null) return "<none>";
            return event.getClass().getName();
        }

        private static Throwable unwrap(Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof InvocationTargetException || current instanceof UndeclaredThrowableException) {
                Throwable next = current instanceof InvocationTargetException
                        ? ((InvocationTargetException) current).getTargetException()
                        : ((UndeclaredThrowableException) current).getUndeclaredThrowable();
                if (next == null || next == current) break;
                current = next;
            }
            return current;
        }
    }
}
