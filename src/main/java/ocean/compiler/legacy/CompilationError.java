package ocean.compiler.legacy;

import org.jetbrains.annotations.NotNull;
/**
 * Eski derleyici mimarisi için hata mesajı, kaynak konumu ve dosya bilgisini tutan kayıt (record).
 */
record CompilationError(String message,int line,int column,String fileName){
    @NotNull
    @Override
    public String toString() {
        return String.format("[Hata] %s (%d:%d) - %s", fileName, line, column, message);
    }
}