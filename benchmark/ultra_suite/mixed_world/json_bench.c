#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    int id;
    char name[32];
    double value;
} JsonData;

void parseJson(const char *json, JsonData *data) {
    const char *idPtr = strstr(json, "\"id\":");
    if (idPtr) sscanf(idPtr + 5, "%d", &data->id);

    const char *namePtr = strstr(json, "\"name\":");
    if (namePtr) {
        const char *start = strchr(namePtr + 7, '\"');
        const char *end = strchr(start + 1, '\"');
        int len = end - start - 1;
        strncpy(data->name, start + 1, len);
        data->name[len] = '\0';
    }

    const char *valPtr = strstr(json, "\"value\":");
    if (valPtr) sscanf(valPtr + 8, "%lf", &data->value);
}

int main() {
    const char *json = "{\"id\": 12345, \"name\": \"Ocean Language\", \"value\": 98.76}";
    int limit = 1000000;
    clock_t start = clock();

    double totalValue = 0;
    for (int i = 0; i < limit; i++) {
        JsonData data;
        parseJson(json, &data);
        totalValue += data.value;
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C JSON Sum: %.2f, Time: %.1f ms\n", totalValue, ms);

    return 0;
}
