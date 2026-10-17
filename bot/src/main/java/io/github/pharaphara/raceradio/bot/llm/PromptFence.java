package io.github.pharaphara.raceradio.bot.llm;

/**
 * Wraps everything that did not come from this program (activity names and descriptions, the
 * athlete's free text, text read from a photo, segment names) in a tagged block that the system
 * prompt declares to be data, never instructions. A closing tag inside the data is neutralised,
 * so a description cannot end the block early and smuggle in an instruction.
 */
public final class PromptFence {

    private PromptFence() {
    }

    public static String data(String source, String content) {
        String safeSource = source.replaceAll("[^a-zA-Z0-9_.-]", "_");
        String safeContent = content == null ? "" : content.replace("</data", "<\\/data");
        return "<data source=\"" + safeSource + "\">\n" + safeContent + "\n</data>";
    }
}
