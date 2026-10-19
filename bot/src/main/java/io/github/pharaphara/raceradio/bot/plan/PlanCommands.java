package io.github.pharaphara.raceradio.bot.plan;

import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.core.plan.Objective;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** /objectif, /dispo, /sports, /mode and /plan, in French. */
public final class PlanCommands {

    static final Map<String, DayOfWeek> DAYS = Map.of("lun", DayOfWeek.MONDAY, "mar", DayOfWeek.TUESDAY, "mer", DayOfWeek.WEDNESDAY,
            "jeu", DayOfWeek.THURSDAY, "ven", DayOfWeek.FRIDAY, "sam", DayOfWeek.SATURDAY, "dim", DayOfWeek.SUNDAY);
    static final Map<String, String> SPORTS = Map.of("natation", "Swim", "velo", "Ride", "vélo", "Ride", "course", "Run");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*h\\s*(\\d+)?|(\\d+)\\s*(?:min|mn|m)?");

    private final Plans plans;
    private final Planner planner;
    private final Clock clock;

    public PlanCommands(Plans plans, Planner planner, Clock clock) {
        this.plans = plans;
        this.planner = planner;
        this.clock = clock;
    }

    public String objective(String text) {
        String rest = text.replaceFirst("(?i)^/objectif(@\\S+)?\\s*", "").strip();
        String lower = rest.toLowerCase(Locale.ROOT);
        if (rest.isEmpty()) {
            var all = plans.objectives();
            if (all.isEmpty()) {
                return "Aucun objectif. Exemples :\n/objectif course 2027-05-23 A triathlon M < 2h40\n/objectif volume 6 h par semaine\n/objectif forme rester en forme cet hiver";
            }
            StringBuilder b = new StringBuilder("Tes objectifs :");
            for (var o : all) {
                b.append("\n").append(o.id()).append(". ").append(describe(o.objective()));
            }
            return b.append("\nRetirer : /objectif retirer <n>").toString();
        }
        if (lower.startsWith("retirer")) {
            Matcher m = Pattern.compile("(\\d+)").matcher(rest);
            return m.find() && plans.withdrawObjective(Long.parseLong(m.group(1))) ? "Objectif retiré." : "Numéro introuvable (voir /objectif).";
        }
        if (lower.startsWith("course")) {
            Matcher m = Pattern.compile("(?i)course\\s+(\\d{4}-\\d{2}-\\d{2})\\s*([abc])?\\s*(.*)").matcher(rest);
            if (!m.find()) {
                return "Format : /objectif course AAAA-MM-JJ [A|B|C] description. Exemple : /objectif course 2027-05-23 A triathlon M < 2h40";
            }
            LocalDate date;
            try {
                date = LocalDate.parse(m.group(1));
            } catch (DateTimeParseException e) {
                return "Date illisible : " + m.group(1);
            }
            if (!date.isAfter(LocalDate.now(clock))) {
                return "Cette date est passée.";
            }
            Objective.Priority p = m.group(2) == null ? Objective.Priority.A : Objective.Priority.valueOf(m.group(2).toUpperCase(Locale.ROOT));
            String what = m.group(3).isBlank() ? "course" : m.group(3).strip();
            long id = plans.addObjective(new Objective(Objective.Kind.RACE, sportOf(what), date, p, what), clock.instant());
            return "Objectif " + id + " enregistré : " + describe(new Objective(Objective.Kind.RACE, sportOf(what), date, p, what))
                    + "\nVois ce que ça donne avec /plan.";
        }
        if (lower.startsWith("volume") || lower.startsWith("forme")) {
            boolean volume = lower.startsWith("volume");
            String what = rest.substring(volume ? 6 : 5).strip();
            Objective o = new Objective(volume ? Objective.Kind.VOLUME : Objective.Kind.FITNESS, "Any", null, Objective.Priority.A,
                    what.isEmpty() ? (volume ? "volume régulier" : "garder la forme") : what);
            long id = plans.addObjective(o, clock.instant());
            return "Objectif " + id + " enregistré : " + describe(o);
        }
        return "Je ne comprends pas. Exemples : /objectif course 2027-05-23 A triathlon M · /objectif volume 6 h · /objectif forme";
    }

    public String availability(String text) {
        String rest = text.replaceFirst("(?i)^/dispo(@\\S+)?\\s*", "").strip().toLowerCase(Locale.ROOT);
        if (rest.isEmpty()) {
            Map<DayOfWeek, Integer> a = plans.availability();
            if (a.isEmpty()) {
                return "Pas de disponibilités. Exemple : /dispo lun 1h, mer 45min, jeu 1h, sam 2h, dim 1h30";
            }
            StringBuilder b = new StringBuilder("Tes disponibilités :");
            a.forEach((d, m) -> b.append("\n• ").append(d.getDisplayName(java.time.format.TextStyle.FULL, Locale.FRANCE)).append(" ").append(m).append(" min"));
            return b.toString();
        }
        Map<DayOfWeek, Integer> parsed = new EnumMap<>(DayOfWeek.class);
        Matcher m = Pattern.compile("(lun|mar|mer|jeu|ven|sam|dim)\\w*\\s+([0-9hmin ]+)").matcher(rest);
        while (m.find()) {
            Integer minutes = minutes(m.group(2).strip());
            if (minutes != null && minutes > 0) {
                parsed.put(DAYS.get(m.group(1)), minutes);
            }
        }
        if (parsed.isEmpty()) {
            return "Je n'ai rien compris. Exemple : /dispo lun 1h, mer 45min, sam 2h";
        }
        plans.availability(parsed);
        return availability("/dispo") + "\nVois la semaine qui en sort avec /plan.";
    }

    public String sports(String text) {
        String rest = text.replaceFirst("(?i)^/sports(@\\S+)?\\s*", "").strip().toLowerCase(Locale.ROOT);
        Set<String> active = new LinkedHashSet<>(plans.sports());
        Matcher m = Pattern.compile("(natation|v[ée]lo|course)\\s+(on|off|oui|non)").matcher(rest);
        boolean changed = false;
        while (m.find()) {
            String sport = SPORTS.get(m.group(1));
            if (m.group(2).equals("on") || m.group(2).equals("oui")) {
                active.add(sport);
            } else {
                active.remove(sport);
            }
            changed = true;
        }
        if (changed) {
            plans.sports(active);
        }
        return "Sports en saison : " + String.join(", ", active.stream().map(PlanCommands::french).toList())
                + (changed ? "" : "\nExemple : /sports natation off");
    }

    public String mode(String text) {
        String rest = text.replaceFirst("(?i)^/mode(@\\S+)?\\s*", "").strip().toLowerCase(Locale.ROOT);
        Plans.Mode m = switch (rest) {
            case "demande", "à la demande", "a la demande", "planifier", "ajuster" -> Plans.Mode.ON_DEMAND;
            case "analyser", "off" -> Plans.Mode.ANALYSE;
            default -> null;
        };
        if (m != null) {
            plans.mode(m);
        }
        Plans.Mode now = plans.mode();
        return "Mode : " + switch (now) {
            case ON_DEMAND -> "à la demande (je crée une séance avec /seance ou une semaine avec /semaine quand tu le demandes, "
                    + "et j'allège mes propres séances un matin orange ou rouge)";
            case ANALYSE -> "analyser (je ne touche pas au calendrier : je commente ce qu'un autre outil, comme Garmin Coach, a planifié)";
        } + (m == null ? "\nChanger : /mode demande ou /mode analyser" : "");
    }

    /** Where the athlete stands against the objective; the week itself is made with /semaine. */
    public String plan() {
        return planner.feasibility().map(planner::describe)
                .orElse("Pas d'objectif de course enregistré : /objectif course AAAA-MM-JJ A description.")
                + "\n\nPour programmer : /semaine (la semaine prochaine) ou /seance (une séance).";
    }

    static Integer minutes(String s) {
        Matcher m = Pattern.compile("^(\\d+)\\s*h\\s*(\\d+)?").matcher(s);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) * 60 + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
        }
        Matcher n = Pattern.compile("^(\\d+)").matcher(s);
        return n.find() ? Integer.parseInt(n.group(1)) : null;
    }

    static String sportOf(String what) {
        String w = what.toLowerCase(Locale.ROOT);
        if (w.contains("tri")) {
            return "Triathlon";
        }
        if (w.contains("trail") || w.contains("km") || w.contains("marathon") || w.contains("course")) {
            return "Run";
        }
        if (w.contains("vélo") || w.contains("velo") || w.contains("cyclo")) {
            return "Ride";
        }
        return "Any";
    }

    private static String describe(Objective o) {
        return switch (o.kind()) {
            case RACE -> o.target() + ", le " + o.date() + ", priorité " + o.priority();
            case VOLUME -> "volume : " + o.target();
            case FITNESS -> "forme : " + o.target();
        };
    }

    private static String french(String sport) {
        return switch (sport) {
            case "Swim" -> "natation";
            case "Ride" -> "vélo";
            case "Run" -> "course";
            default -> sport;
        };
    }
}
