package ch.lenglet.taql.ast;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.grammar.TaqlLexer;
import ch.lenglet.taql.grammar.TaqlParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

/** Text -> untyped syntax model, collecting every syntax error rather than failing on the first. */
public final class TaqlParserFacade {

    private TaqlParserFacade() {}

    public static Ast.Query parse(String source) {
        List<Diagnostic> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                errors.add(new Diagnostic(Diagnostic.Phase.SYNTAX, line, charPositionInLine + 1, msg));
            }
        };

        TaqlLexer lexer = new TaqlLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);

        TaqlParser parser = new TaqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        TaqlParser.QueryContext tree = parser.query();
        if (!errors.isEmpty()) throw new TaqlException(errors);

        return AstBuilder.build(tree);
    }
}
