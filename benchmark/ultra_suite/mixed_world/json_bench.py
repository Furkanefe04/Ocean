import time

class JsonData:
    __slots__ = ['id', 'name', 'value']
    def __init__(self):
        self.id = 0
        self.name = ""
        self.value = 0.0

def parse_json(json_str, data):
    id_pos = json_str.find("\"id\":")
    if id_pos != -1:
        sub = json_str[id_pos + 5:].strip()
        comma = sub.find(",")
        data.id = int(sub[:comma])

    name_pos = json_str.find("\"name\":")
    if name_pos != -1:
        start = json_str.find("\"", name_pos + 7)
        end = json_str.find("\"", start + 1)
        data.name = json_str[start + 1:end]

    val_pos = json_str.find("\"value\":")
    if val_pos != -1:
        sub = json_str[val_pos + 8:].strip()
        brace = sub.find("}")
        data.value = float(sub[:brace])

def main():
    json_str = "{\"id\": 12345, \"name\": \"Ocean Language\", \"value\": 98.76}"
    limit = 1000000
    start = time.time()
    
    total_value = 0.0
    for _ in range(limit):
        data = JsonData()
        parse_json(json_str, data)
        total_value += data.value
        
    end = time.time()
    print("PYTHON JSON Sum: {}, Time: {:.1f} ms".format(total_value, (end - start) * 1000))

if __name__ == "__main__":
    main()
