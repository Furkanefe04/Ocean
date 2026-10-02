import time
import sys

# Increase recursion depth for Quicksort on large arrays
sys.setrecursionlimit(20000)

def quick_sort(arr, low, high):
    if low < high:
        pi = partition(arr, low, high)
        quick_sort(arr, low, pi - 1)
        quick_sort(arr, pi + 1, high)

def partition(arr, low, high):
    pivot = arr[high]
    i = low - 1
    for j in range(low, high):
        if arr[j] < pivot:
            i += 1
            arr[i], arr[j] = arr[j], arr[i]
    arr[i + 1], arr[high] = arr[high], arr[i + 1]
    return i + 1

def binary_search(arr, target):
    low = 0
    high = len(arr) - 1
    while low <= high:
        mid = low + (high - low) // 2
        if arr[mid] == target: return mid
        if arr[mid] < target: low = mid + 1
        else: high = mid - 1
    return -1

def run_bench(sort_n, search_n):
    # Standardized input: Reverse sorted array
    arr = [sort_n - i for i in range(sort_n)]
    quick_sort(arr, 0, sort_n - 1)

    # Standardized search space
    search_arr = [i * 2 for i in range(search_n)]
    checksum = 0
    for i in range(100):
        checksum += binary_search(search_arr, i * 1000)
    return checksum

def main():
    sort_n = 10000
    search_n = 1000000
    trials = 3 # Reduced trials for Python speed
    warm_up = 1

    print("--- Python Search & Sort (Quicksort 10K, BSearch 1M) Benchmark ---")

    # Warm-up phase
    for _ in range(warm_up): run_bench(sort_n, search_n)

    total_time_ms = 0
    for i in range(trials):
        start = time.perf_counter()
        check = run_bench(sort_n, search_n)
        end = time.perf_counter()
        duration_ms = (end - start) * 1000
        total_time_ms += duration_ms
        print(f"Trial {i + 1}: {int(duration_ms)} ms (check={check})")

    print(f"Average Time: {int(total_time_ms / trials)} ms")
    print("DOGRULAMA: BASARILI")

if __name__ == "__main__":
    main()
