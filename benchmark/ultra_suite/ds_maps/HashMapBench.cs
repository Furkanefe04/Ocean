using System;
using System.Diagnostics;

class Node {
    public int key;
    public int value;
    public Node next;
}

class ManualHashMap {
    private Node[] buckets;
    private int capacity;

    public ManualHashMap(int cap) {
        this.capacity = cap;
        this.buckets = new Node[cap];
    }

    public void Put(int key, int value) {
        int h = Math.Abs(key % capacity);
        Node curr = buckets[h];
        while (curr != null) {
            if (curr.key == key) {
                curr.value = value;
                return;
            }
            curr = curr.next;
        }
        Node newNode = new Node { key = key, value = value, next = buckets[h] };
        buckets[h] = newNode;
    }

    public int Get(int key) {
        int h = Math.Abs(key % capacity);
        Node curr = buckets[h];
        while (curr != null) {
            if (curr.key == key) return curr.value;
            curr = curr.next;
        }
        return -1;
    }
}

class HashMapBench {
    static void Main() {
        int size = 5000000;
        Stopwatch sw = Stopwatch.StartNew();

        ManualHashMap map = new ManualHashMap(size);
        for (int i = 0; i < size; i++) {
            map.Put(i, i * 2);
        }

        long sum = 0;
        for (int i = 0; i < size; i++) {
            sum += map.Get(i);
        }

        sw.Stop();
        Console.WriteLine("CSHARP HashMap Sum: " + sum + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
