package ocean.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;

/**
 * Ocean programını derleyip çalıştıran Gradle task'ı.
 *
 * Kullanım:
 *   ./gradlew runOcean
 */
public abstract class RunOceanTask extends DefaultTask {

    @Input
    public abstract Property<String> getEntryPoint();

    @InputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() {
        String entry = getEntryPoint().get();
        File outDir = getOutputDir().get().getAsFile();

        getLogger().lifecycle("Running Ocean program: {}", entry);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-cp", outDir.getAbsolutePath(),
                    "ocean.compiler.generated." + entry + "." + entry
            );
            pb.inheritIO();
            pb.directory(outDir);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Ocean program exited with code " + exitCode);
            }
        } catch (Exception e) {
            throw new TaskExecutionException(this, e);
        }
    }
}
