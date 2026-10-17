package io.github.pharaphara.raceradio.charts;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;

/**
 * Rasterises the same SVG the web page serves. Text needs a font installed where this runs: the
 * container installs DejaVu, and the SVG asks for it first.
 */
public final class Png {

    private Png() {
    }

    /** @param scale 2 for a sharp image on a phone screen */
    public static byte[] of(String svg, double width, double scale) {
        PNGTranscoder t = new PNGTranscoder();
        t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) (width * scale));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            t.transcode(new TranscoderInput(new StringReader(svg)), new TranscoderOutput(out));
        } catch (TranscoderException e) {
            throw new IllegalStateException("could not rasterise the chart: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }
}
