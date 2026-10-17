package io.github.pharaphara.raceradio.bot.strength;

import io.github.pharaphara.raceradio.bot.TestDb;
import io.github.pharaphara.raceradio.bot.config.RaceRadioProperties;
import io.github.pharaphara.raceradio.bot.llm.CoachModel;
import io.github.pharaphara.raceradio.bot.llm.Journal;
import io.github.pharaphara.raceradio.bot.llm.ModelReply;
import io.github.pharaphara.raceradio.bot.llm.ModelRequest;
import io.github.pharaphara.raceradio.bot.llm.ModelTransport;
import io.github.pharaphara.raceradio.bot.llm.Pricing;
import io.github.pharaphara.raceradio.bot.llm.SystemPrompt;
import io.github.pharaphara.raceradio.bot.llm.Usage;
import io.github.pharaphara.raceradio.bot.store.Ledger;
import io.github.pharaphara.raceradio.icu.IntervalsClient;
import io.github.pharaphara.raceradio.icu.IntervalsCredentials;
import io.github.pharaphara.raceradio.icu.IntervalsWriter;
import io.github.pharaphara.raceradio.icu.model.ActivitySummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BoardReaderTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T19:00:00Z"), PARIS);

    @TempDir
    Path dir;

    private final List<String> writes = new ArrayList<>();
    private List<ActivitySummary> today = List.of();
    private String transcription = "AMRAP 12 min\n10 burpees";
    private String seenMediaType;

    private BoardReader reader() {
        IntervalsClient icu = new IntervalsClient(new IntervalsCredentials("i42", "k"), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/")) {
            @Override
            public List<ActivitySummary> activities(LocalDate from, LocalDate to) {
                return today;
            }
        };
        ModelTransport t = new ModelTransport() {
            @Override
            public ModelReply send(ModelRequest request, SystemPrompt system) {
                throw new AssertionError("a photo must go with its image");
            }

            @Override
            public ModelReply sendWithImage(ModelRequest request, SystemPrompt system, byte[] image, String mediaType) {
                seenMediaType = mediaType;
                assertThat(request.imageSha256()).isNotBlank();
                return new ModelReply(transcription, "claude-sonnet-5-5", "end_turn", false, Usage.NONE, 1, 0.01);
            }

            @Override
            public String billing() {
                return "subscription";
            }
        };
        CoachModel model = new CoachModel(t, SystemPrompt.of("rules"), new Ledger(TestDb.in(dir)), new Journal(dir.resolve("llm")),
                new Pricing(Map.<String, RaceRadioProperties.Prices>of()), "claude-sonnet-5-5", true, new BigDecimal("10"), CLOCK, PARIS);
        return new BoardReader(model, icu, new IntervalsWriter(icu, IntervalsWriter.Mode.DRY_RUN, writes::add), dir.resolve("photos"), CLOCK);
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};

    @Test
    void theWorkoutIsWrittenIntoTodaysStrengthSession() throws Exception {
        today = List.of(new ActivitySummary("c1", LocalDateTime.of(2026, 10, 17, 19, 0), "Crossfit", "Box", 2700, null, null, 40, 150.0, null),
                new ActivitySummary("r1", LocalDateTime.of(2026, 10, 17, 7, 0), "Run", "Footing", 2400, null, null, 30, 140.0, null));
        String answer = reader().read(JPEG, "WOD du soir");
        assertThat(answer).contains("AMRAP 12 min").contains("Box").contains("mode sec");
        assertThat(writes).singleElement().asString().startsWith("DRY RUN PUT activity/c1").contains("10 burpees");
        try (var files = Files.list(dir.resolve("photos"))) {
            Path kept = files.findFirst().orElseThrow();
            assertThat(kept.getFileName().toString()).startsWith("2026-10-17-").endsWith(".jpg");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(kept))).isEqualTo("rw-------");
        }
    }

    @Test
    void withoutASessionYetTheAthleteIsToldToSendItAgainLater() {
        assertThat(reader().read(JPEG, null)).contains("Pas encore de séance");
        assertThat(writes).isEmpty();
    }

    @Test
    void aPhotoThatIsNotABoardIsSaidSo() {
        transcription = "PAS UN TABLEAU";
        assertThat(reader().read(JPEG, null)).contains("Je ne vois pas de séance");
    }

    @Test
    void aPngIsSentAsAPng() {
        reader().read(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1}, null);
        assertThat(seenMediaType).isEqualTo("image/png");
    }
}
