package ocean.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Ocean Gradle Plugin.
 * 
 * Kullanım:
 * <pre>
 * plugins {
 *     id 'ocean.compiler' version '1.0.0'
 * }
 * 
 * ocean {
 *     sourceDir = file('src/main/ocean')
 *     outputDir = file('build/classes/ocean')
 *     entryPoint = 'Main'
 * }
 * </pre>
 */
public class OceanPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Extension oluştur — kullanıcı konfigürasyonu
        OceanExtension extension = project.getExtensions().create("ocean", OceanExtension.class, project);

        // compileOcean task'ı kaydet
        project.getTasks().register("compileOcean", CompileOceanTask.class, task -> {
            task.setGroup("ocean");
            task.setDescription("Compiles Ocean source files (.ocean) into JVM bytecode.");

            // Extension'dan konfigürasyonu al
            task.getSourceDir().set(extension.getSourceDir());
            task.getOutputDir().set(extension.getOutputDir());
            task.getEntryPoint().set(extension.getEntryPoint());
        });

        // runOcean task'ı kaydet
        project.getTasks().register("runOcean", RunOceanTask.class, task -> {
            task.setGroup("ocean");
            task.setDescription("Compiles and runs an Ocean program.");
            task.dependsOn("compileOcean");

            task.getEntryPoint().set(extension.getEntryPoint());
            task.getOutputDir().set(extension.getOutputDir());
        });

        // Java plugin varsa, Ocean çıktısını classpath'e ekle
        project.getPluginManager().withPlugin("java", appliedPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.named("main", sourceSet -> {
                sourceSet.getOutput().dir(extension.getOutputDir());
            });
        });
    }
}
