package poneglyph.core.testutil;

import org.junit.jupiter.api.Assumptions;
import poneglyph.core.compile.Compiler;

import java.time.Duration;
import java.util.List;

/** Locates a usable C compiler for tests, or skips the test if there is none. */
public final class Gcc {

    private static volatile Boolean available;

    private Gcc() {
    }

    public static String path() {
        String p = System.getProperty("poneglyph.gcc");
        return p == null || p.isBlank() ? Compiler.DEFAULT_GCC : p;
    }

    public static Compiler compiler() {
        return new Compiler(path(), Duration.ofSeconds(60), List.of());
    }

    public static boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            a = compiler().isAvailable();
            available = a;
        }
        return a;
    }

    /** Call at the top of any test that needs to compile C. */
    public static void assumeAvailable() {
        Assumptions.assumeTrue(isAvailable(), "C compiler '" + path() + "' not available; skipping compile test");
    }
}
