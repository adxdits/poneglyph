package poneglyph.core.compile;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessTest {

    @Test
    void functionSourceAddsPreludeAndDropsLocalIncludes() {
        String src = Harness.functionSource("#include \"secret.h\"\n#include <stdint.h>\nint f(void) { return 1; }\n");
        assertTrue(src.contains("#include <stdio.h>"));
        assertTrue(src.contains("#include <stdint.h>"));
        assertTrue(src.contains("int f(void) { return 1; }"));
        for (String line : src.split("\n")) {
            assertFalse(line.trim().startsWith("#include \""), "local include survived: " + line);
        }
    }

    @Test
    void testSourceHoistsIncludesAndOverridesAssertAfterThem() {
        String tests = "#include <assert.h>\n#include <string.h>\nint main(void)\n{\n    assert(1);\n    return 0;\n}\n";
        String src = Harness.testSource(tests);
        int assertInclude = src.indexOf("#include <assert.h>");
        int functionInclude = src.indexOf("#include \"function.c\"");
        int override = src.indexOf("#define assert(cond) RD_CHECK(cond)");
        int lineDirective = src.indexOf("#line 1 \"generated_tests.c\"");
        int main = src.indexOf("int main(void)");
        assertTrue(assertInclude >= 0 && functionInclude > assertInclude, "includes must come before function.c");
        assertTrue(override > functionInclude, "assert override must come after function.c");
        assertTrue(lineDirective > override && main > lineDirective);

        // Line numbers of the model's tests are preserved after the #line directive.
        List<String> after = Arrays.asList(src.substring(lineDirective).split("\n"));
        assertEquals("#line 1 \"generated_tests.c\"", after.get(0));
        assertEquals("", after.get(1));
        assertEquals("", after.get(2));
        assertEquals("int main(void)", after.get(3));
    }

    @Test
    void harnessPrintsMachineReadableMarkers() {
        assertTrue(Harness.TEST_HARNESS.contains(Harness.RESULT_MARKER));
        assertTrue(Harness.TEST_HARNESS.contains(Harness.FAIL_MARKER));
        assertTrue(Harness.TEST_HARNESS.contains("atexit(rd_report)"));
    }
}
