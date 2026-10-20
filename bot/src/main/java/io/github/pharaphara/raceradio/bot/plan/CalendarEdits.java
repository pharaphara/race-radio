package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.archive.Archive;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Workouts already on the calendar, moved to another day ({@code /deplacer jeudi vendredi}) or
 * changed ({@code /modifier demain plus court}).
 *
 * <p>Only the coach's own workouts are touched, as everywhere else: one from Garmin, another tool
 * or the athlete is named and left alone. A move changes only the date, so the workout keeps its
 * steps and its note, and comes with a button to put it back. A change is a new draft, published
 * only on Publier, and the old workout is removed only once the new one is in.
 */
public final class CalendarEdits {

    private static final Logger LOG = LoggerFactory.getLogger(CalendarEdits.class);
    private static final Pattern DAY_TOKEN = Pattern.compile(
            "(?i)aujourd'hui|aujourdhui|auj|demain|après-demain|apres-demain|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche"
                    + "|lun|mar|mer|jeu|ven|sam|dim|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}");
    private static final Map<String, Set<String>> SPORTS = Map.of(
            "vélo", Set.of("Ride", "VirtualRide", "GravelRide", "MountainBikeRide"),
            "course", Set.of("Run", "TrailRun", "VirtualRun"),
            "natation", Set.of("Swim", "OpenWaterSwim"));
    private static final Map<String, String> SPORT_WORDS = Map.ofEntries(
            Map.entry("vélo", "vélo"), Map.entry("velo", "vélo"), Map.entry("home-trainer", "vélo"), Map.entry("bike", "vélo"),
            Map.entry("cap", "course"), Map.entry("course", "course"), Map.entry("footing", "course"), Map.entry("trail", "course"),
            Map.entry("run", "course"), Map.entry("natation", "natation"), Map.entry("nage", "natation"),
            Map.entry("piscine", "natation"), Map.entry("swim", "natation"));

    private final IntervalsWriter writer;
    private final Archive archive;
    private final SessionCreator sessions;
    private final TelegramClient telegram;
    private final long chatId;
    private final Clock clock;
    /** Changes waiting for the athlete to pick which of a day's workouts they meant. */
    private final Map<Long, String> changes = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    CalendarEdits(IntervalsWriter writer, Archive archive, SessionCreator sessions, TelegramClient telegram, long chatId, Clock clock) {
        this.writer = writer;
        this.archive = archive;
        this.sessions = sessions;
        this.telegram = telegram;
        this.chatId = chatId;
        this.clock = clock;
    }

    /** What a request names: the days in order, a sport if any, and the rest of the words. */
    record Request(List<LocalDate> days, String sport, String rest) {
    }

    /** Reads the days and the sport out of "jeudi à vendredi, le vélo" or "2026-10-23 2026-10-24 vélo". */
    static Request parse(String text, LocalDate today) {
        List<LocalDate> days = new ArrayList<>();
        String sport = null;
        List<String> rest = new ArrayList<>();
        for (String word : text.strip().split("\\s+")) {
            String w = word.replaceAll("^[«\"(]+|[»\",.;:!?)]+$", "");
            if (w.isEmpty()) {
                continue;
            }
            String lower = w.toLowerCase(Locale.ROOT);
            if (DAY_TOKEN.matcher(lower).matches()) {
                days.add(SessionCreator.day(lower, today));
            } else if (sport == null && SPORT_WORDS.containsKey(lower)) {
                sport = SPORT_WORDS.get(lower);
            } else {
                rest.add(word);
            }
        }
        return new Request(days, sport, String.join(" ", rest).replaceAll("^(?i)(la séance|la seance|séance|seance)\\s+", "").strip());
    }

    /** Handles {@code /deplacer <jour> <nouveau jour> [sport]}. */
    public void move(String text) {
        String body = text.replaceFirst("(?i)^/d[eé]placer(@\\S+)?\\s*", "").strip();
        LocalDate today = LocalDate.now(clock);
        Request r;
        try {
            r = parse(body, today);
        } catch (RuntimeException e) {
            r = new Request(List.of(), null, body);
        }
        if (r.days().size() < 2) {
            telegram.send(chatId, """
                    Dis-moi la séance et le nouveau jour :
                    /deplacer jeudi vendredi
                    /deplacer demain samedi vélo (quand il y en a deux ce jour-là)""");
            return;
        }
        LocalDate from = r.days().get(0);
        LocalDate to = r.days().get(1);
        if (to.isBefore(today)) {
            telegram.send(chatId, "Ce jour est passé.");
            return;
        }
        Found f = find(from, r.sport());
        if (f.message() != null) {
            telegram.send(chatId, f.message());
            return;
        }
        if (f.events().size() > 1) {
            telegram.sendWithButtonRows(chatId, "Il y a " + f.events().size() + " séances " + SessionCreator.DAY.format(from)
                            + ". Laquelle je déplace au " + SessionCreator.DAY.format(to) + " ?",
                    f.events().stream().map(e -> Map.of(e.name(), "mv:" + e.id() + ":" + from.toEpochDay() + ":" + to.toEpochDay())).toList());
            return;
        }
        moveNow(f.events().getFirst().id(), from, to);
    }

    /** Handles {@code /modifier <jour> [sport] <changement>}. */
    public void modify(String text) {
        String body = text.replaceFirst("(?i)^/modifier(@\\S+)?\\s*", "").strip();
        LocalDate today = LocalDate.now(clock);
        Request r;
        try {
            r = parse(body, today);
        } catch (RuntimeException e) {
            r = new Request(List.of(), null, body);
        }
        if (r.days().isEmpty() || r.rest().isBlank()) {
            telegram.send(chatId, """
                    Dis-moi la séance et ce que je change :
                    /modifier demain plus court, 45 min
                    /modifier jeudi en côte plutôt que sur le plat
                    /modifier samedi vélo plus facile, je suis cuit""");
            return;
        }
        LocalDate day = r.days().getFirst();
        Found f = find(day, r.sport());
        if (f.message() != null) {
            telegram.send(chatId, f.message());
            return;
        }
        if (f.events().size() > 1) {
            long id = ids.incrementAndGet();
            changes.put(id, r.rest());
            telegram.sendWithButtonRows(chatId, "Il y a " + f.events().size() + " séances " + SessionCreator.DAY.format(day) + ". Laquelle je modifie ?",
                    f.events().stream().map(e -> Map.of(e.name(), "md:" + id + ":" + e.id())).toList());
            return;
        }
        sessions.revise(f.events().getFirst(), r.rest());
    }

    /** The coach's workouts that day, or the reason there is nothing it may touch. */
    record Found(List<CalendarEvent> events, String message) {
    }

    private Found find(LocalDate day, String sport) {
        try {
            archive.syncEvents();
        } catch (RuntimeException e) {
            LOG.warn("refreshing the calendar cache failed, using the cache: {}", e.getMessage());
        }
        List<CalendarEvent> all = archive.workoutsOn(day);
        return choose(all, day, sport);
    }

    static Found choose(List<CalendarEvent> all, LocalDate day, String sport) {
        String when = SessionCreator.DAY.format(day);
        List<CalendarEvent> ofSport = sport == null ? all
                : all.stream().filter(e -> e.type() != null && SPORTS.get(sport).contains(e.type())).toList();
        if (ofSport.isEmpty()) {
            return new Found(List.of(), all.isEmpty() ? "Rien de prévu " + when + "."
                    : "Pas de séance de " + sport + " " + when + " (il y a " + names(all) + ").");
        }
        List<CalendarEvent> own = ofSport.stream().filter(CalendarEvent::ownedByRaceRadio).toList();
        if (own.isEmpty()) {
            return new Found(List.of(), names(ofSport) + (ofSport.size() > 1 ? " ne viennent" : " ne vient") + " pas de moi "
                    + "(Garmin, un autre outil ou toi) : je n'y touche pas. Change-la dans Intervals.icu, "
                    + "ou demande-moi une séance à la place avec /seance.");
        }
        return new Found(own, null);
    }

    private static String names(List<CalendarEvent> events) {
        return String.join(", ", events.stream().map(e -> "« " + e.name() + " »").toList());
    }

    private void moveNow(long eventId, LocalDate from, LocalDate to) {
        if (from.equals(to)) {
            telegram.send(chatId, "Elle est déjà " + SessionCreator.DAY.format(to) + ".");
            return;
        }
        if (to.isBefore(LocalDate.now(clock))) {
            telegram.send(chatId, "Ce jour est passé.");
            return;
        }
        try {
            var moved = writer.moveOwnEvent(eventId, to);
            if (moved.isEmpty()) {
                telegram.send(chatId, "Mode sec : rien n'a été déplacé dans Intervals.icu.");
                return;
            }
            try {
                archive.syncEvents();
            } catch (RuntimeException e) {
                LOG.warn("refreshing the calendar cache failed: {}", e.getMessage());
            }
            List<CalendarEvent> others = archive.workoutsOn(to).stream().filter(e -> e.id() != eventId).toList();
            telegram.sendWithButtonRows(chatId, "« " + moved.get().name() + " » déplacée " + SessionCreator.DAY.format(from)
                            + " → " + SessionCreator.DAY.format(to) + ", avec ses étapes et sa note. Ta montre suivra à la prochaine synchronisation."
                            + (others.isEmpty() ? "" : "\nCe jour-là il y a aussi " + names(others) + "."),
                    List.of(Map.of("Remettre " + SessionCreator.DAY.format(from), "mv:" + eventId + ":" + to.toEpochDay() + ":" + from.toEpochDay())));
        } catch (RuntimeException e) {
            telegram.send(chatId, "Je n'ai pas pu la déplacer : " + e.getMessage());
        }
    }

    /** True for the buttons this class put under its messages. */
    public boolean handles(String data) {
        return data.matches("mv:\\d+:\\d+:\\d+|md:\\d+:\\d+");
    }

    public void callback(String data) {
        String[] p = data.split(":");
        if (p[0].equals("mv")) {
            moveNow(Long.parseLong(p[1]), LocalDate.ofEpochDay(Long.parseLong(p[2])), LocalDate.ofEpochDay(Long.parseLong(p[3])));
            return;
        }
        String change = changes.remove(Long.parseLong(p[1]));
        long eventId = Long.parseLong(p[2]);
        if (change == null) {
            telegram.send(chatId, "Cette demande n'est plus en mémoire : redis-moi ce que je change.");
            return;
        }
        archive.workoutEvent(eventId)
                .ifPresentOrElse(e -> sessions.revise(e, change), () -> telegram.send(chatId, "Cette séance n'est plus au calendrier."));
    }
}
