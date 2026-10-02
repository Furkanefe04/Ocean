#include <iostream>
#include <string>
#include <chrono>

struct JsonData {
    int id;
    std::string name;
    double value;
};

void parseJson(const std::string& json, JsonData& data) {
    size_t idPos = json.find("\"id\":");
    if (idPos != std::string::npos) {
        data.id = std::stoi(json.substr(idPos + 5));
    }

    size_t namePos = json.find("\"name\":");
    if (namePos != std::string::npos) {
        size_t start = json.find('\"', namePos + 7);
        size_t end = json.find('\"', start + 1);
        data.name = json.substr(start + 1, end - start - 1);
    }

    size_t valPos = json.find("\"value\":");
    if (valPos != std::string::npos) {
        data.value = std::stod(json.substr(valPos + 8));
    }
}

int main() {
    std::string json = "{\"id\": 12345, \"name\": \"Ocean Language\", \"value\": 98.76}";
    int limit = 1000000;
    auto start = std::chrono::high_resolution_clock::now();

    double totalValue = 0;
    for (int i = 0; i < limit; i++) {
        JsonData data;
        parseJson(json, data);
        totalValue += data.value;
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP JSON Sum: " << totalValue << ", Time: " << ms << " ms" << std::endl;

    return 0;
}
