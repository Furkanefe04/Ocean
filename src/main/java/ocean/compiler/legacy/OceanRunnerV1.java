package ocean.compiler.legacy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
/**
 * İlk nesil Ocean transpiler çalıştırma sınıfı (Legacy V1).
 */
public class OceanRunnerV1 {
    public static void main(String[] args) throws IOException, ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        OceanCompilerV1 compiler = OceanCompilerV1.Instance();
        if (args.length == 0) return;
        compiler.compile(args[0]);
    }
}
