package io.github.pharaphara.raceradio.bot.archive;

import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.model.ActivityStreams;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An activity's streams, fetched from Intervals.icu once and then read from disk. The CSV is kept as
 * sent, gzipped, readable by the service only: it holds GPS traces. Anything computed later, by the
 * review or the archive, reads the same file without a new call.
 */
public class StreamCache {

    private final IntervalsClient intervals;
    private final Path dir;

    public StreamCache(IntervalsClient intervals, Path dir) {
        this.intervals = intervals;
        this.dir = dir;
    }

    public ActivityStreams get(String activityId) {
        return IntervalsClient.parseStreamsCsv(csv(activityId));
    }

    /** True when the streams are already on disk, so reading them costs no call. */
    public boolean has(String activityId) {
        return Files.exists(file(activityId));
    }

    /** Forgets an activity's streams, when they changed upstream (a merge). */
    public void evict(String activityId) {
        try {
            Files.deleteIfExists(file(activityId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String csv(String activityId) {
        Path f = file(activityId);
        try {
            if (Files.exists(f)) {
                try (var in = new GZIPInputStream(Files.newInputStream(f))) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            String csv = intervals.streamsCsv(activityId);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, activityId, ".tmp");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            try (var out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(csv.getBytes(StandardCharsets.UTF_8));
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return csv;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String activityId) {
        if (!activityId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("not an activity id: " + activityId);
        }
        return dir.resolve(activityId + ".csv.gz");
    }
}
