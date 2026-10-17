package io.github.pharaphara.raceradio.charts;

import java.util.List;

/**
 * Colours by role. The categorical slots are taken in this fixed order and never cycled; the set
 * was checked with a colour-vision validator against its surface (lightness band, chroma floor,
 * adjacent-pair separation for colour-blind and normal vision, contrast all pass in dark mode).
 * Text never wears a series colour: identity comes from the mark beside it.
 */
public record Theme(String surface, String textPrimary, String textSecondary, String textMuted,
                    String grid, List<String> series, String font) {

    /** Dark, the default: images are read on a phone, often at night. */
    public static final Theme DARK = new Theme("#1a1a19", "#f0efec", "#c3c2b7", "#8f8e86", "#2f2f2c",
            List.of("#3987e5", "#d95926", "#199e70", "#c98500"), "DejaVu Sans, Arial, sans-serif");

    public static final Theme LIGHT = new Theme("#fcfcfb", "#0b0b0b", "#52514e", "#7a7974", "#e6e5e1",
            List.of("#2a78d6", "#eb6834", "#1baf7a", "#eda100"), "DejaVu Sans, Arial, sans-serif");

    public String slot(int i) {
        if (i < 0 || i >= series.size()) {
            throw new IllegalArgumentException("only " + series.size() + " series slots: fold the rest into one, or split the chart");
        }
        return series.get(i);
    }
}
