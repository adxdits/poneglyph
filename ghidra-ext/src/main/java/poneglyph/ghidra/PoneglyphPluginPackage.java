package poneglyph.ghidra;

import ghidra.framework.plugintool.util.PluginPackage;
import resources.Icons;

/** Groups the Poneglyph plugin in Ghidra's plugin configuration dialog. */
public class PoneglyphPluginPackage extends PluginPackage {

    public static final String NAME = "Poneglyph";

    public PoneglyphPluginPackage() {
        super(NAME, Icons.INFO_ICON,
                "AI-assisted decompilation: rewrite decompiler output with a local LLM and verify it by compiling and testing.");
    }
}
