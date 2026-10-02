#include <stdio.h>
#include <stdlib.h>
#include <time.h>

struct Node { int a; int b; };

int main() {
    clock_t start = clock();
    long long sum = 0;
    for (int i = 1; i <= 50000000; i++) {
        struct Node* n = (struct Node*)malloc(sizeof(struct Node));
        n->a = i;
        n->b = i + 1;
        if (i == 49999999) { sum = n->a + n->b; }
        free(n);
    }
    clock_t end = clock();
    int dur = (int)((end - start) * 1000 / CLOCKS_PER_SEC);
    printf("==============================================\n");
    printf("  C OBJECT ALLOC (50M) BENCHMARK\n");
    printf("  Sure: %d ms\n", dur);
    printf("  Hesap: %lld\n", sum);
    printf("==============================================\n");
    return 0;
}
