package io.github.pharaphara.raceradio.bot.athlete;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.button;
import static io.github.pharaphara.raceradio.bot.athlete.AthleteFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

/** Every log line says whose work it is, by number, and no line ever holds a key. */
class LogTest {

    @TempDir
    Path dir;

    private AthleteFixture f;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private static final String KEY = "abcdefghij0123456789xyz";

    @BeforeEach
    void start() throws IOException {
        f = new AthleteFixture(dir);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logs);
        f.close();
    }

    private List<String> lines() {
        return logs.list.stream().map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap()
                + (e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage())).toList();
    }

    @Test
    void aCheckInLeavesNoKeyInTheLogsAndIsLoggedAsNobodysYet() {
        f.dispatcher.handleNow(message(42, "/inviter"));
        String code = f.to(42).getLast().replaceAll("(?s).*/start ([A-Z0-9]{8}).*", "$1");
        f.dispatcher.handleNow(message(1234, "/start " + code));
        f.dispatcher.handleNow(message(1234, "Paul"));
        f.dispatcher.handleNow(button(1234, "ck:ready"));
        f.dispatcher.handleNow(message(1234, "i54321 " + KEY));
        f.dispatcher.handleNow(button(1234, "ck:sport:Ride"));
        f.dispatcher.handleNow(button(1234, "ck:sports-done"));
        f.dispatcher.handleNow(message(1234, "/aide"));

        assertThat(lines()).isNotEmpty().noneSatisfy(l -> assertThat(l).contains(KEY));
        assertThat(lines()).noneSatisfy(l -> assertThat(l).contains("Paul"));
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("checked in"))
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry(AthleteLog.KEY, AthleteLog.NOBODY));
    }

    @Test
    void whatIsDoneForAnAthleteCarriesTheirNumberOnEveryLine() {
        f.dispatcher.handleNow(message(7, "/aide"));
        List<ILoggingEvent> built = logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("Intervals.icu writes")).toList();
        assertThat(built).isNotEmpty().allSatisfy(e ->
                assertThat(e.getMDCPropertyMap()).containsEntry(AthleteLog.KEY, "#" + f.friend.id()));
        // Outside an athlete's work the context is empty again.
        assertThat(org.slf4j.MDC.get(AthleteLog.KEY)).isNull();
    }
}
