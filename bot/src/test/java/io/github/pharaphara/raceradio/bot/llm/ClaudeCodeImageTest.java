package io.github.pharaphara.raceradio.bot.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The photo is read from a directory that holds nothing else, with Read as the only tool. */
class ClaudeCodeImageTest {

    @TempDir
    Path dir;

    @Test
    void thePhotoIsAloneInTheWorkingDirectoryAndOnlyReadIsOpened() throws IOException {
        Path script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > \"" + dir.resolve("args.txt") + "\"\n"
                + "ls -A > \"" + dir.resolve("cwd.txt") + "\"\n"
                + "cat > \"" + dir.resolve("stdin.txt") + "\"\n"
                + "echo '{\"result\":\"AMRAP 12 min\",\"stop_reason\":\"end_turn\",\"usage\":{},\"modelUsage\":{}}'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Path work = dir.resolve("work");
        var t = new ClaudeCodeTransport(script.toString(), work, Duration.ofSeconds(20));
        ModelReply r = t.sendWithImage(new ModelRequest(Purpose.PHOTO, "claude-sonnet-5-5", "low", 1500, true, "v", "Transcribe."),
                SystemPrompt.of("rules"), new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2}, "image/jpeg");

        assertThat(r.text()).isEqualTo("AMRAP 12 min");
        assertThat(Files.readAllLines(dir.resolve("cwd.txt"))).containsExactly("photo.jpg");
        List<String> args = Files.readAllLines(dir.resolve("args.txt"));
        assertThat(args.get(args.indexOf("--tools") + 1)).isEqualTo("Read");
        assertThat(args).doesNotContain("--bare").contains("--strict-mcp-config");
        assertThat(Files.readString(dir.resolve("stdin.txt"))).contains("photo.jpg");
        try (var left = Files.list(work)) {
            assertThat(left.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("photo-"));
        }
    }
}
