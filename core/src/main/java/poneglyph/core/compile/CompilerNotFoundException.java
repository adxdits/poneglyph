package poneglyph.core.compile;

/** The configured C compiler could not be executed. The message tells the user what to fix. */
public class CompilerNotFoundException extends Exception {

    private final String gccPath;

    public CompilerNotFoundException(String gccPath, String reason) {
        super("Could not run the C compiler '" + gccPath + "'"
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")")
                + ". Install gcc or clang, or point the 'gcc path' setting at an existing compiler.");
        this.gccPath = gccPath;
    }

    public String gccPath() {
        return gccPath;
    }
}
