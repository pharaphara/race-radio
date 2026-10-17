package io.github.pharaphara.raceradio.bot.morning;

import io.github.pharaphara.raceradio.core.readiness.Readiness;
import io.github.pharaphara.raceradio.core.readiness.Signal;
import io.github.pharaphara.raceradio.core.readiness.Verdict;

import java.util.Locale;

/**
 * French wording that does not need the language model: the verdict line, each signal from its
 * raw values, each note. It is what the athlete reads when the model is unavailable, and what
 * /pourquoi shows, so it must cover every signal and every note the readiness model can produce.
 */
public final class Wording {

    private Wording() {
    }

    public static String verdictLine(Verdict v) {
        return switch (v) {
            case GREEN -> "🟢 Entraînement prévu";
            case YELLOW -> "🟡 Version allégée";
            case RED -> "🔴 Repos ou récupération active";
        };
    }

    public static String signal(Signal s) {
        String strength = switch (s.severity()) {
            case STRONG -> "net";
            case MILD -> "léger";
        };
        String what = switch (s.kind()) {
            case HRV_TODAY -> f("VFC de %s ms ce matin, pour %s habituellement", s.value(), s.reference());
            case HRV_TREND -> f("VFC moyenne de %s ms sur les sept derniers matins, sous ta zone habituelle (%s)", s.value(), s.reference());
            case RESTING_HEART_RATE -> f("FC de repos à %s, pour %s habituellement", s.value(), s.reference());
            case SLEEP -> sleep(s);
            case FORM -> f("forme à %s : la fatigue dépasse largement la forme de fond", s.value(), null);
            case RECENT_LOAD -> f("charge de %s sur les trois derniers jours, lourde pour une forme de fond de %s", s.value(), s.reference());
            case FEEL -> s.value() == null ? "ton ressenti est bas"
                    : f("tu te sens bas sur %s points sur cinq", s.value(), null);
        };
        String registry = s.registry() == Signal.Registry.CONVENTION ? "repère de coach" : "statistique sur tes données";
        return what + " (signal " + strength + ", " + registry + ")";
    }

    /** Translates the notes the readiness model emits; unknown ones are flagged rather than hidden. */
    public static String note(String english) {
        if (english.startsWith("A single reading is off")) {
            return "Un seul indicateur s'écarte : à lui seul, il ne change pas ta journée.";
        }
        if (english.startsWith("The numbers look fine but you report feeling low")) {
            return "Les chiffres sont bons mais tu te sens bas : quand les deux divergent, le coach suit ton ressenti.";
        }
        if (english.startsWith("The numbers suggested easing off, but you report feeling good")) {
            return "Les chiffres invitaient à lever le pied, mais tu te sens bien : le coach suit ton ressenti.";
        }
        if (english.startsWith("You report feeling good, but several readings point the same way")) {
            return "Tu te sens bien, mais plusieurs indicateurs vont dans le même sens. Une maladie peut passer inaperçue le premier matin : la journée reste calme.";
        }
        if (english.startsWith("Resting heart rate") && english.contains("unusually low")) {
            return "FC de repos inhabituellement basse. Certains coachs y voient parfois l'annonce d'une maladie ; à elle seule, elle ne signifie rien.";
        }
        return "(note non traduite) " + english;
    }

    /** Translates what the readiness model could not judge for lack of data. */
    public static String missing(String english) {
        if (english.equals("heart rate variability this morning")) {
            return "la VFC de ce matin";
        }
        if (english.startsWith("a baseline for heart rate variability")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("needs (\\d+) days, has (\\d+)").matcher(english);
            return m.find() ? "une base de VFC (" + m.group(1) + " jours nécessaires, " + m.group(2) + " disponibles)" : "une base de VFC";
        }
        if (english.equals("resting heart rate this morning")) {
            return "la FC de repos de ce matin";
        }
        if (english.equals("a baseline for resting heart rate")) {
            return "une base de FC de repos";
        }
        if (english.equals("last night's sleep")) {
            return "le sommeil de la nuit";
        }
        return english;
    }

    public static String deterministic(Readiness r) {
        StringBuilder b = new StringBuilder(verdictLine(r.verdict()));
        if (r.signals().isEmpty()) {
            b.append("\nRien à signaler ce matin.");
        }
        for (Signal s : r.signals()) {
            b.append("\n• ").append(signal(s));
        }
        for (String n : r.notes()) {
            b.append("\n").append(note(n));
        }
        if (!r.missing().isEmpty()) {
            b.append("\nDonnées manquantes : ").append(String.join(", ", r.missing().stream().map(Wording::missing).toList())).append('.');
        }
        return b.toString();
    }

    private static String sleep(Signal s) {
        String h = s.value() == null ? null : String.format(Locale.FRANCE, "%dh%02d", (int) Math.floor(s.value()), (int) Math.round((s.value() % 1) * 60));
        if (h != null && s.reference() != null) {
            return "nuit de " + h + ", score de sommeil " + Math.round(s.reference());
        }
        return h != null ? "nuit de " + h : "score de sommeil " + Math.round(s.reference());
    }

    private static String f(String pattern, Double a, Double b) {
        return String.format(Locale.FRANCE, pattern, a == null ? "?" : Math.round(a), b == null ? "?" : Math.round(b));
    }
}
