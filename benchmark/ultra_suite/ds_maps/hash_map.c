#include <stdio.h>
#include <stdlib.h>
#include <time.h>

typedef struct Node {
    int key;
    int value;
    struct Node *next;
} Node;

typedef struct {
    Node **buckets;
    int capacity;
} HashMap;

HashMap* createMap(int capacity) {
    HashMap *map = (HashMap *)malloc(sizeof(HashMap));
    map->capacity = capacity;
    map->buckets = (Node **)calloc(capacity, sizeof(Node *));
    return map;
}

void put(HashMap *map, int key, int value) {
    int h = key % map->capacity;
    if (h < 0) h = -h;
    Node *curr = map->buckets[h];
    while (curr) {
        if (curr->key == key) {
            curr->value = value;
            return;
        }
        curr = curr->next;
    }
    Node *newNode = (Node *)malloc(sizeof(Node));
    newNode->key = key;
    newNode->value = value;
    newNode->next = map->buckets[h];
    map->buckets[h] = newNode;
}

int get(HashMap *map, int key) {
    int h = key % map->capacity;
    if (h < 0) h = -h;
    Node *curr = map->buckets[h];
    while (curr) {
        if (curr->key == key) return curr->value;
        curr = curr->next;
    }
    return -1;
}

int main() {
    int size = 5000000;
    clock_t start = clock();

    HashMap *map = createMap(size);
    for (int i = 0; i < size; i++) {
        put(map, i, i * 2);
    }

    long long sum = 0;
    for (int i = 0; i < size; i++) {
        sum += get(map, i);
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C HashMap Sum: %lld, Time: %.1f ms\n", sum, ms);

    return 0;
}
