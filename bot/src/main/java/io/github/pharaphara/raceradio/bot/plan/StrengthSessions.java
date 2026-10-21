package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.PromptFence;
import io.github.pharaphara.raceradio.bot.llm.Purpose;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.bot.strength.Exercises;
import io.github.pharaphara.raceradio.bot.strength.StrengthWorkout;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Strength sessions on demand, from the athlete's own equipment: {@code /seance demain renfo 30 min}.
 * The model composes them from the catalogue cut down to what the athlete owns; the program refuses
 * an exercise outside it, then publishes timed steps the watch beeps through, the order of the
 * exercises and their repetitions in the note, and checks the calendar read the right duration.
 * {@code /materiel} keeps what the athlete owns, in plain words.
 */
public final class StrengthSessions {

    private static final Logger LOG = LoggerFactory.getLogger(StrengthSessions.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern STRENGTH = Pattern.compile("\\b(renfo|renforcement|muscu|musculation|ppg|gainage)\\b");

    private record Pending(LocalDate day, StrengthWorkout workout, String note) {
    }

    private final CoachModel model;
    private final IntervalsWriter writer;
    private final Plans plans;
    private final TelegramClient telegram;
    private final long chatId;
    private final Function<LocalDate, String> context;
    private final Exercises catalogue = Exercises.catalogue();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /** @param context  what a coach looks at before prescribing, for a day: the same as for any workout */
    public StrengthSessions(CoachModel model, IntervalsWriter writer, Plans plans, TelegramClient telegram, long chatId,
                            Function<LocalDate, String> context) {
        this.model = model;
        this.writer = writer;
        this.plans = plans;
        this.telegram = telegram;
        this.chatId = chatId;
        this.context = context;
    }

    /** True for a request about strength rather than an endurance workout. */
    public static boolean asked(String request) {
        String plain = Normalizer.normalize(request, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return STRENGTH.matcher(plain).find();
    }

    /** Handles {@code /materiel ...}: shows or replaces what the athlete owns. */
    public String equipment(String text) {
        String rest = text.replaceFirst("(?i)^/mat[ée]riel(@\\S+)?\\s*", "").strip();
        if (rest.isEmpty()) {
            Set<String> owned = Exercises.equipmentIn(plans.equipment());
            return "Ton matériel : " + Exercises.french(owned) + (plans.equipment().isBlank() ? "" : " (« " + plans.equipment() + " »)")
                    + ".\nPour le changer : /materiel haltères 2 × 10 kg, kettlebell 16 kg, élastiques, barre de traction";
        }
        plans.equipment(rest);
        Set<String> owned = Exercises.equipmentIn(rest);
        return "C'est noté : " + Exercises.french(owned) + ", en plus du poids du corps. Les séances de renfo s'en serviront : "
                + "/seance demain renfo 30 min";
    }

    /** Drafts a strength session for the day and shows it with Publier and Annuler. */
    public void draft(LocalDate day, String request) {
        Set<String> owned = Exercises.equipmentIn(plans.equipment());
        try {
            JsonNode root = ask(prompt(day, request, owned, null, null));
            StrengthWorkout w = StrengthWorkout.of(root.path("workout"));
            List<String> problems = w.problems(catalogue, owned);
            if (!problems.isEmpty()) {
                root = ask(prompt(day, request, owned, root.toString(), "Fix these and keep the rest: " + String.join("; ", problems)));
                w = StrengthWorkout.of(root.path("workout"));
                problems = w.problems(catalogue, owned);
                if (!problems.isEmpty()) {
                    LOG.warn("strength draft refused: {}", problems);
                    telegram.send(chatId, "Je n'arrive pas à composer cette séance avec ton matériel (" + Exercises.french(owned)
                            + "). Reformule, ou complète ton matériel avec /materiel.");
                    return;
                }
            }
            String note = oneLine(root.path("note").asString(""));
            long id = ids.incrementAndGet();
            pending.put(id, new Pending(day, w, note));
            telegram.sendWithButtonRows(chatId, preview(day, w, root.path("why").asString(""), note),
                    List.of(Map.of("Publier", "spub:" + id), Map.of("Annuler", "sdrop:" + id)));
        } catch (CoachModel.BudgetExhausted e) {
            telegram.send(chatId, "Le budget du modèle est atteint ce mois-ci : je ne peux pas préparer de séance.");
        } catch (RuntimeException e) {
            LOG.warn("drafting a strength session failed: {}", e.toString());
            telegram.send(chatId, "Je n'ai pas réussi à préparer cette séance de renfo. Reformule-la plus simplement.");
        }
    }

    private JsonNode ask(String prompt) {
        ModelReply r = model.ask(Purpose.SESSION, "low", 6_000, prompt);
        if (r.refused() || r.text().isBlank()) {
            throw new IllegalStateException("no answer");
        }
        return JSON.readTree(SessionCreator.json(r.text()));
    }

    String preview(LocalDate day, StrengthWorkout w, String why, String note) {
        StringBuilder b = new StringBuilder("« ").append(w.name()).append(" » · renforcement · ")
                .append(minutes(w.seconds())).append(" · ")
                .append(SessionCreator.DAY.format(day)).append('\n');
        if (!why.isBlank()) {
            b.append(why).append('\n');
        }
        b.append('\n');
        w.lines(catalogue).forEach(l -> b.append(l).append('\n'));
        if (!note.isBlank()) {
            b.append("\nNote : ").append(note).append('\n');
        }
        return b.append("\nTa montre minutera chaque effort et chaque récup ; l'ordre des exercices est dans la note.\nÇa te va ?").toString();
    }

    private static String minutes(int seconds) {
        return Math.round(seconds / 60.0) + " min";
    }

    public boolean handles(String data) {
        return data.matches("(spub|sdrop):\\d+");
    }

    public void callback(String data) {
        String[] p = data.split(":", 2);
        Pending d = pending.remove(Long.parseLong(p[1]));
        if (d == null) {
            telegram.send(chatId, "Ce brouillon n'existe plus. Redemande-le.");
            return;
        }
        if (p[0].equals("sdrop")) {
            telegram.send(chatId, "Abandonné, rien n'a été publié.");
            return;
        }
        publish(d);
    }

    /** The note, then the order of the exercises, then the timed steps; the duration read back is checked. */
    private void publish(Pending d) {
        String description = (d.note().isBlank() ? "" : d.note() + " ") + "Ordre : " + String.join(" / ", d.workout().lines(catalogue))
                + "\n\n" + d.workout().steps();
        try {
            Optional<CalendarEvent> e = writer.publishText(d.day(), "WeightTraining", d.workout().name(), description);
            if (e.isEmpty()) {
                telegram.send(chatId, "Mode sec : rien n'a été publié dans Intervals.icu.");
                return;
            }
            int read = e.get().workoutDoc() == null ? 0 : e.get().workoutDoc().path("duration").asInt(0);
            if (read != d.workout().seconds()) {
                writer.deleteOwnEvent(e.get().id());
                telegram.send(chatId, "Intervals.icu a mal compris la séance (" + read / 60 + " min au lieu de "
                        + d.workout().seconds() / 60 + ") : je l'ai retirée.");
                return;
            }
            telegram.sendWithButtonRows(chatId, "« " + d.workout().name() + " » publiée pour " + SessionCreator.DAY.format(d.day())
                    + ". Ta montre minutera les efforts et les récups ; l'ordre des exercices est dans la note.",
                    List.of(Map.of("Retirer du calendrier", "del:" + e.get().id())));
        } catch (RuntimeException ex) {
            telegram.send(chatId, "La publication a échoué : " + ex.getMessage());
        }
    }

    private static String oneLine(String note) {
        return note.replaceAll("\\s+", " ").strip().replaceFirst("^[-•*\\s]+", "");
    }

    String prompt(LocalDate day, String request, Set<String> owned, String previous, String change) {
        StringBuilder list = new StringBuilder();
        for (Exercises.Exercise e : catalogue.doable(owned)) {
            list.append(e.key()).append(": ").append(e.name()).append(" (").append(e.zone()).append(")\n");
        }
        String base = """
                Task: prescribe one strength session for %s for an endurance athlete, as a coach who takes the
                initiative. Answer with JSON and nothing else:
                {"why": "one or two French sentences: the choice and what it rests on",
                 "note": "two to four French sentences: what it is for in plain words, how to do it (form before
                          load, a controlled tempo, breathing), how to tell it went right, a target effort out of 10",
                 "workout": {"name": "readable French title with the key numbers, e.g. \\"Renfo course · 30 min\\"",
                   "blocks": [{"title": "Échauffement", "rounds": 1,
                               "items": [{"exercise": "mobilite_hanches", "seconds": 300, "reps": null}]},
                              {"title": "Circuit", "rounds": 3,
                               "items": [{"exercise": "squat_gobelet", "seconds": 40, "reps": 12},
                                         {"exercise": null, "seconds": 20, "reps": null}]}]}}
                Rules, checked by the program:
                - Only exercises of the list below, by their key: they are what the athlete can do with their
                  equipment. A rest is an item whose exercise is null.
                - Every item is timed, because the watch beeps through them: work 20 to 60 s, rest 10 to 30 s
                  between exercises and 60 to 120 s at the end of a round. Give reps as a guide when the exercise
                  is counted, else null.
                - A warm-up of 5 to 8 minutes first, a cool-down of 3 to 5 minutes last, 1 to 6 rounds per block.
                - Duration: what the athlete asked, else 25 to 35 minutes.
                - For endurance: running wants hips, glutes, single-leg strength, calves and the trunk; cycling
                  wants the posterior chain, glutes, the trunk and hips; swimming wants the back, shoulder
                  stability and the trunk. Serve the athlete's sports.
                - Calibrate on the context: a red morning means mobility and light trunk work only; a hard session
                  in the last 24 hours, or a long or hard one tomorrow, means no heavy or jumping leg work. Say so
                  in "why".
                - Never an explanation the knowledge base does not hold.
                Exercises, key: name (zone):
                %s""".formatted(day + " (" + SessionCreator.DAY.format(day) + ")", list);
        String out = base + "\n" + PromptFence.data("race-radio.context", context.apply(day)) + "\n" + PromptFence.data("request", request);
        if (previous != null) {
            out += "\nYou proposed this:\n" + PromptFence.data("race-radio.previous-draft", previous)
                    + "\nApply this change, keep the rest:\n" + PromptFence.data("change", change);
        }
        return out;
    }
}
