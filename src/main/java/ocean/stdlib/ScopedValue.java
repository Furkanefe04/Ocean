package ocean.stdlib;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Modern Scoped Values for Ocean standard library.
 * Provides lightweight, immutable, thread-safe, and bounded context sharing,
 * with seamless propagation to Virtual Threads and Task APIs.
 */
public final class ScopedValue<T> {

    public static <T> ScopedValue<T> newInstance() {
        return new ScopedValue<>();
    }

    private ScopedValue() {}

    public T get() {
        ScopeSnapshot current = ScopeSnapshot.getCurrent();
        if (current != null && current.bindings.containsKey(this)) {
            @SuppressWarnings("unchecked")
            T val = (T) current.bindings.get(this);
            return val;
        }
        throw new NoSuchElementException("ScopedValue is not bound in the current scope");
    }

    public boolean isBound() {
        ScopeSnapshot current = ScopeSnapshot.getCurrent();
        return current != null && current.bindings.containsKey(this);
    }

    public T orElse(T other) {
        ScopeSnapshot current = ScopeSnapshot.getCurrent();
        if (current != null && current.bindings.containsKey(this)) {
            @SuppressWarnings("unchecked")
            T val = (T) current.bindings.get(this);
            return val;
        }
        return other;
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if (isBound()) {
            return get();
        }
        throw exceptionSupplier.get();
    }

    public static <T> Carrier where(ScopedValue<T> key, T value) {
        return new Carrier().where(key, value);
    }

    public static <T> void runWhere(ScopedValue<T> key, T value, Runnable op) {
        where(key, value).run(op);
    }

    public static <T, R> R callWhere(ScopedValue<T> key, T value, Callable<R> op) throws Exception {
        return where(key, value).call(op);
    }

    public static final class Carrier {
        private final Map<ScopedValue<?>, Object> map = new HashMap<>();

        Carrier() {}

        public <T> Carrier where(ScopedValue<T> key, T value) {
            map.put(key, value);
            return this;
        }

        public void run(Runnable op) {
            ScopeSnapshot prev = ScopeSnapshot.getCurrent();
            setCurrent(prev);
            try {
                op.run();
            } finally {
                ScopeSnapshot.setCurrent(prev);
            }
        }

        public <R> R call(Callable<R> op) throws Exception {
            ScopeSnapshot prev = ScopeSnapshot.getCurrent();
            setCurrent(prev);
            try {
                return op.call();
            } finally {
                ScopeSnapshot.setCurrent(prev);
            }
        }

        private void setCurrent(ScopeSnapshot prev) {
            Map<ScopedValue<?>, Object> newBindings = (prev != null)
                    ? new HashMap<>(prev.bindings)
                    : new HashMap<>();
            newBindings.putAll(this.map);
            ScopeSnapshot next = new ScopeSnapshot(newBindings);
            ScopeSnapshot.setCurrent(next);
        }
    }

    public static final class ScopeSnapshot {
        private static final ThreadLocal<ScopeSnapshot> CURRENT_SCOPE = new ThreadLocal<>();

        final Map<ScopedValue<?>, Object> bindings;

        ScopeSnapshot(Map<ScopedValue<?>, Object> bindings) {
            this.bindings = Collections.unmodifiableMap(bindings);
        }

        public static ScopeSnapshot getCurrent() {
            return CURRENT_SCOPE.get();
        }

        public static void setCurrent(ScopeSnapshot snapshot) {
            if (snapshot == null) {
                CURRENT_SCOPE.remove();
            } else {
                CURRENT_SCOPE.set(snapshot);
            }
        }
    }
}
