package ocean.stdlib;

import java.util.Objects;

/**
 * Generic tuple Pair class for Ocean stdlib.
 */
public class OceanPair<K, V> {
    private K key;
    private V value;

    public OceanPair(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public static <K, V> OceanPair<K, V> of(K key, V value) {
        return new OceanPair<>(key, value);
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public void setKey(K key) {
        this.key = key;
    }

    public void setValue(V value) {
        this.value = value;
    }

    public OceanPair<V, K> swap() {
        return new OceanPair<>(value, key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OceanPair<?, ?> pair = (OceanPair<?, ?>) o;
        return Objects.equals(key, pair.key) && Objects.equals(value, pair.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }

    @Override
    public String toString() {
        return "(" + key + ", " + value + ")";
    }
}
