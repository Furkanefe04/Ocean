package ocean.gradle;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.file.DirectoryProperty;

import java.io.File;

/**
 * Ocean derleyici konfigürasyon uzantısı.
 *
 * ocean {
 *     sourceDir = file('src/main/ocean')   // .ocean dosyalarının bulunduğu dizin
 *     outputDir = file('build/classes/ocean') // Derlenmiş sınıfların çıktı dizini
 *     entryPoint = 'Main'                   // Ana sınıf adı
 * }
 */
public class OceanExtension {

    private final DirectoryProperty sourceDir;
    private final DirectoryProperty outputDir;
    private final Property<String> entryPoint;

    public OceanExtension(Project project) {
        this.sourceDir = project.getObjects().directoryProperty();
        this.outputDir = project.getObjects().directoryProperty();
        this.entryPoint = project.getObjects().property(String.class);

        // Varsayılan değerler
        this.sourceDir.set(project.file("src/main/ocean"));
        this.outputDir.set(project.getLayout().getBuildDirectory().dir("classes/ocean").get());
        this.entryPoint.set("Main");
    }

    public DirectoryProperty getSourceDir() {
        return sourceDir;
    }

    public DirectoryProperty getOutputDir() {
        return outputDir;
    }

    public Property<String> getEntryPoint() {
        return entryPoint;
    }
}
