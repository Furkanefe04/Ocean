package ocean.stdlib;

import java.util.NoSuchElementException;

/**
 * Functional null-safe Optional wrapper for Ocean stdlib.
 */
public class OceanOptional<T> {
    private static final OceanOptional<?> EMPTY = new OceanOptional<>(null);
    private final T value;

    private OceanOptional(T value) {
        this.value = value;
    }

    public static <T> OceanOptional<T> of(T value) {
        if (value == null) throw new NullPointerException("Value cannot be null in of()");
        return new OceanOptional<>(value);
    }

    public static <T> OceanOptional<T> ofNullable(T value) {
        return value == null ? empty() : new OceanOptional<>(value);
    }

    @SuppressWarnings("unchecked")
    public static <T> OceanOptional<T> empty() {
        return (OceanOptional<T>) EMPTY;
    }

    public boolean isPresent() {
        return value != null;
    }

    public boolean isEmpty() {
        return value == null;
    }

    public T get() {
        if (value == null) throw new NoSuchElementException("No value present in OceanOptional");
        return value;
    }

    public T orElse(T fallback) {
        return value != null ? value : fallback;
    }

    public T orElseGet(java.util.function.Supplier<? extends T> supplier) {
        return value != null ? value : (supplier != null ? supplier.get() : null);
    }

    public <R> OceanOptional<R> map(java.util.function.Function<? super T, ? extends R> mapper) {
        if (!isPresent() || mapper == null) return empty();
        return ofNullable(mapper.apply(value));
    }

    public OceanOptional<T> filter(java.util.function.Predicate<? super T> predicate) {
        if (!isPresent()) return this;
        if (predicate == null || predicate.test(value)) return this;
        return empty();
    }

    public void ifPresent(java.util.function.Consumer<? super T> action) {
        if (value != null && action != null) {
            action.accept(value);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OceanOptional<?> that = (OceanOptional<?>) o;
        return java.util.Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value != null ? "OceanOptional[" + value + "]" : "OceanOptional.empty";
    }
}
