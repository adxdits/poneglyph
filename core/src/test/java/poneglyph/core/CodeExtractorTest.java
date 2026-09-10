package poneglyph.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeExtractorTest {

    private static final String GHIDRA = String.join("\n",
            "long FUN_00101149(long param_1,int param_2)",
            "",
            "{",
            "  int local_14;",
            "  long local_10;",
            "",
            "  local_10 = 0;",
            "  for (local_14 = 0; local_14 < param_2; local_14 = local_14 + 1) {",
            "    local_10 = local_10 + (long)*(int *)(param_1 + (long)local_14 * 4);",
            "  }",
            "  return local_10;",
            "}",
            "");

    @Test
    void extractsFencedBlockAndDropsProse() {
        String raw = "Sure! Here is the cleaned up code:\n\n```c\nint add(int a, int b)\n{\n    return a + b;\n}\n```\n\n"
                + "This adds two numbers.";
        assertEquals("int add(int a, int b)\n{\n    return a + b;\n}\n", CodeExtractor.extract(raw).orElseThrow());
    }

    @Test
    void returnsEmptyForProseOnly() {
        assertTrue(CodeExtractor.extract("I'm sorry, I cannot rewrite this function.").isEmpty());
        assertTrue(CodeExtractor.extract("").isEmpty());
        assertTrue(CodeExtractor.extract(null).isEmpty());
    }

    @Test
    void stripsThinkBlocks() {
        String raw = "<think>Let me think about int helper(void) { return 0; }</think>\n```c\nint f(void)\n{\n    return 1;\n}\n```";
        String code = CodeExtractor.extract(raw).orElseThrow();
        assertFalse(code.contains("helper"));
        assertTrue(code.contains("int f(void)"));
    }

    @Test
    void handlesUnfencedCodeWithSurroundingProse() {
        String raw = "Here you go.\nint twice(int x)\n{\n    return 2 * x;\n}\nThis function doubles its input.";
        assertEquals("int twice(int x)\n{\n    return 2 * x;\n}\n", CodeExtractor.extract(raw).orElseThrow());
    }

    @Test
    void keepsDeclarationBlocksThatPrecedeTheFunctionBlock() {
        String raw = "First a struct:\n```c\ntypedef struct { int x; } Point;\n```\nThen the function:\n"
                + "```c\nint get_x(const Point *p)\n{\n    return p->x;\n}\n```";
        String code = CodeExtractor.extract(raw).orElseThrow();
        assertTrue(code.startsWith("typedef struct { int x; } Point;"));
        assertTrue(code.contains("int get_x(const Point *p)"));
    }

    @Test
    void picksTheLongestFunctionBearingBlock() {
        String raw = "Original:\n```c\nint FUN_1(int a)\n{\n  return a;\n}\n```\nRewritten:\n"
                + "```c\n/* identity */\nint identity(int value)\n{\n    return value;\n}\n```";
        String code = CodeExtractor.extract(raw).orElseThrow();
        assertTrue(code.contains("identity"));
        assertFalse(code.contains("FUN_1"));
    }

    @Test
    void handlesUnterminatedFence() {
        String raw = "```c\nint f(void)\n{\n    return 1;\n}";
        assertEquals("int f(void)\n{\n    return 1;\n}\n", CodeExtractor.extract(raw).orElseThrow());
    }

    @Test
    void ghidraStyleSignatureWithBlankLineBeforeBrace() {
        Optional<String> code = CodeExtractor.extract(GHIDRA);
        assertTrue(code.isPresent());
        assertTrue(code.get().contains("FUN_00101149"));
    }

    @Test
    void findsFunctionName() {
        assertEquals("FUN_00101149", CodeExtractor.findFunctionName(GHIDRA).orElseThrow());
        assertEquals("add", CodeExtractor.findFunctionName("static int add(int a, int b) {\n return a+b; }").orElseThrow());
        assertEquals("find", CodeExtractor.findFunctionName("struct node *find(struct node *head, int key)\n{\n}").orElseThrow());
        assertEquals(List.of("a", "b"), CodeExtractor.findFunctionNames("int a(void) {\n}\nvoid b(int x)\n{\n}\n"));
    }

    @Test
    void controlFlowAndCallsAreNotDefinitions() {
        assertFalse(CodeExtractor.containsFunctionDefinition("if (x)\n{\n}"));
        assertFalse(CodeExtractor.containsFunctionDefinition("while (x != 0) {\n}"));
        assertFalse(CodeExtractor.containsFunctionDefinition("return foo(x);\n{\n}"));
        assertFalse(CodeExtractor.containsFunctionDefinition("x = foo(a, b);\n{\n}"));
        assertFalse(CodeExtractor.containsFunctionDefinition("int f(int a);"));
        assertTrue(CodeExtractor.extract("int f(int a);\nint g(int b);").isEmpty());
    }

    @Test
    void multipleFunctionsInOneBlockAreKeptTogether() {
        String raw = "```c\nstatic int helper(int x)\n{\n    return x + 1;\n}\n\nint api(int x)\n{\n    return helper(x);\n}\n```";
        String code = CodeExtractor.extract(raw).orElseThrow();
        assertTrue(code.contains("helper(int x)"));
        assertTrue(code.contains("int api(int x)"));
    }
}
