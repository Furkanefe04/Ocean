package ocean.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * .ocean dosyalarını JVM bytecode'a derleyen Gradle task'ı.
 *
 * Kullanım:
 *   ./gradlew compileOcean
 */
public abstract class CompileOceanTask extends DefaultTask {

    @InputDirectory
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getEntryPoint();

    @TaskAction
    public void compile() {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        String entry = getEntryPoint().get();

        if (!srcDir.exists()) {
            getLogger().warn("Ocean source directory not found: {}", srcDir);
            return;
        }

        // .ocean dosyalarını topla
        List<File> oceanFiles = new ArrayList<>();
        collectOceanFiles(srcDir, oceanFiles);

        if (oceanFiles.isEmpty()) {
            getLogger().warn("No .ocean files found in {}", srcDir);
            return;
        }

        getLogger().lifecycle("Compiling {} Ocean source file(s) from {}", oceanFiles.size(), srcDir);

        // OceanRunner'ı çağır
        try {
            // Ana dosyayı bul
            File mainFile = oceanFiles.stream()
                    .filter(f -> f.getName().replace(".ocean", "").equals(entry))
                    .findFirst()
                    .orElse(oceanFiles.get(0));

            String[] args = new String[]{
                    entry,
                    mainFile.getAbsolutePath()
            };

            // Reflection ile OceanRunner'ı çağır (classpath bağımsızlığı için)
            Class<?> runnerClass = Class.forName("ocean.compiler.OceanRunner");
            Method mainMethod = runnerClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);

            getLogger().lifecycle("Ocean compilation completed successfully.");
        } catch (Exception e) {
            throw new TaskExecutionException(this, e);
        }
    }

    private void collectOceanFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectOceanFiles(f, result);
            } else if (f.getName().endsWith(".ocean")) {
                result.add(f);
            }
        }
    }
}
