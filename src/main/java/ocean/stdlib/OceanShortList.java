package ocean.stdlib;

import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * High-performance primitive short-backed specialized list eliminating Short autoboxing.
 */
public class OceanShortList extends OceanList<Short> {
    private static final long serialVersionUID = 1L;

    private short[] data;
    private int elementCount;

    public OceanShortList() {
        this(10);
    }

    public OceanShortList(int initialCapacity) {
        super(0);
        this.data = new short[Math.max(initialCapacity, 10)];
        this.elementCount = 0;
    }

    public boolean add(short element) {
        ensureCapacityInternal(elementCount + 1);
        data[elementCount++] = element;
        return true;
    }

    @Override
    public boolean add(Short element) {
        if (element != null) {
            return add(element.shortValue());
        }
        return false;
    }

    public short getShort(int index) {
        checkIndex(index);
        return data[index];
    }

    @Override
    public Short get(int index) {
        return getShort(index);
    }

    @Override
    public Short getFirst() {
        if (isEmpty()) throw new NoSuchElementException();
        return get(0);
    }

    @Override
    public Short getLast() {
        if (isEmpty()) throw new NoSuchElementException();
        return get(elementCount - 1);
    }

    public short set(int index, short element) {
        checkIndex(index);
        short old = data[index];
        data[index] = element;
        return old;
    }

    @Override
    public Short set(int index, Short element) {
        checkIndex(index);
        short old = data[index];
        if (element != null) {
            data[index] = element;
        }
        return old;
    }

    @Override
    public Short remove(int index) {
        checkIndex(index);
        short old = data[index];
        int numMoved = elementCount - index - 1;
        if (numMoved > 0) {
            System.arraycopy(data, index + 1, data, index, numMoved);
        }
        elementCount--;
        return old;
    }

    @Override
    public boolean remove(Object o) {
        int idx = indexOf(o);
        if (idx >= 0) {
            remove(idx);
            return true;
        }
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    @Override
    public int indexOf(Object o) {
        if (o instanceof Short) {
            short val = (Short) o;
            for (int i = 0; i < elementCount; i++) {
                if (data[i] == val) return i;
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        if (o instanceof Short) {
            short val = (Short) o;
            for (int i = elementCount - 1; i >= 0; i--) {
                if (data[i] == val) return i;
            }
        }
        return -1;
    }

    @Override
    public void shuffle() {
        Random rnd = new Random();
        for (int i = elementCount - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            short tmp = data[i];
            data[i] = data[j];
            data[j] = tmp;
        }
    }

    public void sort() {
        Arrays.sort(data, 0, elementCount);
    }

    @Override
    public void sort(Comparator<? super Short> c) {
        if (c == null) {
            sort();
            return;
        }
        Short[] boxed = new Short[elementCount];
        for (int i = 0; i < elementCount; i++) boxed[i] = data[i];
        Arrays.sort(boxed, c);
        for (int i = 0; i < elementCount; i++) data[i] = boxed[i];
    }

    @Override
    public boolean isSorted() {
        for (int i = 0; i < elementCount - 1; i++) {
            if (data[i] > data[i + 1]) return false;
        }
        return true;
    }

    @Override
    public int size() {
        return elementCount;
    }

    @Override
    public boolean isEmpty() {
        return elementCount == 0;
    }

    @Override
    public void clear() {
        elementCount = 0;
    }

    @NotNull
    @Override
    public Iterator<Short> iterator() {
        return new Iterator<>() {
            private int cursor = 0;

            public boolean hasNext() {
                return cursor < elementCount;
            }

            public Short next() {
                if (!hasNext()) throw new NoSuchElementException();
                return get(cursor++);
            }
        };
    }

    public OceanShortList slice(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > elementCount || fromIndex > toIndex) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + ", toIndex: " + toIndex + ", size: " + elementCount);
        }
        OceanShortList sub = new OceanShortList(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            sub.add(data[i]);
        }
        return sub;
    }

    @NotNull
    @Override
    public OceanList<Short> subList(int fromIndex, int toIndex) {
        return slice(fromIndex, toIndex);
    }

    public short[] toShortArray() {
        return Arrays.copyOf(data, elementCount);
    }

    @NotNull
    @Override
    public Object[] toArray() {
        Object[] a = new Object[elementCount];
        for (int i = 0; i < elementCount; i++) a[i] = data[i];
        return a;
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(@NotNull T[] a) {
        if (a.length < elementCount) {
            a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), elementCount);
        }
        for (int i = 0; i < elementCount; i++) {
            a[i] = (T) Short.valueOf(data[i]);
        }
        if (a.length > elementCount) {
            a[elementCount] = null;
        }
        return a;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof List<?> other)) return false;
        if (elementCount != other.size()) return false;
        if (other instanceof OceanShortList otherShortList) {
            for (int i = 0; i < elementCount; i++) {
                if (data[i] != otherShortList.data[i]) return false;
            }
            return true;
        }
        Iterator<?> it = other.iterator();
        for (int i = 0; i < elementCount; i++) {
            Object item = it.next();
            if (!(item instanceof Short && (Short) item == data[i])) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = 1;
        for (int i = 0; i < elementCount; i++) {
            hashCode = 31 * hashCode + Short.hashCode(data[i]);
        }
        return hashCode;
    }

    @Override
    public String toString() {
        if (elementCount == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elementCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append(data[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private void ensureCapacityInternal(int minCapacity) {
        if (minCapacity > data.length) {
            int newCapacity = data.length + (data.length >> 1);
            if (newCapacity < minCapacity) newCapacity = minCapacity;
            data = Arrays.copyOf(data, newCapacity);
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= elementCount) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + elementCount);
        }
    }
}
