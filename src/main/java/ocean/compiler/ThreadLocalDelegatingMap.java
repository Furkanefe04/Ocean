package ocean.compiler;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
/**
 * Paralel derleme oturumları (CompilationSession) arasında iş parçacığı güvenliği sağlayan
 * ve geçerli iş parçacığının oturum haritasına delege eden dinamik Map uygulaması.
 */
public class ThreadLocalDelegatingMap<K, V> implements Map<K, V> {
    private final Function<CompilationSession, Map<K, V>> mapSelector;

    public ThreadLocalDelegatingMap(Function<CompilationSession, Map<K, V>> mapSelector) {
        this.mapSelector = mapSelector;
    }

    private Map<K, V> getDelegate() {
        CompilationSession session = CompilationSession.getActiveSession();
        return mapSelector.apply(Objects.requireNonNullElseGet(session, CompilationSession::getFallbackSession));
    }

    @Override public int size() { return getDelegate().size(); }
    @Override public boolean isEmpty() { return getDelegate().isEmpty(); }
    @Override public boolean containsKey(Object key) { return getDelegate().containsKey(key); }
    @Override public boolean containsValue(Object value) { return getDelegate().containsValue(value); }
    @Override public V get(Object key) { return getDelegate().get(key); }
    @Override public V put(K key, V value) { return getDelegate().put(key, value); }
    @Override public V remove(Object key) { return getDelegate().remove(key); }
    @Override public void putAll(@NotNull Map<? extends K, ? extends V> m) { getDelegate().putAll(m); }
    @Override public void clear() { getDelegate().clear(); }
    @NotNull
    @Override public Set<K> keySet() { return getDelegate().keySet(); }
    @NotNull
    @Override public Collection<V> values() { return getDelegate().values(); }
    @NotNull
    @Override public Set<Entry<K, V>> entrySet() { return getDelegate().entrySet(); }
    @Override public boolean equals(Object o) { return getDelegate().equals(o); }
    @Override public int hashCode() { return getDelegate().hashCode(); }
    @Override public String toString() { return getDelegate().toString(); }

    @Override public V getOrDefault(Object key, V defaultValue) { return getDelegate().getOrDefault(key, defaultValue); }
    @Override public void forEach(java.util.function.BiConsumer<? super K, ? super V> action) { getDelegate().forEach(action); }
    @Override public void replaceAll(java.util.function.BiFunction<? super K, ? super V, ? extends V> function) { getDelegate().replaceAll(function); }
    @Override public V putIfAbsent(K key, V value) { return getDelegate().putIfAbsent(key, value); }
    @Override public boolean remove(Object key, Object value) { return getDelegate().remove(key, value); }
    @Override public boolean replace(K key, V oldValue, V newValue) { return getDelegate().replace(key, oldValue, newValue); }
    @Override public V replace(K key, V value) { return getDelegate().replace(key, value); }
    @Override public V computeIfAbsent(K key, @NotNull Function<? super K, ? extends V> mappingFunction) { return getDelegate().computeIfAbsent(key, mappingFunction); }
    @Override public V computeIfPresent(K key, @NotNull java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return getDelegate().computeIfPresent(key, remappingFunction); }
    @Override public V compute(K key, @NotNull java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return getDelegate().compute(key, remappingFunction); }
    @Override public V merge(K key, @NotNull V value, @NotNull java.util.function.BiFunction<? super V, ? super V, ? extends V> remappingFunction) { return getDelegate().merge(key, value, remappingFunction); }
}
