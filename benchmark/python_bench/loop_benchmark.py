import time
def run():
    start = time.time()
    sum_val = 0
    # 100M Iterations
    for i in range(1, 100000001):
        sum_val += 1
    end = time.time()
    print(f"Sonuc: {sum_val}")
    print(f"Python Loop Suresi: {int((end - start) * 1000)}ms")
if __name__ == "__main__":
    run()
