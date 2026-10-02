import time
import os

def main():
    total_size = 1024 * 1024 * 1024 # 1GB
    buffer_size = 64 * 1024 # 64KB
    buffer = b'\0' * buffer_size
    filename = "io_test_python.bin"

    start = time.time()

    # Write
    with open(filename, "wb") as f:
        for _ in range(0, total_size, buffer_size):
            f.write(buffer)

    # Read
    total_read = 0
    with open(filename, "rb") as f:
        while True:
            chunk = f.read(buffer_size)
            if not chunk:
                break
            total_read += len(chunk)

    end = time.time()
    print("PYTHON IO Total: {} bytes, Time: {:.1f} ms".format(total_read, (end - start) * 1000))

    if os.path.exists(filename):
        os.remove(filename)

if __name__ == "__main__":
    main()
