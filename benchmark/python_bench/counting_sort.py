import time, os, tracemalloc

def counting_sort(arr, n, max_val):
    count = [0] * (max_val + 1)
    output = [0] * n
    for i in range(n): count[arr[i]] += 1
    for i in range(1, max_val + 1): count[i] += count[i - 1]
    for j in range(n - 1, -1, -1):
        v = arr[j]; count[v] -= 1; output[count[v]] = v
    for i in range(n): arr[i] = output[i]

size = 1000000
max_val = 10000
arr = [((i * 7 + 13) * 31) % (max_val + 1) for i in range(size)]

tracemalloc.start()
t1 = time.time()
counting_sort(arr, size, max_val)
t2 = time.time()
current, peak = tracemalloc.get_traced_memory()
tracemalloc.stop()

sorted_ok = all(arr[i] <= arr[i+1] for i in range(size - 1))
chk = 0
for v in arr: chk ^= v

print(f"Python CountingSort | Size={size} | Sure={int((t2-t1)*1000)}ms | Sorted={sorted_ok} | Checksum={chk} | Mem={peak//1024}KB")
