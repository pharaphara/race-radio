package io.github.pharaphara.raceradio.charts;

import java.util.Locale;

/** A minimal SVG writer. Text is escaped; numbers are written with a dot, whatever the locale. */
public final class Svg {

    private final StringBuilder b = new StringBuilder();
    private final double width;
    private final double height;

    public Svg(double width, double height, Theme theme) {
        this.width = width;
        this.height = height;
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n(width)).append("\" height=\"").append(n(height))
                .append("\" viewBox=\"0 0 ").append(n(width)).append(' ').append(n(height))
                .append("\" font-family=\"").append(esc(theme.font())).append("\">\n");
        rect(0, 0, width, height, theme.surface(), 0, 1);
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public Svg rect(double x, double y, double w, double h, String fill, double radius, double opacity) {
        b.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\"%s/>%n",
                x, y, Math.max(0, w), Math.max(0, h), radius, fill, opacity < 1 ? String.format(Locale.ROOT, " fill-opacity=\"%.2f\"", opacity) : ""));
        return this;
    }

    /**
     * A bar with a 4 px rounded data end and a square base, as the mark specification asks:
     * drawn as a rounded rectangle with its base half covered by a square one.
     */
    public Svg bar(double x, double y, double w, double h, String fill, boolean horizontal) {
        double r = Math.min(4, Math.min(w, h) / 2);
        rect(x, y, w, h, fill, r, 1);
        if (horizontal) {
            rect(x, y, Math.min(w, r), h, fill, 0, 1);
        } else {
            rect(x, y + h - Math.min(h, r), w, Math.min(h, r), fill, 0, 1);
        }
        return this;
    }

    public Svg line(double x1, double y1, double x2, double y2, String stroke, double strokeWidth) {
        b.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%.1f\"/>%n",
                x1, y1, x2, y2, stroke, strokeWidth));
        return this;
    }

    /** A 2 px polyline with round joins; NaN points break the line rather than dropping to zero. */
    public Svg path(double[] xs, double[] ys, String stroke) {
        StringBuilder d = new StringBuilder();
        boolean pen = false;
        for (int i = 0; i < xs.length; i++) {
            if (Double.isNaN(ys[i])) {
                pen = false;
                continue;
            }
            d.append(pen ? 'L' : 'M').append(n(xs[i])).append(' ').append(n(ys[i])).append(' ');
            pen = true;
        }
        b.append("<path d=\"").append(d.toString().strip()).append("\" fill=\"none\" stroke=\"").append(stroke)
                .append("\" stroke-width=\"2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>\n");
        return this;
    }

    public Svg circle(double cx, double cy, double r, String fill, String ring) {
        b.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"/>%n",
                cx, cy, r, fill, ring));
        return this;
    }

    public Svg text(double x, double y, String text, double size, String fill, String anchor, boolean bold) {
        b.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"%s\" text-anchor=\"%s\"%s>%s</text>%n",
                x, y, size, fill, anchor, bold ? " font-weight=\"bold\"" : "", esc(text)));
        return this;
    }

    public String toString() {
        return b + "</svg>\n";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
