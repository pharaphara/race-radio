package io.github.pharaphara.raceradio.bot.athlete;

import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.bot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What only the administrator can do: invite, see who is in and what each costs, choose an
 * athlete's model and budget, pause, resume and remove. Every other athlete who types these gets
 * nothing special: the dispatcher sends them here only for the administrator.
 */
public final class AdminCommands {

    private static final Logger LOG = LoggerFactory.getLogger(AdminCommands.class);
    static final Pattern COMMAND = Pattern.compile("(?i)^/(inviter|athletes|athlètes|modele|modèle|plafond|secours|pause|reprendre|retirer)(?:@\\S+)?\\b\\s*(.*)$",
            Pattern.DOTALL);

    private final JdbcTemplate jdbc;
    private final Athletes athletes;
    private final Invitations invitations;
    private final AthleteRegistry registry;
    private final TelegramClient telegram;
    private final Path dataDir;
    private final ZoneId zone;
    private final Clock clock;
    private final boolean invitationsOpen;

    public AdminCommands(JdbcTemplate jdbc, Athletes athletes, Invitations invitations, AthleteRegistry registry,
                         TelegramClient telegram, Path dataDir, ZoneId zone, Clock clock, boolean invitationsOpen) {
        this.jdbc = jdbc;
        this.athletes = athletes;
        this.invitations = invitations;
        this.registry = registry;
        this.telegram = telegram;
        this.dataDir = dataDir;
        this.zone = zone;
        this.clock = clock;
        this.invitationsOpen = invitationsOpen;
    }

    public boolean handles(TelegramClient.Update u) {
        return u.isCallback() ? u.callbackData() != null && u.callbackData().startsWith("adm:")
                : u.text() != null && COMMAND.matcher(u.text().strip()).matches();
    }

    public void handle(TelegramClient.Update u, long chat) {
        if (u.isCallback()) {
            telegram.acknowledge(u.callbackId(), "Reçu");
            callback(u.callbackData(), chat);
            return;
        }
        Matcher m = COMMAND.matcher(u.text().strip());
        if (!m.matches()) {
            return;
        }
        String rest = m.group(2).strip();
        switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "inviter" -> invite(chat, rest);
            case "athletes", "athlètes" -> telegram.send(chat, list());
            case "modele", "modèle" -> route(chat, rest);
            case "plafond" -> budget(chat, rest);
            case "secours" -> fallback(chat, rest);
            case "pause" -> status(chat, rest, "paused");
            case "reprendre" -> status(chat, rest, "active");
            case "retirer" -> askRemove(chat, rest);
            default -> { }
        }
    }

    private void invite(long chat, String rest) {
        if (!invitationsOpen) {
            telegram.send(chat, "Les invitations demandent une clé maître pour chiffrer les clés des invités : ajoute "
                    + "RACE_RADIO_SECRET_KEY au .env (openssl rand -base64 32), puis redémarre.");
            return;
        }
        String route = rest.toLowerCase(Locale.ROOT).startsWith("gratuit") ? "free" : "subscription";
        String code = invitations.create(route);
        String link = telegram.username().map(name -> "\nOu ce lien, qui ouvre le coach avec le code : https://t.me/" + name + "?start=" + code)
                .orElse("");
        telegram.send(chat, "Invitation créée, " + (route.equals("free") ? "modèle gratuit" : "sur ton abonnement")
                + ", valable 7 jours et une seule fois.\n\nÀ envoyer à ton invité : qu'il écrive au coach\n/start " + code + link);
    }

    String list() {
        StringBuilder b = new StringBuilder("Athlètes :\n");
        YearMonth month = YearMonth.now(clock.withZone(zone));
        for (Athlete a : athletes.all()) {
            BigDecimal cost = new Ledger(new io.github.pharaphara.raceradio.bot.store.AthleteDb(jdbc, a.id())).costOf(month, zone);
            Integer calls = jdbc.queryForObject("SELECT COUNT(*) FROM llm_call WHERE athlete_id = ? AND at >= ?", Integer.class,
                    a.id().value(), month.atDay(1).atStartOfDay(zone).toInstant().toString());
            b.append("• ").append(a.name() == null ? "toi" : a.name()).append(a.administrator() ? " (admin)" : "")
                    .append(a.active() ? "" : " · en pause")
                    .append(" · ").append(a.modelRoute().equals("free") ? "modèle gratuit" + (a.modelFallback() ? " (secours abonnement)" : "")
                            : "abonnement")
                    .append(" · ").append(calls).append(" appels, ").append(cost.setScale(2, RoundingMode.HALF_UP)).append(" $ ce mois")
                    .append(a.monthlyBudget() == null ? "" : " sur " + a.monthlyBudget().stripTrailingZeros().toPlainString() + " $")
                    .append('\n');
        }
        return b.toString().strip();
    }

    /** The athlete a first name designates, among those who are not the administrator. */
    private Optional<Athlete> named(long chat, String name) {
        if (name.isBlank()) {
            telegram.send(chat, "De qui ? Donne son prénom, comme dans /athletes.");
            return Optional.empty();
        }
        List<Athlete> found = athletes.all().stream().filter(a -> !a.administrator() && a.name() != null
                && a.name().equalsIgnoreCase(name.strip())).toList();
        if (found.size() != 1) {
            telegram.send(chat, found.isEmpty() ? "Personne de ce nom. /athletes donne la liste." : "Plusieurs athlètes portent ce prénom.");
            return Optional.empty();
        }
        return Optional.of(found.getFirst());
    }

    private void route(long chat, String rest) {
        String[] p = rest.split("\\s+", 2);
        if (p.length < 2 || !(p[1].equalsIgnoreCase("abonnement") || p[1].equalsIgnoreCase("gratuit"))) {
            telegram.send(chat, "Exemple : /modele Paul gratuit, ou /modele Paul abonnement");
            return;
        }
        named(chat, p[0]).ifPresent(a -> {
            String route = p[1].equalsIgnoreCase("gratuit") ? "free" : "subscription";
            jdbc.update("UPDATE athlete SET model_route = ? WHERE id = ?", route, a.id().value());
            registry.forget(a.id());
            telegram.send(chat, a.name() + " passe " + (route.equals("free") ? "au modèle gratuit." : "sur ton abonnement."));
        });
    }

    private void budget(long chat, String rest) {
        String[] p = rest.split("\\s+", 2);
        if (p.length < 2 || !p[1].replace(',', '.').matches("\\d+(\\.\\d+)?")) {
            telegram.send(chat, "Exemple : /plafond Paul 5 (en dollars par mois, au prix public)");
            return;
        }
        named(chat, p[0]).ifPresent(a -> {
            jdbc.update("UPDATE athlete SET monthly_budget_usd = ? WHERE id = ?", Double.parseDouble(p[1].replace(',', '.')), a.id().value());
            registry.forget(a.id());
            telegram.send(chat, "Plafond de " + a.name() + " : " + p[1] + " $ par mois.");
        });
    }

    /** Whether a failure of the free model may fall back to the administrator's subscription. */
    private void fallback(long chat, String rest) {
        String[] p = rest.split("\\s+", 2);
        if (p.length < 2 || !(p[1].equalsIgnoreCase("oui") || p[1].equalsIgnoreCase("non"))) {
            telegram.send(chat, "Exemple : /secours Paul oui (si le modèle gratuit échoue, ton abonnement répond), ou /secours Paul non");
            return;
        }
        named(chat, p[0]).ifPresent(a -> {
            boolean on = p[1].equalsIgnoreCase("oui");
            jdbc.update("UPDATE athlete SET model_fallback = ? WHERE id = ?", on ? 1 : 0, a.id().value());
            registry.forget(a.id());
            telegram.send(chat, on ? "Si le modèle gratuit échoue pour " + a.name() + ", ton abonnement prendra le relais."
                    : "Plus de relais par ton abonnement pour " + a.name() + " : ses textes restent calculés si le modèle gratuit échoue.");
        });
    }

    private void status(long chat, String rest, String status) {
        named(chat, rest).ifPresent(a -> {
            jdbc.update("UPDATE athlete SET status = ? WHERE id = ?", status, a.id().value());
            registry.forget(a.id());
            telegram.send(chat, a.name() + (status.equals("paused") ? " est en pause : plus de message, plus d'analyse." : " reprend."));
        });
    }

    private void askRemove(long chat, String rest) {
        named(chat, rest).ifPresent(a -> telegram.sendWithButtonRows(chat, "Retirer " + a.name()
                        + " ? Toutes ses données ici seront effacées : archive, conversations, notes, clé. Ses données dans Intervals.icu ne bougent pas.",
                List.of(Map.of("Oui, retirer " + a.name(), "adm:remove:" + a.id().value()), Map.of("Non", "adm:keep"))));
    }

    private void callback(String data, long chat) {
        if (data.equals("adm:keep")) {
            telegram.send(chat, "Rien n'a été retiré.");
            return;
        }
        if (data.startsWith("adm:remove:")) {
            AthleteId id = new AthleteId(Long.parseLong(data.substring("adm:remove:".length())));
            Optional<Athlete> a = athletes.byId(id);
            if (a.isEmpty() || a.get().administrator()) {
                telegram.send(chat, "Rien à retirer.");
                return;
            }
            remove(a.get());
            telegram.send(chat, a.get().name() + " est retiré, et ses données effacées.");
        }
    }

    /** Every row of theirs goes with the athlete (ON DELETE CASCADE), then their files. */
    void remove(Athlete a) {
        registry.forget(a.id());
        jdbc.update("DELETE FROM athlete WHERE id = ?", a.id().value());
        Path dir = dataDir.resolve("athletes").resolve(String.valueOf(a.id().value()));
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (IOException e) {
                LOG.warn("athlete #{}: files not all removed: {}", a.id().value(), e.getMessage());
            }
        }
        LOG.info("athlete #{} removed", a.id().value());
    }
}
