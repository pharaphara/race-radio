package io.github.pharaphara.raceradio.bot.athlete;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The athletes the coach serves, each with their own {@link AthleteServices}, built on first use and
 * kept: a draft or a button pressed a minute ago is still there. {@link #forget} drops one after
 * their settings change, so the next message rebuilds it.
 */
public final class AthleteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AthleteRegistry.class);
    /** Folders the administrator's files lived in before athletes, moved under their own folder once. */
    static final List<String> FOLDERS = List.of("streams", "llm", "photos");

    private final Athletes athletes;
    private final AthleteServices.Shared shared;
    private final Function<Athlete, String> keys;
    private final Map<Long, AthleteServices> built = new ConcurrentHashMap<>();

    /**
     * @param keys  an athlete's Intervals.icu key in clear: the configuration's for the administrator,
     *              their own stored key for the others
     */
    public AthleteRegistry(Athletes athletes, AthleteServices.Shared shared, Function<Athlete, String> keys) {
        this.athletes = athletes;
        this.shared = shared;
        this.keys = keys;
        moveAdministratorFiles();
    }

    /** One athlete's coach. */
    public AthleteServices services(Athlete a) {
        return built.computeIfAbsent(a.id().value(), id -> new AthleteServices(a, keys.apply(a), shared));
    }

    public AthleteServices administrator() {
        return services(athletes.administrator());
    }

    /** The coach of the active athlete this chat belongs to; empty for anyone else. */
    public Optional<AthleteServices> forChat(long chatId) {
        return athletes.byChat(chatId).map(this::services);
    }

    /** Every active athlete's coach; one that cannot be built is reported and left out, not the others. */
    public List<AthleteServices> active() {
        List<AthleteServices> out = new ArrayList<>();
        for (Athlete a : athletes.active()) {
            try {
                out.add(services(a));
            } catch (RuntimeException e) {
                LOG.error("{}: could not be set up: {}", a.label(), e.getMessage());
            }
        }
        return out;
    }

    public AthleteJobs jobs(AthleteServices s) {
        return new AthleteJobs(s, shared.telegram(), shared.props().zone());
    }

    public void forget(AthleteId id) {
        built.remove(id.value());
    }

    private void moveAdministratorFiles() {
        Path data = shared.props().dataDir();
        Path own = data.resolve("athletes").resolve(String.valueOf(athletes.administrator().id().value()));
        for (String folder : FOLDERS) {
            Path from = data.resolve(folder);
            Path to = own.resolve(folder);
            if (Files.isDirectory(from) && !Files.exists(to)) {
                try {
                    Files.createDirectories(own);
                    Files.move(from, to);
                    LOG.info("moved {} to {}", from, to);
                } catch (IOException e) {
                    throw new UncheckedIOException("moving " + from + " to " + to + " failed", e);
                }
            }
        }
    }
}
