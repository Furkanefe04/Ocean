package ocean.stdlib;

import org.jetbrains.annotations.NotNull;

import java.util.EmptyStackException;
import java.util.Iterator;

/**
 * High-performance generic dynamic LIFO stack implementation for Ocean stdlib.
 */
public class OceanStack<T> implements Iterable<T> {
    private Object[] elements;
    private int size;

    public OceanStack() {
        this(10);
    }

    public OceanStack(int initialCapacity) {
        this.elements = new Object[Math.max(initialCapacity, 10)];
        this.size = 0;
    }

    public void push(T item) {
        ensureCapacity(size + 1);
        elements[size++] = item;
    }

    @SuppressWarnings("unchecked")
    public T pop() {
        if (isEmpty()) throw new EmptyStackException();
        T item = (T) elements[--size];
        elements[size] = null;
        return item;
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        if (isEmpty()) throw new EmptyStackException();
        return (T) elements[size - 1];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        for (int i = 0; i < size; i++) elements[i] = null;
        size = 0;
    }

    public OceanList<T> toList() {
        OceanList<T> list = new OceanList<>();
        for (int i = size - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            T item = (T) elements[i];
            list.add(item);
        }
        return list;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > elements.length) {
            int newCap = elements.length + (elements.length >> 1);
            if (newCap < minCapacity) newCap = minCapacity;
            Object[] newArr = new Object[newCap];
            System.arraycopy(elements, 0, newArr, 0, size);
            elements = newArr;
        }
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int index = size - 1;

            @Override
            public boolean hasNext() {
                return index >= 0;
            }

            @Override
            @SuppressWarnings("unchecked")
            public T next() {
                return (T) elements[index--];
            }
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OceanStack[");
        for (int i = size - 1; i >= 0; i--) {
            sb.append(elements[i]);
            if (i > 0) sb.append(", ");
        }
        return sb.append("]").toString();
    }
}
