package ocean.compiler;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
/**
 * Paralel derleme oturumları (CompilationSession) arasında iş parçacığı güvenliği sağlayan
 * ve geçerli iş parçacığının oturum kümesine delege eden dinamik Set uygulaması.
 */
public class ThreadLocalDelegatingSet<E> implements Set<E> {
    private final Function<CompilationSession, Set<E>> setSelector;

    public ThreadLocalDelegatingSet(Function<CompilationSession, Set<E>> setSelector) {
        this.setSelector = setSelector;
    }

    private Set<E> getDelegate() {
        CompilationSession session = CompilationSession.getActiveSession();
        return setSelector.apply(Objects.requireNonNullElseGet(session, CompilationSession::getFallbackSession));
    }

    @Override public int size() { return getDelegate().size(); }
    @Override public boolean isEmpty() { return getDelegate().isEmpty(); }
    @Override public boolean contains(Object o) { return getDelegate().contains(o); }
    @NotNull
    @Override public Iterator<E> iterator() { return getDelegate().iterator(); }
    @NotNull
    @Override public Object[] toArray() { return getDelegate().toArray(); }
    @NotNull
    @Override public <T> T[] toArray(@NotNull T[] a) { return getDelegate().toArray(a); }
    @Override public boolean add(E e) { return getDelegate().add(e); }
    @Override public boolean remove(Object o) { return getDelegate().remove(o); }
    @Override public boolean containsAll(@NotNull Collection<?> c) { return getDelegate().containsAll(c); }
    @Override public boolean addAll(@NotNull Collection<? extends E> c) { return getDelegate().addAll(c); }
    @Override public boolean retainAll(@NotNull Collection<?> c) { return getDelegate().retainAll(c); }
    @Override public boolean removeAll(@NotNull Collection<?> c) { return getDelegate().removeAll(c); }
    @Override public void clear() { getDelegate().clear(); }
    @Override public boolean equals(Object o) { return getDelegate().equals(o); }
    @Override public int hashCode() { return getDelegate().hashCode(); }
    @Override public String toString() { return getDelegate().toString(); }

    @Override public boolean removeIf(@NotNull Predicate<? super E> filter) { return getDelegate().removeIf(filter); }
    @Override public void forEach(Consumer<? super E> action) { getDelegate().forEach(action); }
    @NotNull
    @Override public Spliterator<E> spliterator() { return getDelegate().spliterator(); }
}
