package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.store.AthleteDb;
import io.github.pharaphara.raceradio.bot.store.Plans;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A newcomer's way in, guided step by step in plain words, for people who have never seen an API
 * key: an invitation code, a first name, an Intervals.icu account tied to their watch, their id and
 * key (the message is deleted at once, the key checked by a read and sealed), and their sports.
 * Where they stand is kept in {@code checkin}, so a restart loses nothing; a check-in left two days
 * starts over. Only when every step is done does the athlete exist.
 */
public final class CheckIn {

    private static final Logger LOG = LoggerFactory.getLogger(CheckIn.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration STALE = Duration.ofDays(2);
    private static final Pattern START = Pattern.compile("(?i)^/start(?:@\\S+)?\\s+(\\S+)\\s*$");
    private static final Pattern ATHLETE_ID = Pattern.compile("(?i)\\b(i\\d{3,})\\b");
    private static final Pattern KEY = Pattern.compile("\\b([A-Za-z0-9]{16,})\\b");
    /** Sports offered at check-in, in the order shown. */
    static final Map<String, String> SPORTS = new LinkedHashMap<>(Map.of());

    static {
        SPORTS.put("Ride", "Vélo");
        SPORTS.put("Run", "Course à pied");
        SPORTS.put("Swim", "Natation");
    }

    private final JdbcTemplate jdbc;
    private final Invitations invitations;
    private final SecretBox box;
    private final TelegramClient telegram;
    private final Function<IntervalsCredentials, IntervalsClient> intervals;
    private final Consumer<AthleteId> welcomed;
    private final Clock clock;

    /**
     * @param box        null when no master key is configured: then nobody can check in, and the
     *                   newcomer is told so
     * @param intervals  a client for the credentials given, to check them by a read
     * @param welcomed   what to do once the athlete exists: the archive filled in the background
     */
    public CheckIn(JdbcTemplate jdbc, Invitations invitations, SecretBox box, TelegramClient telegram,
                   Function<IntervalsCredentials, IntervalsClient> intervals, Consumer<AthleteId> welcomed, Clock clock) {
        this.jdbc = jdbc;
        this.invitations = invitations;
        this.box = box;
        this.telegram = telegram;
        this.intervals = intervals;
        this.welcomed = welcomed;
        this.clock = clock;
    }

    /** True when this update belongs to a check-in: a code, or a check-in under way. */
    public boolean concerns(TelegramClient.Update u) {
        return (u.text() != null && START.matcher(u.text().strip()).matches()) || state(u.chatId()).isPresent();
    }

    private record State(String step, ObjectNode data) {
    }

    private Optional<State> state(long chat) {
        return jdbc.query("SELECT step, data, updated_at FROM checkin WHERE chat_id = ?", (rs, i) -> {
            if (Instant.parse(rs.getString(3)).plus(STALE).isBefore(clock.instant())) {
                return null;
            }
            return new State(rs.getString(1), (ObjectNode) JSON.readTree(rs.getString(2)));
        }, chat).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    private void save(long chat, String step, ObjectNode data) {
        jdbc.update("INSERT INTO checkin (chat_id, step, data, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(chat_id) DO UPDATE SET step = excluded.step, data = excluded.data, updated_at = excluded.updated_at",
                chat, step, data.toString(), clock.instant().toString());
    }

    public void handle(TelegramClient.Update u) {
        long chat = u.chatId();
        if (u.isCallback()) {
            telegram.acknowledge(u.callbackId(), "");
        }
        Matcher start = u.text() == null ? null : START.matcher(u.text().strip());
        if (start != null && start.matches()) {
            begin(chat, start.group(1));
            return;
        }
        Optional<State> s = state(chat);
        if (s.isEmpty()) {
            return;
        }
        switch (s.get().step()) {
            case "name" -> name(chat, u, s.get().data());
            case "intervals" -> intervalsReady(chat, u, s.get().data());
            case "key" -> key(chat, u, s.get().data());
            case "sports" -> sports(chat, u, s.get().data());
            default -> { }
        }
    }

    private void begin(long chat, String code) {
        if (box == null) {
            telegram.send(chat, "Les inscriptions ne sont pas encore ouvertes sur ce coach. Préviens la personne qui t'a invité.");
            LOG.warn("a check-in was refused: RACE_RADIO_SECRET_KEY is not configured");
            return;
        }
        Optional<Invitations.Invitation> invitation = invitations.valid(code);
        if (invitation.isEmpty()) {
            telegram.send(chat, "Ce code n'est pas valable, ou il a déjà servi, ou il a expiré. Demande-en un nouveau à la personne qui t'a invité.");
            return;
        }
        ObjectNode data = JSON.createObjectNode();
        data.put("invitation", invitation.get().codeHash()).put("route", invitation.get().modelRoute());
        save(chat, "name", data);
        telegram.send(chat, """
                Bienvenue ! Je suis un coach d'endurance : chaque matin je te dis si c'est un jour pour t'entraîner, \
                t'alléger ou récupérer, et après chaque séance je t'en fais l'analyse. Je peux aussi te proposer des séances.

                Bon à savoir : la personne qui t'a invité administre ce coach et peut lire tes données.

                Pour commencer, quel est ton prénom ?""");
    }

    private void name(long chat, TelegramClient.Update u, ObjectNode data) {
        String name = u.text() == null ? "" : u.text().strip();
        if (name.isEmpty() || name.startsWith("/") || name.length() > 40) {
            telegram.send(chat, "Écris simplement ton prénom.");
            return;
        }
        data.put("name", name);
        save(chat, "intervals", data);
        telegram.sendWithButtonRows(chat, "Enchanté " + name + " ! Je travaille à partir d'Intervals.icu, un site gratuit "
                + "qui récupère les séances de ta montre. As-tu un compte Intervals.icu relié à ta montre ?",
                List.of(Map.of("Oui, c'est fait", "ck:ready"), Map.of("Pas encore", "ck:notyet")));
    }

    private void intervalsReady(long chat, TelegramClient.Update u, ObjectNode data) {
        if ("ck:notyet".equals(u.callbackData())) {
            telegram.sendWithButtonRows(chat, """
                    Pas de souci, ça prend cinq minutes :
                    1. Va sur https://intervals.icu et crée un compte (gratuit).
                    2. Dans Settings, section Connections, relie ta montre (Garmin, Coros, Suunto…).
                    3. Pour Garmin, coche aussi l'envoi des séances prévues vers la montre (upload planned workouts).
                    Quand c'est fait, appuie ici :""", List.of(Map.of("C'est fait", "ck:ready")));
            return;
        }
        if (!"ck:ready".equals(u.callbackData())) {
            telegram.sendWithButtonRows(chat, "Appuie sur un des boutons :",
                    List.of(Map.of("Oui, c'est fait", "ck:ready"), Map.of("Pas encore", "ck:notyet")));
            return;
        }
        save(chat, "key", data);
        telegram.send(chat, """
                Parfait. J'ai besoin de deux choses pour lire tes séances :
                • ton identifiant d'athlète, qui commence par i (par exemple i12345) ;
                • ta clé d'accès.

                Sur Intervals.icu : clique sur ton nom en haut à droite, puis Settings. Tout en bas, section \
                « Developer Settings », tu trouves l'« Athlete ID » et un bouton pour afficher l'« API Key ».

                Envoie-les-moi dans un seul message, séparés par un espace. J'effacerai ton message tout de suite \
                et je garderai la clé chiffrée.""");
    }

    private void key(long chat, TelegramClient.Update u, ObjectNode data) {
        String text = u.text() == null ? "" : u.text();
        if (u.messageId() != 0 && !text.isBlank()) {
            try {
                telegram.deleteMessage(chat, u.messageId());
            } catch (RuntimeException e) {
                LOG.warn("the message holding a key could not be deleted: {}", e.getMessage());
            }
        }
        Matcher id = ATHLETE_ID.matcher(text);
        String rest = id.find() ? text.replace(id.group(1), " ") : text;
        Matcher key = KEY.matcher(rest);
        if (!id.reset().find() || !key.find()) {
            telegram.send(chat, "Je n'ai pas trouvé les deux : l'identifiant (qui commence par i) et la clé, dans le même message, "
                    + "séparés par un espace. Réessaie.");
            return;
        }
        String athleteId = id.group(1).toLowerCase(java.util.Locale.ROOT);
        String apiKey = key.group(1);
        io.github.pharaphara.raceradio.icu.model.Athlete profile;
        try {
            profile = intervals.apply(new IntervalsCredentials(athleteId, apiKey)).athlete();
        } catch (RuntimeException e) {
            telegram.send(chat, "Intervals.icu refuse cet identifiant ou cette clé. Vérifie-les dans Settings, "
                    + "Developer Settings, et renvoie-les-moi.");
            return;
        }
        data.put("intervalsId", athleteId).put("key", box.seal(apiKey));
        data.putArray("sports");
        save(chat, "sports", data);
        String garmin = profile.pushesWorkoutsToGarmin() ? ""
                : "\n\nUne chose à régler plus tard : dans Intervals.icu, Settings, Garmin, coche l'envoi des séances prévues "
                + "vers la montre (upload planned workouts). Sans ça, les séances que je te prépare n'arriveront pas sur ta montre.";
        telegram.sendWithButtonRows(chat, "C'est bon, j'ai accès à tes séances, et ton message est effacé." + garmin
                + "\n\nDernière question : quels sports pratiques-tu ? Coche-les, puis valide.", sportButtons(Set.of()));
    }

    private void sports(long chat, TelegramClient.Update u, ObjectNode data) {
        Set<String> chosen = new LinkedHashSet<>();
        data.path("sports").forEach(n -> chosen.add(n.asString()));
        String cb = u.callbackData() == null ? "" : u.callbackData();
        if (cb.startsWith("ck:sport:") && SPORTS.containsKey(cb.substring(9))) {
            String sport = cb.substring(9);
            if (!chosen.remove(sport)) {
                chosen.add(sport);
            }
            var array = data.putArray("sports");
            chosen.forEach(array::add);
            save(chat, "sports", data);
            telegram.sendWithButtonRows(chat, chosen.isEmpty() ? "Coche au moins un sport." : "Choisi : "
                    + String.join(", ", chosen.stream().map(SPORTS::get).toList()) + ". Tu peux en cocher d'autres, ou valider.",
                    sportButtons(chosen));
            return;
        }
        if (!cb.equals("ck:sports-done")) {
            telegram.sendWithButtonRows(chat, "Coche tes sports avec les boutons, puis valide.", sportButtons(chosen));
            return;
        }
        if (chosen.isEmpty()) {
            telegram.sendWithButtonRows(chat, "Coche au moins un sport.", sportButtons(chosen));
            return;
        }
        finish(chat, data, chosen);
    }

    private static List<Map<String, String>> sportButtons(Set<String> chosen) {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        SPORTS.forEach((code, label) -> rows.add(Map.of((chosen.contains(code) ? "✓ " : "") + label, "ck:sport:" + code)));
        rows.add(Map.of("Valider", "ck:sports-done"));
        return rows;
    }

    private void finish(long chat, ObjectNode data, Set<String> sports) {
        Optional<Invitations.Invitation> invitation = jdbc.query("SELECT code_hash, model_route FROM invitation "
                        + "WHERE code_hash = ? AND used_at IS NULL", (rs, i) -> new Invitations.Invitation(rs.getString(1), rs.getString(2)),
                data.path("invitation").asString()).stream().findFirst();
        if (invitation.isEmpty()) {
            jdbc.update("DELETE FROM checkin WHERE chat_id = ?", chat);
            telegram.send(chat, "Ton invitation a déjà servi entre-temps. Demande-en une nouvelle.");
            return;
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO athlete (chat_id, name, role, intervals_athlete_id, intervals_key, model_route, created_at)
                VALUES (?, ?, 'athlete', ?, ?, ?, ?) RETURNING id""", Long.class,
                chat, data.path("name").asString(), data.path("intervalsId").asString(), data.path("key").asString(),
                invitation.get().modelRoute(), clock.instant().toString());
        AthleteId athlete = new AthleteId(id);
        invitations.use(invitation.get(), athlete);
        new Plans(new AthleteDb(jdbc, athlete)).sports(sports);
        jdbc.update("DELETE FROM checkin WHERE chat_id = ?", chat);
        LOG.info("athlete #{} checked in", id);
        telegram.send(chat, """
                C'est parti ! Je récupère ton historique en arrière-plan, ça peut prendre quelques minutes.

                Dès demain matin, je te dirai comment tu te portes. Après chaque séance, je t'enverrai son analyse.
                Pour régler ton objectif : /objectif. Tes disponibilités : /dispo. Tout le reste : /aide.
                Et tu peux simplement m'écrire, comme à un coach.""");
        welcomed.accept(athlete);
    }
}
