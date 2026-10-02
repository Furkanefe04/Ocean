
import java.math.RoundingMode;
import java.lang.reflect.Field;

public class TestReflection {
    public static void main(String[] args) {
        try {
            Class<?> cls = RoundingMode.class;
            Field f = cls.getField("HALF_UP");
            System.out.println("Type: " + f.getType().getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
