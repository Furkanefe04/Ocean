package ocean.compiler;

import org.antlr.v4.runtime.*;
import java.util.ArrayList;
import java.util.List;

import ocean.compiler.OceanLexer;
/**
 * Ocean kaynak kodları için belirteç (token) akışı oluşturan fabrika sınıfı.
 * ANTLR4 lexer çıktısındaki belirteçleri kanal ve boşluk filtrelerinden geçirerek ayrıştırıcıya hazırlar.
 */
public class OceanTokenStreamFactory {
    public static TokenStream createTokenStream(OceanLexer lexer) {
        List<? extends Token> rawTokens = lexer.getAllTokens();
        List<Token> filteredTokens = new ArrayList<>(rawTokens.size() + 16);

        for (Token t : rawTokens) {
            int type = t.getType();
            if (type == OceanLexer.RSHIFT || type == OceanLexer.URSHIFT) {
                CommonToken gt1 = new CommonToken(t);
                gt1.setType(OceanLexer.GT);
                gt1.setText(">");
                CommonToken gt2 = new CommonToken(t);
                gt2.setType(OceanLexer.GT);
                gt2.setText(">");
                filteredTokens.add(gt1);
                filteredTokens.add(gt2);
                if (type == OceanLexer.URSHIFT) {
                    CommonToken gt3 = new CommonToken(t);
                    gt3.setType(OceanLexer.GT);
                    gt3.setText(">");
                    filteredTokens.add(gt3);
                }
            } else {
                filteredTokens.add(t);
            }
        }

        return new CommonTokenStream(new ListTokenSource(filteredTokens));
    }
}
