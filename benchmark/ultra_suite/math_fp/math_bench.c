#include <stdio.h>
#include <math.h>
#include <time.h>

int main() {
    int limit = 100000000;
    clock_t start = clock();

    double res = 0.0;
    for (int i = 0; i < limit; i++) {
        res += sin(i) * cos(i) + sqrt(i);
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Math Res: %.2f, Time: %.1f ms\n", res, ms);

    return 0;
}
