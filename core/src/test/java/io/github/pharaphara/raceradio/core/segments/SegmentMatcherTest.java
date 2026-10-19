package io.github.pharaphara.raceradio.core.segments;

import io.github.pharaphara.raceradio.core.segments.Geo.Point;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SegmentMatcherTest {

    /** A fictional place; one degree of longitude here is about 76 km. */
    private static final double LAT = 46.0;
    private static final double LNG = 3.0;
    private static final double M_PER_DEG_LNG = Math.toRadians(1) * Geo.EARTH_RADIUS * Math.cos(Math.toRadians(LAT));
    private static final double M_PER_DEG_LAT = Math.toRadians(1) * Geo.EARTH_RADIUS;

    private static Point at(double east, double north) {
        return new Point(LAT + north / M_PER_DEG_LAT, LNG + east / M_PER_DEG_LNG);
    }

    /** A 1 km segment due east from the origin. */
    private static final List<Point> SEGMENT = List.of(at(0, 0), at(500, 0), at(1000, 0));

    /** A track through {@code waypoints} (east, north) at {@code speed} m/s, one reading a second. */
    private static SegmentMatcher.Track ride(double speed, double[]... waypoints) {
        List<double[]> pts = new ArrayList<>();
        for (int w = 1; w < waypoints.length; w++) {
            double[] a = waypoints[w - 1];
            double[] b = waypoints[w];
            double d = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int n = (int) Math.round(d / speed);
            for (int k = 0; k < n; k++) {
                double f = (double) k / n;
                pts.add(new double[] {a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])});
            }
        }
        pts.add(waypoints[waypoints.length - 1]);
        double[] lat = new double[pts.size()];
        double[] lng = new double[pts.size()];
        double[] time = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            Point p = at(pts.get(i)[0], pts.get(i)[1]);
            lat[i] = p.lat();
            lng[i] = p.lng();
            time[i] = i;
        }
        return new SegmentMatcher.Track(lat, lng, time);
    }

    @Test
    void googlesReferencePolylineDecodes() {
        List<Point> p = Geo.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertThat(p).hasSize(3);
        assertThat(p.get(0).lat()).isCloseTo(38.5, within(1e-9));
        assertThat(p.get(0).lng()).isCloseTo(-120.2, within(1e-9));
        assertThat(p.get(2).lat()).isCloseTo(43.252, within(1e-9));
        assertThat(p.get(2).lng()).isCloseTo(-126.453, within(1e-9));
    }

    @Test
    void aPassageTenMetresOffTheLineIsTimedFromLineToLine() {
        SegmentMatcher.Track t = ride(5, new double[] {-200, 10}, new double[] {1500, 10});
        List<SegmentMatcher.Passage> p = SegmentMatcher.match(t, SEGMENT, 1000);
        assertThat(p).hasSize(1);
        assertThat(p.getFirst().seconds()).isCloseTo(200, within(1.0));
    }

    @Test
    void theSegmentRiddenBackwardsDoesNotCount() {
        SegmentMatcher.Track t = ride(5, new double[] {1500, 0}, new double[] {-200, 0});
        assertThat(SegmentMatcher.match(t, SEGMENT, 1000)).isEmpty();
    }

    @Test
    void twoLapsGiveTwoPassages() {
        SegmentMatcher.Track t = ride(5, new double[] {-100, 0}, new double[] {1100, 0}, new double[] {1100, 300},
                new double[] {-100, 300}, new double[] {-100, 0}, new double[] {1100, 0});
        List<SegmentMatcher.Passage> p = SegmentMatcher.match(t, SEGMENT, 1000);
        assertThat(p).hasSize(2).allSatisfy(x -> assertThat(x.seconds()).isCloseTo(200, within(1.0)));
    }

    @Test
    void touchingBothEndsWithoutFollowingTheSegmentIsNotAPassage() {
        // Same start and finish, but a detour 300 m north in between: 90 % of the segment is never near.
        SegmentMatcher.Track t = ride(5, new double[] {-100, 0}, new double[] {0, 0}, new double[] {200, 300},
                new double[] {800, 300}, new double[] {1000, 0}, new double[] {1100, 0});
        assertThat(SegmentMatcher.match(t, SEGMENT, 1000)).isEmpty();
    }
}
