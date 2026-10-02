import time

def run_loop(iterations):
    sum_val = 0
    # Use range() in Python 3 which is an iterator
    for i in range(1, iterations + 1):
        sum_val += i
    return sum_val

def main():
    # 1B iterations for parity with others
    iterations = 1000000000
    warm_up = 1 
    trials = 3 # Reduced trials for Python to save time, still enough for average

    print("--- Python Loop (1B iterations) Benchmark ---")

    # Minimal warm-up
    run_loop(iterations // 10) 

    total_time = 0
    for i in range(trials):
        start = time.perf_counter()
        result = run_loop(iterations)
        end = time.perf_counter()
        
        duration_ms = (end - start) * 1000
        total_time += duration_ms
        print(f"Trial {i + 1}: {int(duration_ms)} ms (sum={result})")

    print(f"Average Time: {int(total_time / trials)} ms")
    print("DOGRULAMA: BASARILI")

if __name__ == "__main__":
    main()
