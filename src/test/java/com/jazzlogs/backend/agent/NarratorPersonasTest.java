package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.jazzlogs.backend.character.JazzlogsCharacter;

class NarratorPersonasTest {

    private static final String FIXTURES = "classpath:agent/narrators-fixture/";
    private static final String COMPLETE_FILE = "## TRAITS\nline\n## CHAT VOICE\nline\n## SAMPLE\nline\n## ON OTHER JAZZLOGS FRIENDS\nline\n";

    @TempDir
    Path dir;

    @Test
    void render_putsOnlyThatNarratorsOwnVoiceInThePrompt() {
        NarratorPersonas personas = new NarratorPersonas(new DefaultResourceLoader(), FIXTURES);

        String section = personas.render(JazzlogsCharacter.MARK);

        assertThat(section).contains("You are Mark, one of the eight JazzLogs narrators — MARK wherever");
        assertThat(section).contains("Fixture traits for Mark.");
        assertThat(section).contains("HOW YOU TALK IN CHAT\nFixture chat voice for Mark.");
        assertThat(section).contains("Fixture sample written by Mark.");
        assertThat(section).contains("Fixture: how Mark talks about a friend's log.");
        assertThat(section).contains("ON OTHER JAZZLOGS FRIENDS\nFixture: how Mark talks about a friend's log.");
        assertThat(section).doesNotContain("Fixture traits for Laura.");
    }

    @Test
    void refusesToLoad_andReportsEveryProblemAcrossAllFilesAtOnce() throws IOException {
        writeCompleteFilesExcept(JazzlogsCharacter.BOB);
        // LAURA: a typo'd header, which also leaves the real section missing.
        Files.writeString(dir.resolve("laura.md"), COMPLETE_FILE.replace("## SAMPLE\n", "## SAMPEL\n"));
        // ALICE: a section that's there but empty.
        Files.writeString(dir.resolve("alice.md"), COMPLETE_FILE.replace("## TRAITS\nline\n", "## TRAITS\n\n"));

        assertThatThrownBy(() -> new NarratorPersonas(new DefaultResourceLoader(), dir.toUri().toString()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bob.md: file not found")
            .hasMessageContaining("laura.md: missing section \"## SAMPLE\"")
            .hasMessageContaining("laura.md: unexpected section \"## SAMPEL\"")
            .hasMessageContaining("alice.md: section \"## TRAITS\" is empty");
    }

    private void writeCompleteFilesExcept(JazzlogsCharacter skipped) throws IOException {
        for (JazzlogsCharacter narrator : JazzlogsCharacter.values()) {
            if (narrator != skipped) {
                Files.writeString(dir.resolve(narrator.name().toLowerCase(Locale.ROOT) + ".md"), COMPLETE_FILE);
            }
        }
    }
}
