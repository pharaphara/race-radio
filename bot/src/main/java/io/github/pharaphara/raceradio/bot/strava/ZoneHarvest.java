package io.github.pharaphara.raceradio.bot.strava;

import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/**
 * Every Strava segment of an area, added to the shared library: the administrator's own Strava
 * application asks {@code segments/explore}, which gives at most ten segments per rectangle, so a
 * disc is cut into squares of about {@value #TILE_KM} km and asked square by square, within the read
 * quota, resuming where it stopped. Found segments enter the library by name; their traces are
 * fetched afterwards, one call each, by the harvest that already fetches library traces.
 */
public final class ZoneHarvest {

    private static final Logger LOG = LoggerFactory.getLogger(ZoneHarvest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final double TILE_KM = 2.5;
    private static final double KM_PER_DEGREE = 111.32;
    private static final String KEY = "strava.zone";

    private final StravaClient strava;
    private final JdbcTemplate jdbc;
    private final long athlete;
    private final Clock clock;

    public ZoneHarvest(StravaClient strava, AthleteDb db, Clock clock) {
        this.strava = strava;
        this.jdbc = db.jdbc();
        this.athlete = db.id();
        this.clock = clock;
    }

    /** Squares of the disc around a point, south-west and north-east corners, those touching the disc only. */
    static ArrayNode tiles(double lat, double lon, double radiusKm) {
        ArrayNode out = JSON.createArrayNode();
        double dLat = TILE_KM / KM_PER_DEGREE;
        double dLon = TILE_KM / (KM_PER_DEGREE * Math.cos(Math.toRadians(lat)));
        int n = (int) Math.ceil(radiusKm / TILE_KM);
        for (int i = -n; i < n; i++) {
            for (int j = -n; j < n; j++) {
                // Distance from the centre to the nearest point of the square, in km.
                double nearX = Math.max(0, Math.max(i, -(i + 1)) * TILE_KM);
                double nearY = Math.max(0, Math.max(j, -(j + 1)) * TILE_KM);
                if (Math.hypot(nearX, nearY) > radiusKm) {
                    continue;
                }
                double s = lat + i * dLat;
                double w = lon + j * dLon;
                out.addArray().add(round(s)).add(round(w)).add(round(s + dLat)).add(round(w + dLon));
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

    /** Starts a new area, replacing any unfinished one. Returns the number of squares to ask. */
    public int start(double lat, double lon, double radiusKm, boolean running) {
        ObjectNode zone = JSON.createObjectNode();
        zone.put("lat", lat).put("lon", lon).put("radius", radiusKm).put("type", running ? "running" : "riding")
                .put("asked", 0).put("found", 0);
        zone.set("tiles", tiles(lat, lon, radiusKm));
        save(zone);
        LOG.info("strava zone: {} squares to explore", zone.path("tiles").size());
        return zone.path("tiles").size();
    }

    /** Asks the squares left, until done or the quota stops it; resumes there next time. */
    public synchronized void step() {
        Optional<ObjectNode> z = zone();
        if (z.isEmpty() || z.get().path("tiles").isEmpty() || !strava.configured()) {
            return;
        }
        ObjectNode zone = z.get();
        ArrayNode tiles = (ArrayNode) zone.path("tiles");
        try {
            while (!tiles.isEmpty()) {
                JsonNode t = tiles.get(0);
                JsonNode found = strava.get(String.format(Locale.ROOT, "segments/explore?bounds=%s,%s,%s,%s&activity_type=%s",
                        t.get(0).asDouble(), t.get(1).asDouble(), t.get(2).asDouble(), t.get(3).asDouble(), zone.path("type").asString()));
                for (JsonNode s : found.path("segments")) {
                    zone.put("found", zone.path("found").asInt() + jdbc.update(
                            "INSERT OR IGNORE INTO segment (id, origin, name, added_by) VALUES (?, 'strava', ?, ?)",
                            s.path("id").asLong(), s.path("name").asString(), athlete));
                }
                tiles.remove(0);
                zone.put("asked", zone.path("asked").asInt() + 1);
                save(zone);
            }
            LOG.info("strava zone explored: {} new segments", zone.path("found").asInt());
        } catch (StravaClient.QuotaReached e) {
            LOG.info("strava zone paused by the quota, {} squares left", tiles.size());
        }
    }

    /** Where the area stands, for /segment zone. */
    public String status() {
        return zone().map(z -> {
            int left = z.path("tiles").size();
            int asked = z.path("asked").asInt();
            return String.format(Locale.FRANCE, "Zone de %.0f km autour de %.4f, %.4f (%s) : %d carrés explorés sur %d, %d nouveaux segments%s.",
                    z.path("radius").asDouble(), z.path("lat").asDouble(), z.path("lon").asDouble(),
                    z.path("type").asString().equals("running") ? "course" : "vélo", asked, asked + left, z.path("found").asInt(),
                    left == 0 ? ", terminée ; leurs tracés arrivent avec la récupération Strava" : ", en cours dans le quota Strava");
        }).orElse("Aucune zone en cours.");
    }

    private Optional<ObjectNode> zone() {
        return jdbc.queryForList("SELECT value FROM setting WHERE athlete_id = ? AND key = ?", String.class, athlete, KEY).stream()
                .findFirst().map(v -> (ObjectNode) JSON.readTree(v));
    }

    private void save(ObjectNode zone) {
        jdbc.update("INSERT INTO setting (athlete_id, key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT(athlete_id, key) DO UPDATE SET value = excluded.value", athlete, KEY, zone.toString());
    }
}
