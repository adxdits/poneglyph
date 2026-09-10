package poneglyph.ghidra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PoneglyphSettingsTest {

    @Test
    void splitsFlagsOnWhitespace() {
        assertEquals(List.of(), PoneglyphSettings.splitFlags(null));
        assertEquals(List.of(), PoneglyphSettings.splitFlags("   "));
        assertEquals(List.of("-fsanitize=address,undefined", "-g"), PoneglyphSettings.splitFlags("  -fsanitize=address,undefined \t -g "));
    }
}
