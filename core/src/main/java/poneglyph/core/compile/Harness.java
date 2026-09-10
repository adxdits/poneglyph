package poneglyph.core.compile;

import poneglyph.core.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the two source files that get compiled:
 *
 * <ul>
 *   <li>{@code function.c}: a prelude of standard headers followed by the refined code.</li>
 *   <li>{@code test.c}: the prelude, the test's own {@code #include}s, {@code #include "function.c"},
 *       a tiny non-aborting assertion harness, then the model's test code.</li>
 * </ul>
 *
 * The harness redefines {@code assert()} so that failing checks are recorded and printed instead of
 * aborting, which lets one run report every failing case. At exit it prints a line starting with
 * {@link #RESULT_MARKER} that the {@code TestRunner} parses.
 */
public final class Harness {

    public static final String FUNCTION_FILE = "function.c";
    public static final String TEST_FILE = "test.c";
    public static final String TEST_BINARY = "test_bin";
    public static final String RESULT_MARKER = "PONEGLYPH_RESULT:";
    public static final String FAIL_MARKER = "FAILED:";

    private static final Pattern SYSTEM_INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*<[^>]+>.*$");
    private static final Pattern LOCAL_INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*\"[^\"]+\".*$");

    static final String PRELUDE = String.join("\n",
            "/* Poneglyph prelude: common standard headers so refined code can use libc freely. */",
            "#include <stdio.h>",
            "#include <stdlib.h>",
            "#include <string.h>",
            "#include <stdint.h>",
            "#include <stdbool.h>",
            "#include <stddef.h>",
            "#include <limits.h>",
            "#include <math.h>",
            "#include <ctype.h>",
            "#include <errno.h>",
            "");

    static final String TEST_HARNESS = String.join("\n",
            "/* ---- Poneglyph test harness ---- */",
            "static int rd_checks = 0;",
            "static int rd_failures = 0;",
            "static void rd_report(void) {",
            "    fflush(stdout);",
            "    if (rd_failures > 0) {",
            "        printf(\"\\n" + RESULT_MARKER + " %d of %d checks failed\\n\", rd_failures, rd_checks);",
            "        fflush(stdout);",
            "        _Exit(1);",
            "    }",
            "    printf(\"\\n" + RESULT_MARKER + " all %d checks passed\\n\", rd_checks);",
            "    fflush(stdout);",
            "}",
            "__attribute__((constructor)) static void rd_install(void) { atexit(rd_report); }",
            "#define RD_CHECK(cond) do { rd_checks++; if (!(cond)) { rd_failures++; "
                    + "printf(\"" + FAIL_MARKER + " %s (test line %d)\\n\", #cond, __LINE__); fflush(stdout); } } while (0)",
            "#undef assert",
            "#define assert(cond) RD_CHECK(cond)",
            "/* ---- end harness ---- */",
            "");

    private Harness() {
    }

    /**
     * Prelude plus the refined code. Local {@code #include "x.h"} lines are dropped (there is no such
     * file), and a {@code #line} directive makes compiler diagnostics use the refined code's own line
     * numbers rather than counting the prelude.
     */
    public static String functionSource(String refinedCode) {
        StringBuilder sb = new StringBuilder(PRELUDE).append('\n');
        sb.append("#line 1 \"").append(FUNCTION_FILE).append("\"\n");
        for (String line : Text.normalizeNewlines(refinedCode).split("\n", -1)) {
            if (LOCAL_INCLUDE.matcher(line).matches()) {
                sb.append("/* removed by Poneglyph: ").append(line.strip()).append(" */\n");
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Assembles {@code test.c}. The model's system includes are hoisted above the harness (so a later
     * {@code #include <assert.h>} cannot undo the assert override), and replaced by blank lines so a
     * {@code #line} directive keeps the reported line numbers equal to the model's own test code.
     */
    public static String testSource(String testCode) {
        List<String> includes = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (String line : Text.normalizeNewlines(testCode).split("\n", -1)) {
            if (SYSTEM_INCLUDE.matcher(line).matches()) {
                includes.add(line.strip());
                body.append('\n');
            } else if (LOCAL_INCLUDE.matcher(line).matches()) {
                body.append('\n');
            } else {
                body.append(line).append('\n');
            }
        }
        StringBuilder sb = new StringBuilder(PRELUDE).append('\n');
        for (String inc : includes) {
            sb.append(inc).append('\n');
        }
        sb.append("#include \"").append(FUNCTION_FILE).append("\"\n\n");
        sb.append(TEST_HARNESS).append('\n');
        sb.append("#line 1 \"generated_tests.c\"\n");
        sb.append(body);
        return sb.toString();
    }

    public static Path writeFunction(Path dir, String refinedCode) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(FUNCTION_FILE);
        Files.writeString(p, functionSource(refinedCode));
        return p;
    }

    public static Path writeTest(Path dir, String testCode) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(TEST_FILE);
        Files.writeString(p, testSource(testCode));
        return p;
    }
}
