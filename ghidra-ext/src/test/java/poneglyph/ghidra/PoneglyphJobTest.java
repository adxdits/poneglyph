package poneglyph.ghidra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoneglyphJobTest {

    private static final String BLOCK = PoneglyphJob.COMMENT_BEGIN + "\nGREEN: compiles and passes tests\nint f(void) { return 1; }\n"
            + PoneglyphJob.COMMENT_END;

    @Test
    void emptyCommentBecomesTheBlock() {
        assertEquals(BLOCK, PoneglyphJob.mergeComment(null, BLOCK));
        assertEquals(BLOCK, PoneglyphJob.mergeComment("   ", BLOCK));
    }

    @Test
    void userCommentIsPreservedAndBlockAppended() {
        String merged = PoneglyphJob.mergeComment("Handles the login handshake.\n", BLOCK);
        assertEquals("Handles the login handshake.\n\n" + BLOCK, merged);
    }

    @Test
    void existingBlockIsReplacedInPlace() {
        String old = "before text\n\n" + PoneglyphJob.COMMENT_BEGIN + "\nRED: does not compile\nint f(void) { return 1 }\n"
                + PoneglyphJob.COMMENT_END + "\n\nafter text";
        String merged = PoneglyphJob.mergeComment(old, BLOCK);
        assertEquals("before text\n\n" + BLOCK + "\n\nafter text", merged);
        assertTrue(merged.indexOf(PoneglyphJob.COMMENT_BEGIN) == merged.lastIndexOf(PoneglyphJob.COMMENT_BEGIN),
                "only one block should remain");
    }
}
