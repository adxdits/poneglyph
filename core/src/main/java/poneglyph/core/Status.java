package poneglyph.core;

/** Traffic-light verdict for a refined function. */
public enum Status {
    /** Does not compile (or no code was produced). */
    RED,
    /** Compiles, but tests fail or could not be run. */
    YELLOW,
    /** Compiles and passes the generated tests. */
    GREEN
}
