package ocean.compiler;

/**
 * Ocean Language - Legacy Runner Bridge
 * This class delegates all calls to OceanRunnerV3 to maintain backward compatibility
 * while using the new IR-driven architecture.
 */
public class OceanRunner {
    public static void main(String[] args) {
        OceanRunnerV3.main(args);
    }
}
