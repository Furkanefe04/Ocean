package ocean.stdlib;

import java.util.NoSuchElementException;

/**
 * Functional Result monad (Ok or Err) for Ocean stdlib.
 */
public class OceanResult<T, E> {
    private final T value;
    private final E error;
    private final boolean isOk;

    private OceanResult(T value, E error, boolean isOk) {
        this.value = value;
        this.error = error;
        this.isOk = isOk;
    }

    public static <T, E> OceanResult<T, E> ok(T value) {
        return new OceanResult<>(value, null, true);
    }

    public static <T, E> OceanResult<T, E> err(E error) {
        return new OceanResult<>(null, error, false);
    }

    public boolean isOk() { return isOk; }
    public boolean isErr() { return !isOk; }

    public T getValue() {
        if (!isOk) throw new NoSuchElementException("Result is Err: " + error);
        return value;
    }

    public E getError() {
        if (isOk) throw new NoSuchElementException("Result is Ok: " + value);
        return error;
    }

    public T unwrapOr(T fallback) {
        return isOk ? value : fallback;
    }

    public <R> OceanResult<R, E> map(java.util.function.Function<? super T, ? extends R> mapper) {
        if (!isOk) return OceanResult.err(error);
        if (mapper == null) return OceanResult.err(error);
        return OceanResult.ok(mapper.apply(value));
    }

    public <F> OceanResult<T, F> mapError(java.util.function.Function<? super E, ? extends F> mapper) {
        if (isOk) return OceanResult.ok(value);
        if (mapper == null) return OceanResult.err(null);
        return OceanResult.err(mapper.apply(error));
    }

    public void ifOk(java.util.function.Consumer<? super T> action) {
        if (isOk && action != null) {
            action.accept(value);
        }
    }

    public void ifErr(java.util.function.Consumer<? super E> action) {
        if (!isOk && action != null) {
            action.accept(error);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OceanResult<?, ?> that = (OceanResult<?, ?>) o;
        return isOk == that.isOk &&
                java.util.Objects.equals(value, that.value) &&
                java.util.Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(value, error, isOk);
    }

    @Override
    public String toString() {
        return isOk ? "Ok(" + value + ")" : "Err(" + error + ")";
    }
}
