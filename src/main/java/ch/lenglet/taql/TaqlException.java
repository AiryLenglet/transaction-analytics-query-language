package ch.lenglet.taql;

import java.util.List;

/** Thrown for anything the user could have written differently. Never for internal faults. */
public class TaqlException extends RuntimeException {

    private final List<Diagnostic> diagnostics;

    public TaqlException(List<Diagnostic> diagnostics) {
        super(diagnostics.stream().map(Diagnostic::toString).reduce((a, b) -> a + "\n" + b).orElse("query error"));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public TaqlException(Diagnostic diagnostic) {
        this(List.of(diagnostic));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
