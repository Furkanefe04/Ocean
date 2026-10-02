public class JsonBench {
    static class JsonData {
        int id;
        String name;
        double value;
    }

    static void parseJson(String json, JsonData data) {
        int idPos = json.indexOf("\"id\":");
        if (idPos != -1) {
            String sub = json.substring(idPos + 5).trim();
            int comma = sub.indexOf(",");
            data.id = Integer.parseInt(sub.substring(0, comma));
        }

        int namePos = json.indexOf("\"name\":");
        if (namePos != -1) {
            int start = json.indexOf("\"", namePos + 7);
            int end = json.indexOf("\"", start + 1);
            data.name = json.substring(start + 1, end);
        }

        int valPos = json.indexOf("\"value\":");
        if (valPos != -1) {
            String sub = json.substring(valPos + 8).trim();
            int brace = sub.indexOf("}");
            data.value = Double.parseDouble(sub.substring(0, brace));
        }
    }

    public static void main(String[] args) {
        String json = "{\"id\": 12345, \"name\": \"Ocean Language\", \"value\": 98.76}";
        int limit = 1000000;
        long start = System.currentTimeMillis();

        double totalValue = 0;
        for (int i = 0; i < limit; i++) {
            JsonData data = new JsonData();
            parseJson(json, data);
            totalValue += data.value;
        }

        long end = System.currentTimeMillis();
        System.out.println("Java JSON Sum: " + totalValue + ", Time: " + (end - start) + " ms");
    }
}
