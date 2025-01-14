package io.arex.agent.bootstrap;

import io.arex.agent.bootstrap.ctx.ArexThreadLocal;

import java.util.concurrent.atomic.AtomicLong;


public class TraceContextManager {
    private static final ArexThreadLocal<String> TRACE_CONTEXT = new ArexThreadLocal<>();
    private static IDGenerator idGenerator;

    public static void init(String ipAddress) {
        idGenerator = new IDGenerator(ipAddress, idGenerator == null ? 0L : idGenerator.counter.get());
    }

    public static String get() {
        return get(false);
    }

    /**
     * This method can only be called at the service entrance
     */
    public static String get(boolean createIfAbsent) {
        String messageId = TRACE_CONTEXT.get();
        if (messageId == null && createIfAbsent) {
            messageId = idGenerator.next();
            TRACE_CONTEXT.set(messageId);
        }
        return messageId;
    }

    public static void set(String traceId) {
        TRACE_CONTEXT.set(traceId);
    }

    public static String remove() {
        String messageId = TRACE_CONTEXT.get();
        TRACE_CONTEXT.remove();
        return messageId;
    }

    public static String generateId() {
        return idGenerator.next();
    }

    private static final class IDGenerator {
        private final String PREFIX;
        private final AtomicLong counter;

        public IDGenerator(String prefix, long initialCount) {
            PREFIX = "AREX-" + (prefix == null ? "" : prefix.replace(".", "-")) + "-";
            this.counter = new AtomicLong(initialCount);
        }

        public String next() {
            return PREFIX.concat(String.valueOf(getNowMillis())).concat(String.valueOf(counter.getAndIncrement()));
        }

        private long getNowMillis() {
            return System.nanoTime() / 1000000;
        }
    }
}
