package ch.lenglet.taql.ast;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.grammar.TaqlLexer;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Text -> untyped syntax model, collecting every syntax error rather than
 * failing on the first.
 *
 * ANTLR generates a parser of the same name from the grammar, so the generated
 * one is spelled out in full below. This is the one callers want: it holds the
 * limits, runs both stages, and hands back an {@link Ast.Query}.
 *
 * <h2>Two-stage parsing</h2>
 * The {@code expression} rule is left-recursive, and ANTLR's full LL(*)
 * prediction walks it quadratically: {@code 1+1+1+...} with 3200 terms took 43
 * seconds, so a single request inside the published 16 KB size limit could burn
 * minutes of CPU before touching the database. SLL prediction parses the same
 * input in 45 ms.
 *
 * SLL can report a syntax error on input that full LL would accept, so this is
 * the standard two-stage strategy: try SLL and bail on the first error, then --
 * only if that failed -- re-parse with LL and the default error strategy, which
 * is what produces the collected, positioned diagnostics. Valid queries never
 * pay for the second stage, and invalid ones are small by the size limit.
 *
 * <h2>Why there are limits at all</h2>
 * Prediction cost is bounded by SLL, but stack depth is not: every pass after
 * the parser walks the tree recursively too, so {@code ---...---1} overflowed
 * the stack in {@link AstBuilder} even once the parse itself was cheap.
 * {@link Limits#maxNestingDepth} is the bound that fixes that, enforced while
 * the tree is built so nothing deeper ever reaches the resolver or the
 * generator; {@link Limits#maxSourceLength} bounds the work before that, and
 * the {@link StackOverflowError} catch covers a thread with a smaller stack
 * than these were measured against.
 */
public final class TaqlParser {

    private final Limits limits;

    /** With the default limits; see {@link Limits}. */
    public TaqlParser() {
        this(Limits.DEFAULTS);
    }

    public TaqlParser(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param maxSourceLength  characters accepted in one query. 8 KB is far
     *                         more than a hand-written analytical query needs.
     *                         Keep {@code openapi.yaml}'s {@code maxLength} in
     *                         step: a contract the server will not honour is
     *                         worse than either bound alone.
     * @param maxAliasLength   how long an output name may be. 128 is what SQL
     *                         Server allows an identifier, and an alias is the
     *                         one piece of caller text that reaches the
     *                         statement as an identifier rather than a
     *                         parameter. Without this the store rejects the
     *                         statement at run time -- error 103, classified
     *                         UNKNOWN, reported as a server fault -- so a
     *                         caller could turn their own mistake into a 500.
     * @param maxNestingDepth  how deep expressions and predicates may nest.
     *                         This is the bound that actually protects the
     *                         stack, and it protects every later pass too --
     *                         the resolver, the printer and the SQL generator
     *                         all walk the same tree recursively, so a tree
     *                         they cannot survive must not be built. 256 is
     *                         about an order of magnitude past anything a real
     *                         query nests, and an order of magnitude short of
     *                         where the walks start to fail.
     */
    public record Limits(int maxSourceLength, int maxAliasLength, int maxNestingDepth) {

        public static final Limits DEFAULTS = new Limits(8192, 128, 256);

        public Limits {
            if (maxSourceLength < 1) throw new IllegalArgumentException("maxSourceLength must be positive");
            if (maxAliasLength < 1) throw new IllegalArgumentException("maxAliasLength must be positive");
            if (maxNestingDepth < 1) throw new IllegalArgumentException("maxNestingDepth must be positive");
        }
    }

    public Ast.Query parse(String source) {
        if (source.length() > limits.maxSourceLength()) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.LIMIT, 1, 1,
                    "query is " + source.length() + " characters; the limit is " + limits.maxSourceLength()));
        }

        try {
            ch.lenglet.taql.grammar.TaqlParser.QueryContext tree = parseFast(source);
            if (tree == null) tree = parseWithDiagnostics(source);
            return AstBuilder.build(tree, limits);
        } catch (StackOverflowError overflow) {
            // maxNestingDepth is the real bound; this covers a thread given a
            // smaller stack than that was measured against. Parsing is a pure
            // function over objects allocated in this call, so there is no
            // half-updated state to leave behind -- unwinding turns a dead
            // request thread into a 400 the caller can act on.
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.LIMIT, 1, 1,
                    "query nests too deeply to parse"));
        }
    }

    /** SLL, bailing on the first error. Returns null when the input needs the LL stage. */
    private static ch.lenglet.taql.grammar.TaqlParser.QueryContext parseFast(String source) {
        ch.lenglet.taql.grammar.TaqlParser parser = parser(source, new CollectingListener(new ArrayList<>()));
        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        parser.setErrorHandler(new BailErrorStrategy());
        try {
            return parser.query();
        } catch (ParseCancellationException bail) {
            return null;
        }
    }

    /** Full LL, collecting every error so the caller gets all of them at once. */
    private static ch.lenglet.taql.grammar.TaqlParser.QueryContext parseWithDiagnostics(String source) {
        List<Diagnostic> errors = new ArrayList<>();
        ch.lenglet.taql.grammar.TaqlParser parser = parser(source, new CollectingListener(errors));
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        parser.setErrorHandler(new DefaultErrorStrategy());

        ch.lenglet.taql.grammar.TaqlParser.QueryContext tree = parser.query();
        if (!errors.isEmpty()) throw new TaqlException(errors);
        return tree;
    }

    private static ch.lenglet.taql.grammar.TaqlParser parser(String source, BaseErrorListener listener) {
        TaqlLexer lexer = new TaqlLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);

        ch.lenglet.taql.grammar.TaqlParser parser = new ch.lenglet.taql.grammar.TaqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        return parser;
    }

    private static final class CollectingListener extends BaseErrorListener {

        private final List<Diagnostic> errors;

        CollectingListener(List<Diagnostic> errors) {
            this.errors = errors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            errors.add(new Diagnostic(Diagnostic.Phase.SYNTAX, line, charPositionInLine + 1, msg));
        }
    }
}
