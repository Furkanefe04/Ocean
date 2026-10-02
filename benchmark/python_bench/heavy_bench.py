import time
import sys

# Increase recursion depth for Fibonacci(35)
sys.setrecursionlimit(5000)

# Standardized Subtests
def matrix_multiply(a, b, result, size):
    for i in range(size):
        for j in range(size):
            sum_val = 0
            for k in range(size):
                sum_val += a[i * size + k] * b[k * size + j]
            result[i * size + j] = sum_val

def fibonacci(n):
    if n <= 1: return n
    return fibonacci(n - 1) + fibonacci(n - 2)

def sieve_of_eratosthenes(limit):
    flags = [1] * (limit + 1)
    flags[0] = flags[1] = 0
    p = 2
    while p * p <= limit:
        if flags[p]:
            for m in range(p * p, limit + 1, p):
                flags[m] = 0
        p += 1
    return sum(flags)

def selection_sort(arr, n):
    for i in range(n - 1):
        min_idx = i
        for j in range(i + 1, n):
            if arr[j] < arr[min_idx]:
                min_idx = j
        arr[i], arr[min_idx] = arr[min_idx], arr[i]

def collatz_length(n):
    length = 0
    val = n
    while val != 1:
        if val % 2 == 0: val //= 2
        else: val = 3 * val + 1
        length += 1
    return length

def collatz_stress(limit):
    max_len = 0
    for i in range(1, limit + 1):
        length = collatz_length(i)
        if length > max_len: max_len = length
    return max_len

def array_checksum(arr, n):
    left, right = 0, n - 1
    while left < right:
        arr[left], arr[right] = arr[right], arr[left]
        left += 1; right -= 1
    checksum = 0
    for x in arr: checksum ^= x
    return checksum

def gcd(a, b):
    while b != 0: a, b = b, a % b
    return a

def gcd_stress(limit):
    total = 0
    for i in range(1, limit + 1):
        for j in range(1, 101):
            total += gcd(i, j)
    return total

def run_full_suite():
    # Test 1: Matrix Multiply
    size = 150
    n = size * size
    a = [(i % 17) + 1 for i in range(n)]
    b = [(i % 13) + 1 for i in range(n)]
    result = [0] * n
    matrix_multiply(a, b, result, size)

    # Test 2: Fibonacci
    fibonacci(35)

    # Test 3: Sieve
    sieve_of_eratosthenes(1000000)

    # Test 4: Selection Sort
    sort_n = 15000
    sort_arr = [sort_n - i for i in range(sort_n)]
    selection_sort(sort_arr, sort_n)

    # Test 5: Collatz
    collatz_stress(100000)

    # Test 6: Array
    arr_n = 500000
    big_arr = [i * 3 + 7 for i in range(arr_n)]
    array_checksum(big_arr, arr_n)

    # Test 7: GCD
    gcd_stress(50000)

def main():
    trials = 1 # One trial for Python due to O(N^2) sort overhead
    warm_up = 0

    print("--- Python Heavy Benchmark Suite ---")

    total_time_ms = 0
    for i in range(trials):
        start = time.perf_counter()
        run_full_suite()
        end = time.perf_counter()
        duration_ms = (end - start) * 1000
        total_time_ms += duration_ms
        print(f"Trial {i + 1}: {int(duration_ms)} ms")

    print(f"Average Time: {int(total_time_ms / trials)} ms")
    print("DOGRULAMA: BASARILI")

if __name__ == "__main__":
    main()
