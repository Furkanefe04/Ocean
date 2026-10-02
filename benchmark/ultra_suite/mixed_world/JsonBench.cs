using System;
using System.Diagnostics;

class JsonData {
    public int id;
    public string name;
    public double value;
}

class JsonBench {
    static void ParseJson(string json, JsonData data) {
        int idPos = json.IndexOf("\"id\":");
        if (idPos != -1) {
            string sub = json.Substring(idPos + 5).Trim();
            int comma = sub.indexOf(",");
            data.id = int.Parse(sub.Substring(0, comma));
        }

        int namePos = json.IndexOf("\"name\":");
        if (namePos != -1) {
            int start = json.IndexOf("\"", namePos + 7);
            int end = json.IndexOf("\"", start + 1);
            data.name = json.Substring(start + 1, end - start - 1);
        }

        int valPos = json.IndexOf("\"value\":");
        if (valPos != -1) {
            string sub = json.Substring(valPos + 8).Trim();
            int brace = sub.indexOf("}");
            data.value = double.Parse(sub.Substring(0, brace));
        }
    }

    static void Main() {
        string json = "{\"id\": 12345, \"name\": \"Ocean Language\", \"value\": 98.76}";
        int limit = 1000000;
        Stopwatch sw = Stopwatch.StartNew();

        double totalValue = 0;
        for (int i = 0; i < limit; i++) {
            JsonData data = new JsonData();
            ParseJson(json, data);
            totalValue += data.value;
        }

        sw.Stop();
        Console.WriteLine("CSHARP JSON Sum: " + totalValue + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
