#include <iostream>
#include <vector>
#include <chrono>

struct Node {
    int key;
    int value;
    Node *next;
};

class ManualHashMap {
    std::vector<Node*> buckets;
    int capacity;

public:
    ManualHashMap(int cap) : capacity(cap), buckets(cap, nullptr) {}

    void put(int key, int value) {
        int h = key % capacity;
        if (h < 0) h = -h;
        Node *curr = buckets[h];
        while (curr) {
            if (curr->key == key) {
                curr->value = value;
                return;
            }
            curr = curr->next;
        }
        Node *newNode = new Node{key, value, buckets[h]};
        buckets[h] = newNode;
    }

    int get(int key) {
        int h = key % capacity;
        if (h < 0) h = -h;
        Node *curr = buckets[h];
        while (curr) {
            if (curr->key == key) return curr->value;
            curr = curr->next;
        }
        return -1;
    }

    ~ManualHashMap() {
        for (int i = 0; i < capacity; ++i) {
            Node *curr = buckets[i];
            while (curr) {
                Node *temp = curr;
                curr = curr->next;
                delete temp;
            }
        }
    }
};

int main() {
    int size = 5000000;
    auto start = std::chrono::high_resolution_clock::now();

    ManualHashMap map(size);
    for (int i = 0; i < size; i++) {
        map.put(i, i * 2);
    }

    long long sum = 0;
    for (int i = 0; i < size; i++) {
        sum += map.get(i);
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP HashMap Sum: " << sum << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
