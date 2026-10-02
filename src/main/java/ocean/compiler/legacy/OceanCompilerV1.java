/**
 * Ocean Language - Version 1.0 Compiler
 * Translates Ocean source to Java source and compiles using the system Java compiler.
 * This is the very first implementation of the language.
 */
package ocean.compiler.legacy;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Ocean dilinin ilk nesil kaynak kod dönüştürücü derleyicisi (Legacy V1 uygulaması).
 */
public class OceanCompilerV1 {
    private static final String inputPath = "examples/";
    private static final String outputPath = "src/main/java/ocean/";
    private static OceanCompilerV1 compiler;

    public static synchronized OceanCompilerV1 Instance() {
        if (compiler == null) {
            compiler = new OceanCompilerV1();
        }
        return compiler;
    }

    public void compile(String mainClassName) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        String mainFile = mainClassName + ".ocean";
        String code = new String(Files.readAllBytes(Path.of(inputPath + mainFile)));
        File javaFile = new File(outputPath + mainClassName + ".java");
        FileWriter fileWriter = new FileWriter(javaFile);
        fileWriter.write(OceanToJava(code, mainClassName));
        fileWriter.close();

        String[] javaFiles = new String[]{javaFile.getPath()};
        
        clearBinaryFile(Paths.get(outputPath));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null, javaFiles);
        if (result != 0) {
            System.out.println("Derleme sırasında hata oluştu!");
        } else {
            URLClassLoader classLoader = new URLClassLoader(new URL[]{(new File("src/main/java/")).toURI().toURL()}, ClassLoader.getSystemClassLoader().getParent());
            Class<?> cls = Class.forName("ocean." + mainClassName, true, classLoader);
            Method main = cls.getDeclaredMethod("main", String[].class);
            main.invoke(null, (Object) new String[]{});
            classLoader.close();
        }
        clearJavaFiles(Paths.get(outputPath),Paths.get(inputPath));
        clearBinaryFile(Paths.get(outputPath));

    }

    public String OceanToJava(String inputFile, String className) {
        String output = "package ocean;\n\n" + inputFile;
        output = this.replaceDSLKeyword(output, "variable", "var");
        output = this.replaceDSLKeyword(output, "value", "final var");
        output = this.replaceDSLKeyword(output, " bool ", " boolean ");
        output = this.replaceDSLKeyword(output, "class " + className, "public class " + className).replaceAll("main\\(\\)", "public static void main(String[] args)");
        output = this.replaceDSLKeyword(output, "function", "");
        output = this.replaceDSLKeyword(output, "trying", "try");
        output = this.replaceDSLKeyword(output, "from", "=");
        output = this.replaceDSLKeyword(output, "to", ";i <");
        output = this.replaceDSLKeyword(output, "with", "; i");
        output = this.replaceDSLKeyword(output, "decreasing", "-=");
        output = this.replaceDSLKeyword(output, "increasing", "+=");
        output = this.replaceDSLKeyword(output, "OceanInput", "Scanner");
        output = this.replaceDSLKeyword(output, "OceanOutput", "System.out.println");
        output = this.replaceDSLKeyword(output, "ocean.", "java.");
        output = this.replaceDSLKeyword(output, " in ", " : ");
        output = this.replaceDSLKeyword(output, " sync ", " volatile ");
        output = this.replaceDSLKeyword(output, " lock ", " synchronized ");
        output = this.replaceDSLKeyword(output, "skip", "continue");
        output = this.replaceDSLKeyword(output, "stop", "break");
        output = this.replaceDSLKeyword(output, "result", "yield");
        output = this.replaceDSLKeyword(output, "verify", "assert");
        return output;
    }

    public String replaceDSLKeyword(String code, String keyword, String replacement) {
        Pattern stringPattern = Pattern.compile("\"(\\\\.|[^\"])*\"");
        Matcher matcher = stringPattern.matcher(code);
        List<String> strings = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        for(int index = 0; matcher.find(); ++index) {
            strings.add(matcher.group());
            matcher.appendReplacement(sb, "__STRING" + index + "__");
        }

        matcher.appendTail(sb);
        String temp = sb.toString();
        temp = temp.replaceAll("\\b" + Pattern.quote(keyword) + "\\b", replacement);

        for(int i = 0; i < strings.size(); ++i) {
            temp = temp.replace("__STRING" + i + "__", strings.get(i));
        }

        return temp;
    }

    public void clearJavaFiles(Path outputPath,Path inputPath) throws IOException {
        ArrayList<String> javaFiles = new ArrayList<>();
        Files.walk(inputPath).filter(path -> path.toString().endsWith(".ocean")).forEach(path -> {
           File file = new File(path.toString());
           javaFiles.add(file.getName().replace(".ocean", ".java"));
        });
        Files.walk(outputPath).filter((path) -> path.toString().endsWith(".java")).forEach((path ) -> {
            File file = new File(path.toString());
            if (javaFiles.contains(file.getName())) {
                file.delete();
            }
        });
    }

    public void clearBinaryFile(Path outputPath) throws IOException {
        Files.walk(outputPath).filter((path) -> path.toString().endsWith(".class")).forEach((path) -> {
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new RuntimeException("Silemedi: " + path, e);
            }
        });
    }

    public ArrayList<String> listFilesForFolder(File folder) {
        ArrayList<String> files = new ArrayList<>();

        for(File fileEntry : Objects.requireNonNull(folder.listFiles())) {
            if (fileEntry.isDirectory()) {
                listFilesForFolder(fileEntry);
            } else {
                files.add(fileEntry.getName());
            }
        }
        return files;
    }
}
