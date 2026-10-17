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
 * </ul>
 */
@Component
public class RunOnce implements ApplicationRunner {

    private final String once;
    private final MorningCheck morning;
    private final SessionReview review;
    private final IntervalsClient intervals;
    private final ConfigurableApplicationContext context;
    private final java.nio.file.Path charts;
    private final io.github.pharaphara.raceradio.bot.strength.BoardReader boards;

    public RunOnce(@Value("${race-radio.once:}") String once, MorningCheck morning, SessionReview review,
                   IntervalsClient intervals, ConfigurableApplicationContext context, RaceRadioProperties props,
                   io.github.pharaphara.raceradio.bot.strength.BoardReader boards) {
        this.boards = boards;
        this.charts = props.dataDir().resolve("charts");
        this.once = once;
        this.morning = morning;
        this.review = review;
        this.intervals = intervals;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (once.isBlank()) {
            return;
        }
        if (once.equals("morning")) {
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
        } else if (once.startsWith("photo:")) {
            try {
                byte[] photo = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(once.substring("photo:".length())));
                System.out.println("\n" + boards.read(photo, null));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        } else if (once.equals("fitness")) {
            byte[] chart = morning.fitnessChart();
            save("fitness", chart == null ? java.util.List.of() : java.util.List.of(chart));
        } else {
            throw new IllegalArgumentException("race-radio.once must be morning, fitness, review:<activity id> or photo:<file>");
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
