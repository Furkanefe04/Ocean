import time

class Node:
    __slots__ = ['key', 'value', 'next']
    def __init__(self, key, value, next_node):
        self.key = key
        self.value = value
        self.next = next_node

class ManualHashMap:
    def __init__(self, capacity):
        self.capacity = capacity
        self.buckets = [None] * capacity

    def put(self, key, value):
        h = abs(key) % self.capacity
        curr = self.buckets[h]
        while curr:
            if curr.key == key:
                curr.value = value
                return
            curr = curr.next
        self.buckets[h] = Node(key, value, self.buckets[h])

    def get(self, key):
        h = abs(key) % self.capacity
        curr = self.buckets[h]
        while curr:
            if curr.key == key:
                return curr.value
            curr = curr.next
        return -1

def main():
    size = 5000000
    start = time.time()
    
    m_map = ManualHashMap(size)
    for i in range(size):
        m_map.put(i, i * 2)
        
    total_sum = 0
    for i in range(size):
        total_sum += m_map.get(i)
        
    end = time.time()
    print("PYTHON HashMap Sum: {}, Time: {:.1f} ms".format(total_sum, (end - start) * 1000))

if __name__ == "__main__":
    main()
