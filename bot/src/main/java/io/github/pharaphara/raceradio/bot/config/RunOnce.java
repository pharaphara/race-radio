package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.review.SessionReview;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs one job, prints the result and exits, without Telegram and without publishing anything.
 * Handy to check a deployment, or the wording.
 * <ul>
 *   <li>{@code --race-radio.once=morning}: the morning call.</li>
 *   <li>{@code --race-radio.once=review:<activity id>}: the review of one activity of the last year.</li>
 *   <li>{@code --race-radio.once=session:<request>}: the draft of one workout, shown, never published.</li>
 *   <li>{@code --race-radio.once=week:<request>}: the draft of a week, shown in full, never published.</li>
 *   <li>{@code --race-radio.once=archive}: fills the local archive, as the service does in the background.</li>
 *   <li>{@code --race-radio.once=strava}: one step of the Strava segment harvest.</li>
 *   <li>{@code --race-radio.once=calibrate}: local segment timing against Strava's, on every archived pair.</li>
 *   <li>{@code --race-radio.once=note:<text>}: keeps a note about the athlete, as /note does.</li>
 *   <li>{@code --race-radio.once=ask:<message>}: the coach's answer to a message, its buttons and action, nothing sent.</li>
 *   <li>{@code --race-radio.once=trend:<metric>}: one metric over twelve months, from the archive.</li>
 * </ul>
 */
@Component
public class RunOnce implements ApplicationRunner {

    private final String once;
    private final ConfigurableApplicationContext context;
    private final java.nio.file.Path charts;
    private final io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry registry;
    private final io.github.pharaphara.raceradio.bot.strava.StravaHarvest strava;
    private final io.github.pharaphara.raceradio.bot.store.Database.Outcome database;
    // The administrator's coach, set when a job runs: one-off runs act for the administrator.
    private MorningCheck morning;
    private SessionReview review;
    private IntervalsClient intervals;
    private io.github.pharaphara.raceradio.bot.strength.BoardReader boards;
    private io.github.pharaphara.raceradio.bot.plan.SessionCreator sessions;
    private io.github.pharaphara.raceradio.bot.archive.Archive archive;
    private io.github.pharaphara.raceradio.bot.archive.Trends trends;
    private io.github.pharaphara.raceradio.bot.chat.Conversation conversation;
    private io.github.pharaphara.raceradio.bot.store.Conversations memory;
    private io.github.pharaphara.raceradio.bot.strava.Calibration calibration;

    public RunOnce(@Value("${race-radio.once:}") String once, ConfigurableApplicationContext context, RaceRadioProperties props,
                   io.github.pharaphara.raceradio.bot.athlete.AthleteRegistry registry,
                   io.github.pharaphara.raceradio.bot.strava.StravaHarvest strava,
                   io.github.pharaphara.raceradio.bot.store.Database.Outcome database) {
        this.once = once;
        this.context = context;
        this.charts = props.dataDir().resolve("charts");
        this.registry = registry;
        this.strava = strava;
        this.database = database;
    }

    private void forAdministrator() {
        io.github.pharaphara.raceradio.bot.athlete.AthleteServices a = registry.administrator();
        morning = a.morning();
        review = a.review();
        intervals = a.intervals();
        boards = a.boards();
        sessions = a.sessions();
        archive = a.archive();
        trends = a.trends();
        conversation = a.conversation();
        memory = a.memory();
        calibration = a.calibration();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (once.isBlank()) {
            return;
        }
        if (!once.equals("reset")) {
            forAdministrator();
        }
        if (once.equals("reset")) {
            // The move itself was made when the database was prepared, before anything opened it.
            System.out.println("\n" + database.report());
        } else if (once.equals("morning")) {
            MorningCheck.Outcome o = morning.run(false);
            print(o.message(), o.worded(), "score " + o.readiness().score() + "/100");
        } else if (once.startsWith("review:")) {
            String id = once.substring("review:".length());
            ActivitySummary a = intervals.activities(LocalDate.now().minusYears(1), LocalDate.now()).stream()
                    .filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no activity " + id + " in the last year"));
            SessionReview.Outcome o = review.review(a, false);
            print(o.message(), o.worded(), "structure " + o.facts().structureSource() + ", " + o.facts().repetitions());
            save(id, o.images());
        } else if (once.startsWith("facts:")) {
            String id = once.substring("facts:".length());
            ActivitySummary a = intervals.activities(LocalDate.now().minusYears(1), LocalDate.now()).stream()
                    .filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no activity " + id + " in the last year"));
            System.out.println("\n" + tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(review.facts(a)));
        } else if (once.startsWith("photo:")) {
            try {
                byte[] photo = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(once.substring("photo:".length())));
                System.out.println("\n" + boards.read(photo, null));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        } else if (once.equals("calibrate")) {
            System.out.println("\n" + calibration.run().text());
        } else if (once.equals("strava")) {
            System.out.println("\nstrava: " + strava.run());
        } else if (once.equals("archive")) {
            archive.backfill();
            System.out.println("\narchive: " + archive.activities() + " activities");
        } else if (once.startsWith("trend:")) {
            String words = once.substring("trend:".length());
            var metric = io.github.pharaphara.raceradio.bot.archive.MetricCatalog.find(words)
                    .orElseThrow(() -> new IllegalArgumentException("no metric for: " + words));
            var answer = trends.answer(metric, 12);
            System.out.println("\n" + answer.text());
            save("trend-" + metric.key(), answer.chart() == null ? java.util.List.of() : java.util.List.of(answer.chart()));
        } else if (once.startsWith("session:")) {
            System.out.println("\n" + sessions.prepare("/seance " + once.substring("session:".length())).message());
        } else if (once.startsWith("note:")) {
            long id = memory.addNote(java.time.Instant.now(), once.substring("note:".length()).strip());
            System.out.println("\nnote " + id + " kept");
        } else if (once.startsWith("ask:")) {
            var r = conversation.reply(once.substring("ask:".length()));
            System.out.println("\n" + (r.text() == null ? "(answered by the program)" : r.text()));
            r.suggestions().forEach(s -> System.out.println("[bouton] " + s));
            if (r.action() != null) {
                System.out.println("[action] " + r.action().kind() + " " + r.action().request());
            }
        } else if (once.startsWith("week:")) {
            var p = sessions.prepareWeek("/semaine " + once.substring("week:".length()));
            System.out.println("\n" + p.message());
            if (p.pending() != null) {
                System.out.println("\n----- détail\n" + sessions.detail(p.pending()));
            }
        } else if (once.equals("fitness")) {
            byte[] chart = morning.fitnessChart();
            save("fitness", chart == null ? java.util.List.of() : java.util.List.of(chart));
        } else {
            throw new IllegalArgumentException("race-radio.once must be reset, morning, fitness, archive, strava, calibrate, note:<text>, ask:<message>, trend:<metric>, "
                    + "review:<activity id>, photo:<file>, session:<request> or week:<request>");
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void save(String name, java.util.List<byte[]> images) {
        try {
            java.nio.file.Files.createDirectories(charts);
            for (int i = 0; i < images.size(); i++) {
                java.nio.file.Path p = charts.resolve(name + "-" + (i + 1) + ".png");
                java.nio.file.Files.write(p, images.get(i));
                System.out.println("image : " + p);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void print(String message, boolean worded, String detail) {
        System.out.println("\n" + message + "\n\n(" + (worded ? "rédigé par le modèle" : "texte de repli, sans modèle") + ", " + detail + ")");
    }
}
