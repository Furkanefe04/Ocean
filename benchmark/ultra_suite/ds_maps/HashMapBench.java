class Node {
    int key;
    int value;
    Node next;
}

class ManualHashMap {
    Node[] buckets;
    int capacity;

    ManualHashMap(int cap) {
        this.capacity = cap;
        this.buckets = new Node[cap];
    }

    void put(int key, int value) {
        int h = Math.abs(key % capacity);
        Node curr = buckets[h];
        while (curr != null) {
            if (curr.key == key) {
                curr.value = value;
                return;
            }
            curr = curr.next;
        }
        Node newNode = new Node();
        newNode.key = key;
        newNode.value = value;
        newNode.next = buckets[h];
        buckets[h] = newNode;
    }

    int get(int key) {
        int h = Math.abs(key % capacity);
        Node curr = buckets[h];
        while (curr != null) {
            if (curr.key == key) return curr.value;
            curr = curr.next;
        }
        return -1;
    }
}

public class HashMapBench {
    public static void main(String[] args) {
        int size = 5000000;
        long start = System.currentTimeMillis();

        ManualHashMap map = new ManualHashMap(size);
        for (int i = 0; i < size; i++) {
            map.put(i, i * 2);
        }

        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += map.get(i);
        }

        long end = System.currentTimeMillis();
        System.out.println("Java HashMap Sum: " + sum + ", Time: " + (end - start) + " ms");
    }
}
