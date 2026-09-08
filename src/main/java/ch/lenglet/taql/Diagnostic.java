package ch.lenglet.taql;

/** A single compile error, carrying enough position info for a useful HTTP 400 body. */
public record Diagnostic(Phase phase, int line, int column, String message) {

    public enum Phase { SYNTAX, RESOLUTION, TYPE, LIMIT }

    @Override
    public String toString() {
        return "[" + phase.name().toLowerCase() + " " + line + ":" + column + "] " + message;
    }
}
