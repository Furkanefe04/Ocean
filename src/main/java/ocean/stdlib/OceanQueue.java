package ocean.stdlib;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * High-performance array-backed circular ring buffer FIFO queue for Ocean stdlib.
 */
public class OceanQueue<T> implements Iterable<T> {
    private Object[] elements;
    private int head;
    private int tail;
    private int size;

    public OceanQueue() {
        this(16);
    }

    public OceanQueue(int initialCapacity) {
        this.elements = new Object[Math.max(initialCapacity, 8)];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public boolean enqueue(T item) {
        ensureCapacity();
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        size++;
        return true;
    }

    @SuppressWarnings("unchecked")
    public T dequeue() {
        if (isEmpty()) throw new NoSuchElementException("Queue is empty");
        T item = (T) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        return item;
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        if (isEmpty()) throw new NoSuchElementException("Queue is empty");
        return (T) elements[head];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            elements[(head + i) % elements.length] = null;
        }
        head = 0;
        tail = 0;
        size = 0;
    }

    public OceanList<T> toList() {
        OceanList<T> list = new OceanList<>(size);
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked")
            T item = (T) elements[(head + i) % elements.length];
            list.add(item);
        }
        return list;
    }

    private void ensureCapacity() {
        if (size == elements.length) {
            int newCap = elements.length << 1;
            Object[] newArr = new Object[newCap];
            for (int i = 0; i < size; i++) {
                newArr[i] = elements[(head + i) % elements.length];
            }
            elements = newArr;
            head = 0;
            tail = size;
        }
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int count = 0;

            @Override
            public boolean hasNext() {
                return count < size;
            }

            @Override
            @SuppressWarnings("unchecked")
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                T item = (T) elements[(head + count) % elements.length];
                count++;
                return item;
            }
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OceanQueue[");
        for (int i = 0; i < size; i++) {
            sb.append(elements[(head + i) % elements.length]);
            if (i < size - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }
}
