package ocean.stdlib;

import java.util.HashMap;
import java.util.Map;

/**
 * Element frequency counter data structure for Ocean stdlib.
 */
public class OceanCounter<T> {
    private final Map<T, Integer> counts = new HashMap<>();

    public void add(T item) {
        add(item, 1);
    }

    public void add(T item, int count) {
        if (item != null && count > 0) {
            counts.put(item, counts.getOrDefault(item, 0) + count);
        }
    }

    public int getCount(T item) {
        return counts.getOrDefault(item, 0);
    }

    public void remove(T item) {
        counts.remove(item);
    }

    public int totalCount() {
        int sum = 0;
        for (int c : counts.values()) sum += c;
        return sum;
    }

    public T mostCommon() {
        T maxItem = null;
        int maxCount = -1;
        for (Map.Entry<T, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                maxItem = entry.getKey();
            }
        }
        return maxItem;
    }

    public void addAll(Iterable<T> items) {
        if (items != null) {
            for (T item : items) {
                add(item);
            }
        }
    }

    public boolean contains(T item) {
        return counts.containsKey(item);
    }

    public java.util.Set<T> elements() {
        return counts.keySet();
    }

    public Map<T, Integer> toMap() {
        return new HashMap<>(counts);
    }

    public void clear() {
        counts.clear();
    }

    @Override
    public String toString() {
        return counts.toString();
    }
}
