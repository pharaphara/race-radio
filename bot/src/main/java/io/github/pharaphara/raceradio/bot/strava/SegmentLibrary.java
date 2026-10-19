package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.core.segments.Geo;
import io.github.pharaphara.raceradio.core.segments.Geo.Point;
import io.github.pharaphara.raceradio.core.segments.SegmentMatcher;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The segments kept locally, with their traces, and the passages of an activity over them: the
 * part of segments that needs no Strava subscription. Segments are Strava's, harvested with their
 * map, or the athlete's own (negative ids), defined from a stretch of one of their activities.
 */
public class SegmentLibrary {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A segment ready to match: its trace, its length, and a box around it to skip far ones fast. */
    public record Segment(long id, String name, List<Point> trace, double metres, double[] box) {

        boolean near(double[] other) {
            return box[0] <= other[2] && other[0] <= box[2] && box[1] <= other[3] && other[1] <= box[3];
        }
    }

    /** One passage of an activity over a segment. */
    public record Found(Segment segment, SegmentMatcher.Passage passage) {
    }

    private final JdbcTemplate jdbc;
    private volatile List<Segment> segments;

    public SegmentLibrary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Forgets the loaded segments, after a harvest or a new segment of the athlete's. */
    public void reload() {
        segments = null;
    }

    public List<Segment> segments() {
        List<Segment> s = segments;
        if (s == null) {
            s = load();
            segments = s;
        }
        return s;
    }

    private List<Segment> load() {
        List<Segment> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, name, detail FROM strava_segment WHERE detail IS NOT NULL")) {
            JsonNode d = JSON.readTree((String) r.get("detail"));
            List<Point> trace = d.has("points") ? points(d.path("points")) : Geo.decode(d.path("map").path("polyline").asString(""));
            double metres = d.path("distance").asDouble(0);
            if (trace.size() >= 2 && metres > 0) {
                out.add(new Segment(((Number) r.get("id")).longValue(), (String) r.get("name"), trace, metres, box(trace, 0.001)));
            }
        }
        return List.copyOf(out);
    }

    private static List<Point> points(JsonNode array) {
        List<Point> out = new ArrayList<>();
        for (JsonNode p : array) {
            out.add(new Point(p.get(0).asDouble(), p.get(1).asDouble()));
        }
        return out;
    }

    /** Every passage of the activity over the known segments, in the order they were ridden or run. */
    public List<Found> passages(ActivityStreams streams) {
        Optional<SegmentMatcher.Track> track = track(streams);
        if (track.isEmpty()) {
            return List.of();
        }
        double[] box = box(track.get());
        List<Found> out = new ArrayList<>();
        for (Segment s : segments()) {
            if (!s.near(box)) {
                continue;
            }
            for (SegmentMatcher.Passage p : SegmentMatcher.match(track.get(), s.trace(), s.metres())) {
                out.add(new Found(s, p));
            }
        }
        out.sort(java.util.Comparator.comparingInt(f -> f.passage().start()));
        return out;
    }

    /** The GPS track of an activity's streams, when it has one. */
    public static Optional<SegmentMatcher.Track> track(ActivityStreams s) {
        var lat = s.get("lat");
        var lng = s.get("lng");
        var time = s.get("time");
        if (lat.isEmpty() || lng.isEmpty() || time.isEmpty() || lat.get().presentCount() < 60) {
            return Optional.empty();
        }
        return Optional.of(new SegmentMatcher.Track(lat.get().toArray(), lng.get().toArray(), time.get().toArray()));
    }

    /** {minLat, minLng, maxLat, maxLng}, widened by {@code margin} degrees. */
    static double[] box(List<Point> pts, double margin) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Point p : pts) {
            b[0] = Math.min(b[0], p.lat());
            b[1] = Math.min(b[1], p.lng());
            b[2] = Math.max(b[2], p.lat());
            b[3] = Math.max(b[3], p.lng());
        }
        return new double[] {b[0] - margin, b[1] - margin, b[2] + margin, b[3] + margin};
    }

    static double[] box(SegmentMatcher.Track t) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            if (!Double.isNaN(t.lat()[i]) && !Double.isNaN(t.lng()[i])) {
                pts.add(new Point(t.lat()[i], t.lng()[i]));
            }
        }
        return pts.isEmpty() ? new double[] {0, 0, -1, -1} : box(pts, 0);
    }
}
