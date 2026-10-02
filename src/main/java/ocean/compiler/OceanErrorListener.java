package ocean.compiler;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
/**
 * ANTLR4 sözdizimsel hata dinleyicisi.
 * Kaynak kod ayrıştırma (parsing) sırasındaki sözdizim hatalarını yakalayarak CompilerReporter'a iletir.
 */
public class OceanErrorListener extends BaseErrorListener {
    private final String fileName;

    public OceanErrorListener(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        String safeFile = (fileName != null && !fileName.isEmpty()) ? fileName : "<unknown>";
        String safeMsg = (msg != null && !msg.isEmpty()) ? msg : "Unknown syntax error";
        CompilerReporter.error(safeFile, line, charPositionInLine, "Syntax Error: " + safeMsg, "Parser");
    }
}
