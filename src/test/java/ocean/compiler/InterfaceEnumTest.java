package ocean.compiler;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

public class InterfaceEnumTest {

    @Test
    public void testInterfaceAndEnum() throws Exception {
        String code =
                """
                        package test;
                        interface Logger {
                            function log(String msg);
                        }
                        
                        enum Status {
                            SUCCESS, FAILURE, PENDING
                        }
                        
                        class Main implements Logger {
                            function log(String msg) {
                                OceanOutput(msg);
                            }
                           \s
                            function test() {
                                variable s = Status.SUCCESS;
                                if (s == Status.SUCCESS) {
                                    log("Success!");
                                }
                            }
                        }""";
        
        File tempFile = File.createTempFile("Main", ".ocean");
        Files.writeString(tempFile.toPath(), code);
        
        try {
            OceanRunnerV3 runner = new OceanRunnerV3();
            runner.compile(tempFile.getAbsolutePath());
            
            // If it compiles without error, bytecode emission at least structurally worked.
            // We can also check if files are generated.
        } finally {
            tempFile.delete();
        }
    }
}
