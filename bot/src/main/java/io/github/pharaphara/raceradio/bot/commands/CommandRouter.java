package io.github.pharaphara.raceradio.bot.commands;

import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import io.github.pharaphara.raceradio.bot.morning.Wording;
import io.github.pharaphara.raceradio.bot.store.DecisionLog;
import io.github.pharaphara.raceradio.bot.store.FeelStore;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.core.readiness.Feel;
import io.github.pharaphara.raceradio.core.readiness.Readiness;
import io.github.pharaphara.raceradio.core.readiness.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns each message from the athlete into an answer. */
public final class CommandRouter {

    static final String HELP = """
            Commandes :
            /forme · le verdict calculé maintenant, sans rédaction
            /aujourdhui · le message du matin, rédigé
            /pourquoi · le détail de la dernière décision
            /ressenti 4 3 4 4 5 · sommeil, énergie, courbatures, humeur, appétit, de 1 (mauvais) à 5 (bon)
            /objectif · tes objectifs (course datée, volume ou forme)
            /dispo lun 1h, mer 45min, sam 2h · tes créneaux
            /sports natation off · les sports de la saison
            /mode planifier · ajuster · analyser
            /plan · la semaine prochaine et la faisabilité
            /seance demain vélo 3x10min à 280W · une séance à la demande, montrée avant publication
            /fusionner i123 i124 · recoller une séance coupée en deux (avec confirmation)
            /note j'ai mal au mollet gauche · un fait que le coach doit garder en tête
            /notes · les faits gardés · /oubli 3 · en retirer un
            /tendance vélo efficacité 12 mois · une mesure dans le temps (/tendance seul : la liste)
            /segment · tes segments habituels · /segment jonte · l'historique d'un segment
            /cout · les appels au modèle ce mois-ci
            /dernierenvoi · ce qui a été envoyé au modèle la dernière fois
            /aide · cette liste
            Une photo du tableau Crossfit : je la lis et l'ajoute à ta séance du jour.
            Et sinon, écris-moi simplement : je réponds avec tes données du jour.""";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern DATA_SOURCE = Pattern.compile("<data source=\"([^\"]+)\"");

    private final TelegramClient telegram;
    private final long chatId;
    private final MorningCheck morning;
    private final FeelStore feels;
    private final DecisionLog decisions;
    private final Ledger ledger;
    private final Journal journal;
    private final CoachModel model;
    private final Clock clock;
    private final ZoneId zone;
    private final boolean dryRun;
    private final io.github.pharaphara.raceradio.bot.chat.Conversation conversation;
    private final io.github.pharaphara.raceradio.bot.store.Conversations memory;
    private final io.github.pharaphara.raceradio.bot.strength.BoardReader boards;
    private final io.github.pharaphara.raceradio.bot.plan.PlanCommands planCommands;
    private final io.github.pharaphara.raceradio.bot.plan.Adapter adapter;
    private final io.github.pharaphara.raceradio.bot.merge.Merger merger;
    private final io.github.pharaphara.raceradio.bot.plan.SessionCreator sessions;
    private final io.github.pharaphara.raceradio.bot.archive.Trends trends;
    private final io.github.pharaphara.raceradio.bot.strava.Segments segments;

    public CommandRouter(TelegramClient telegram, long chatId, MorningCheck morning, FeelStore feels, DecisionLog decisions,
                         Ledger ledger, Journal journal, CoachModel model, Clock clock, ZoneId zone, boolean dryRun,
                         io.github.pharaphara.raceradio.bot.chat.Conversation conversation,
                         io.github.pharaphara.raceradio.bot.store.Conversations memory,
                         io.github.pharaphara.raceradio.bot.strength.BoardReader boards,
                         io.github.pharaphara.raceradio.bot.plan.PlanCommands planCommands,
                         io.github.pharaphara.raceradio.bot.plan.Adapter adapter,
                         io.github.pharaphara.raceradio.bot.merge.Merger merger,
                         io.github.pharaphara.raceradio.bot.plan.SessionCreator sessions,
                         io.github.pharaphara.raceradio.bot.archive.Trends trends,
                         io.github.pharaphara.raceradio.bot.strava.Segments segments) {
        this.segments = segments;
        this.trends = trends;
        this.sessions = sessions;
        this.merger = merger;
        this.adapter = adapter;
        this.boards = boards;
        this.planCommands = planCommands;
        this.conversation = conversation;
        this.memory = memory;
        this.telegram = telegram;
        this.chatId = chatId;
        this.morning = morning;
        this.feels = feels;
        this.decisions = decisions;
        this.ledger = ledger;
        this.journal = journal;
        this.model = model;
        this.clock = clock;
        this.zone = zone;
        this.dryRun = dryRun;
    }

    public void handle(TelegramClient.Update u) {
        if (u.isCallback()) {
            String data = u.callbackData() == null ? "" : u.callbackData();
            if (sessions.handles(data)) {
                telegram.acknowledge(u.callbackId(), "Reçu");
                sessions.callback(data);
                return;
            }
            if (data.startsWith("merge:")) {
                String[] ids = data.substring(6).split(":");
                String answer;
                try {
                    answer = merger.merge(ids[0], ids[1]);
                } catch (RuntimeException e) {
                    answer = "La fusion a échoué : " + e.getMessage();
                }
                telegram.acknowledge(u.callbackId(), "Fusion");
                telegram.send(chatId, answer);
                return;
            }
            if (data.startsWith("undo:")) {
                String answer;
                try {
                    answer = adapter.undo(Long.parseLong(data.substring(5)));
                } catch (RuntimeException e) {
                    answer = "Je n'ai pas pu annuler : " + e.getMessage();
                }
                telegram.acknowledge(u.callbackId(), "Annulation");
                telegram.send(chatId, answer);
            } else {
                telegram.acknowledge(u.callbackId(), "Reçu");
            }
            return;
        }
        if (u.hasPhoto()) {
            String answer;
            try {
                answer = boards.read(telegram.download(u.photoFileId()), u.caption());
            } catch (CoachModel.BudgetExhausted e) {
                answer = "Le budget du modèle est atteint ce mois-ci : je ne peux pas lire la photo.";
            } catch (RuntimeException e) {
                answer = "Je n'ai pas pu lire la photo : " + e.getMessage();
            }
            telegram.send(chatId, answer);
            return;
        }
        String text = u.text() == null ? "" : u.text().strip();
        String command = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        if (command.startsWith("/")) {
            sessions.stopAmending();
        }
        String answer = switch (command) {
            case "/start", "/aide", "/help" -> HELP + (dryRun ? "\n\nMode sec : rien n'est écrit dans Intervals.icu." : "");
            case "/forme" -> form();
            case "/aujourdhui" -> morning.run(false).message();
            case "/pourquoi" -> why();
            case "/ressenti" -> feel(text);
            case "/cout" -> cost();
            case "/dernierenvoi" -> lastSent();
            case "/objectif" -> planCommands.objective(text);
            case "/dispo" -> planCommands.availability(text);
            case "/sports" -> planCommands.sports(text);
            case "/mode" -> planCommands.mode(text);
            case "/plan" -> planCommands.plan();
            case "/seance", "/séance" -> {
                sessions.draft(text);
                yield null;
            }
            case "/fusionner" -> {
                merge(text);
                yield null;
            }
            case "/segment", "/segments" -> {
                segments.handle(text);
                yield null;
            }
            case "/tendance", "/tendances" -> {
                trends.handle(text);
                yield null;
            }
            case "/note" -> note(text);
            case "/notes" -> notes();
            case "/oubli" -> forget(text);
            default -> {
                if (command.startsWith("/")) {
                    yield "Commande inconnue.\n\n" + HELP;
                }
                if (sessions.amending()) {
                    // The athlete pressed Modifier on a draft: this message is the change.
                    sessions.amend(text);
                    yield null;
                }
                yield conversation.answer(text);
            }
        };
        if (answer == null) {
            return;
        }
        telegram.send(chatId, answer);
        if (command.equals("/forme")) {
            try {
                byte[] chart = morning.fitnessChart();
                if (chart != null) {
                    telegram.sendPhoto(chatId, chart, null);
                }
            } catch (RuntimeException e) {
                telegram.send(chatId, "Le graphique n'a pas pu être dessiné.");
            }
        }
    }

    String form() {
        Readiness r = morning.assess();
        return Wording.deterministic(r) + "\n\nScore " + r.score() + "/100 · tempérament " + r.temperament().name().toLowerCase(Locale.ROOT);
    }

    /** Shows the preview, with a confirmation button only when the merge is possible. */
    void merge(String text) {
        Matcher m = Pattern.compile("(i?\\d+)\\s+(i?\\d+)").matcher(text);
        if (!m.find()) {
            telegram.send(chatId, "Donne les deux numéros d'activité, tels qu'Intervals.icu les affiche : /fusionner i123 i124");
            return;
        }
        String a = m.group(1).startsWith("i") ? m.group(1) : "i" + m.group(1);
        String b = m.group(2).startsWith("i") ? m.group(2) : "i" + m.group(2);
        try {
            var p = merger.preview(a, b);
            if (p.possible()) {
                telegram.sendWithButtons(chatId, p.text(), Map.of("Confirmer la fusion", "merge:" + p.first().id() + ":" + p.second().id()));
            } else {
                telegram.send(chatId, p.text());
            }
        } catch (RuntimeException e) {
            telegram.send(chatId, "Je n'ai pas pu lire ces activités : " + e.getMessage());
        }
    }

    String note(String text) {
        String fact = text.replaceFirst("(?i)^/note(@\\S+)?\\s*", "").strip();
        if (fact.isEmpty()) {
            return "Écris le fait après /note, par exemple : /note genou droit sensible en descente";
        }
        long id = memory.addNote(clock.instant(), fact);
        return "Noté (n° " + id + ") : je le garde en tête jusqu'à ce que tu le retires avec /oubli " + id + ".";
    }

    String notes() {
        var all = memory.activeNotes();
        if (all.isEmpty()) {
            return "Aucun fait gardé. Ajoute-en avec /note.";
        }
        StringBuilder b = new StringBuilder("Ce que je garde en tête :");
        for (var n : all) {
            b.append("\n").append(n.id()).append(". ").append(n.text()).append(" (").append(n.at(), 0, 10).append(')');
        }
        return b.toString();
    }

    String forget(String text) {
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        if (!m.find()) {
            return "Donne le numéro du fait à retirer, par exemple : /oubli 3 (voir /notes).";
        }
        return memory.withdraw(Long.parseLong(m.group(1))) ? "Retiré." : "Je ne trouve pas ce numéro parmi les faits gardés.";
    }

    String why() {
        return decisions.latest().map(d -> {
            StringBuilder b = new StringBuilder("Dernière décision : " + d.kind().toLowerCase(Locale.ROOT) + " du " + d.day());
            JsonNode r = JSON.readTree(d.evidenceJson());
            for (JsonNode s : r.path("signals")) {
                Signal sig = JSON.convertValue(s, Signal.class);
                b.append("\n• ").append(Wording.signal(sig));
            }
            for (JsonNode n : r.path("notes")) {
                b.append("\n").append(Wording.note(n.asString()));
            }
            if (r.path("signals").isEmpty()) {
                b.append("\nAucun signal : rien ne justifiait de lever le pied.");
            }
            b.append("\n\nModèle de décision : ").append(d.model());
            b.append("\nVersion des consignes du coach : ").append(d.promptVersion());
            return b.toString();
        }).orElse("Aucune décision enregistrée pour l'instant.");
    }

    String feel(String text) {
        Matcher m = Pattern.compile("([1-5])\\D+([1-5])\\D+([1-5])\\D+([1-5])\\D+([1-5])").matcher(text);
        if (!m.find()) {
            return "Donne cinq notes de 1 (mauvais) à 5 (bon) : sommeil, énergie, courbatures, humeur, appétit.\nExemple : /ressenti 4 3 4 4 5";
        }
        Feel f = new Feel(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        feels.save(LocalDate.now(clock), f, clock.instant());
        return "Noté pour aujourd'hui : sommeil " + f.sleep() + ", énergie " + f.energy() + ", courbatures " + f.soreness()
                + ", humeur " + f.mood() + ", appétit " + f.appetite() + "." + (f.lowDimensions() >= 2
                ? "\nDeux points bas ou plus : j'en tiens compte pour la journée." : "");
    }

    String cost() {
        YearMonth month = YearMonth.now(clock.withZone(zone));
        var totals = ledger.totals(month, zone);
        BigDecimal spent = model.spentThisMonth();
        boolean subscription = "subscription".equals(model.billing());
        StringBuilder b = new StringBuilder("Modèle, " + month + " :");
        if (totals.isEmpty()) {
            b.append("\nAucun appel ce mois-ci.");
        }
        long cacheRead = 0;
        long input = 0;
        for (Ledger.PurposeTotal t : totals) {
            b.append(String.format(Locale.FRANCE, "%n• %s : %d appel(s), %s $", purpose(t.purpose()), t.calls(), dollars(t.cost())));
            cacheRead += t.cacheRead();
            input += t.input() + t.cacheWrite() + t.cacheRead();
        }
        b.append(String.format(Locale.FRANCE, "%nTotal %s $ sur un plafond de %s $", dollars(spent), dollars(model.budget())));
        if (input > 0) {
            b.append(String.format(Locale.FRANCE, "%nLu en cache : %d %% de l'entrée", Math.round(100.0 * cacheRead / input)));
        }
        b.append(subscription
                ? "\nAbonnement Claude : rien n'est facturé à l'appel ; montants au prix catalogue de l'API, pour protéger tes quotas."
                : "\nAPI : montants facturés, au prix configuré.");
        return b.toString();
    }

    String lastSent() {
        return journal.last().map(line -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = (Map<String, Object>) line.get("request");
            @SuppressWarnings("unchecked")
            Map<String, Object> reply = (Map<String, Object>) line.get("reply");
            String content = String.valueOf(req.get("userContent"));
            StringBuilder sources = new StringBuilder();
            Matcher m = DATA_SOURCE.matcher(content);
            while (m.find()) {
                sources.append(sources.isEmpty() ? "" : ", ").append(m.group(1));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = reply == null ? Map.of() : (Map<String, Object>) reply.get("usage");
            return "Dernier envoi au modèle (" + line.get("at") + ")"
                    + "\n• but : " + purpose(String.valueOf(req.get("purpose")))
                    + "\n• modèle : " + req.get("model") + ", effort " + req.get("effort")
                    + "\n• consignes du coach : version " + req.get("promptVersion")
                    + "\n• message : " + content.length() + " caractères"
                    + "\n• données jointes : " + (sources.isEmpty() ? "aucune" : sources)
                    + "\n• jetons : entrée " + usage.get("input") + ", sortie " + usage.get("output")
                    + ", cache écrit " + usage.get("cacheWrite") + ", cache lu " + usage.get("cacheRead")
                    + (line.get("error") != null ? "\n• erreur : " + line.get("error") : "")
                    + "\nLe contenu intégral est dans le journal, sur le serveur.";
        }).orElse("Aucun envoi au modèle pour l'instant.");
    }

    private static String purpose(String p) {
        return switch (p) {
            case "MORNING" -> "message du matin";
            case "REVIEW" -> "analyse de séance";
            case "CHAT" -> "conversation";
            case "PHOTO" -> "lecture de photo";
            case "SESSION" -> "séance à la demande";
            default -> p;
        };
    }

    private static String dollars(BigDecimal d) {
        return d.setScale(d.compareTo(BigDecimal.ONE) < 0 ? 4 : 2, RoundingMode.HALF_UP).toPlainString();
    }
}
