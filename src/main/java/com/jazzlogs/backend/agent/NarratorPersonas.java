package com.jazzlogs.backend.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.jazzlogs.backend.character.JazzlogsCharacter;

/**
 * The eight narrators' voices, one file each ({@code mark.md}, {@code
 * laura.md}, ...) under {@code agent.narrators.location} — plain text a
 * writer can edit without touching Java. A file is a sequence of {@code ##
 * SECTION} headers, each followed by that section's text:
 * <pre>
 * ## TRAITS
 * ## CHAT VOICE
 * ## SAMPLE
 * ## ON OTHER JAZZLOGS FRIENDS
 * </pre>
 * Loaded and checked once, at startup, and the app refuses to boot on a
 * missing file or a missing/empty/unknown section — a persona that's
 * silently incomplete would only show up as an off-voice answer in
 * production. Every problem across all eight files is reported together,
 * not one per restart.
 */
@Component
public class NarratorPersonas {

    private static final String SECTION_PREFIX = "## ";
    private static final String TRAITS = "TRAITS";
    private static final String CHAT_VOICE = "CHAT VOICE";
    private static final String SAMPLE = "SAMPLE";
    private static final String ON_FRIENDS = "ON OTHER JAZZLOGS FRIENDS";
    private static final List<String> SECTIONS = List.of(TRAITS, CHAT_VOICE, SAMPLE, ON_FRIENDS);

    private final Map<JazzlogsCharacter, NarratorPersona> personas;

    public NarratorPersonas(ResourceLoader resourceLoader, @Value("${agent.narrators.location}") String location) {
        Map<JazzlogsCharacter, NarratorPersona> loaded = new EnumMap<>(JazzlogsCharacter.class);
        List<String> problems = new ArrayList<>();
        for (JazzlogsCharacter narrator : JazzlogsCharacter.values()) {
            String path = location + fileName(narrator);
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                problems.add(path + ": file not found");
                continue;
            }
            NarratorPersona persona = parse(path, read(resource), problems);
            if (persona != null) {
                loaded.put(narrator, persona);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Narrator personas are incomplete:\n- " + String.join("\n- ", problems));
        }
        this.personas = loaded;
    }

    /** @return the prompt section that makes the agent answer as {@code narrator} */
    public String render(JazzlogsCharacter narrator) {
        NarratorPersona persona = personas.get(narrator);
        String name = displayName(narrator);

        return """
            YOUR CHARACTER
            You are %1$s, one of the eight JazzLogs narrators — %4$s wherever a tool result says
            who wrote a log (writtenBy). Everything in answerText is said by %1$s, in first
            person — never as "JazzLogs", an assistant, or a narrator-less voice.

            WHO YOU ARE
            %2$s

            HOW YOU TALK IN CHAT
            %6$s

            VOICE SAMPLE
            Something %1$s wrote. Match its voice — rhythm, vocabulary, attitude. Never quote it,
            paraphrase it, or reuse its subject matter.
            %3$s

            ON OTHER JAZZLOGS FRIENDS
            %5$s""".formatted(name, persona.traits(), persona.sample(), narrator.name(), persona.onFriends(), persona.chatVoice());
    }

    private static NarratorPersona parse(String path, String text, List<String> problems) {
        Map<String, String> sections = splitSections(text);
        int problemsBefore = problems.size();

        for (String section : SECTIONS) {
            String body = sections.get(section);
            if (body == null) {
                problems.add(path + ": missing section \"" + SECTION_PREFIX + section + "\"");
            } else if (body.isBlank()) {
                problems.add(path + ": section \"" + SECTION_PREFIX + section + "\" is empty");
            }
        }
        // Catches a typo'd header — it would otherwise be dropped silently while the real
        // section is reported as merely "missing".
        for (String section : sections.keySet()) {
            if (!SECTIONS.contains(section)) {
                problems.add(path + ": unexpected section \"" + SECTION_PREFIX + section + "\"");
            }
        }
        if (problems.size() > problemsBefore) {
            return null;
        }
        return new NarratorPersona(sections.get(TRAITS), sections.get(CHAT_VOICE), sections.get(SAMPLE), sections.get(ON_FRIENDS));
    }

    /** Header (upper-cased, without its {@code ## }) to trimmed body; text before the first header is ignored. */
    private static Map<String, String> splitSections(String text) {
        Map<String, StringBuilder> raw = new LinkedHashMap<>();
        StringBuilder current = null;
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith(SECTION_PREFIX)) {
                current = new StringBuilder();
                raw.put(line.substring(SECTION_PREFIX.length()).trim().toUpperCase(Locale.ROOT), current);
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        Map<String, String> sections = new LinkedHashMap<>();
        raw.forEach((header, body) -> sections.put(header, body.toString().strip()));
        return sections;
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read narrator persona " + resource, e);
        }
    }

    private static String fileName(JazzlogsCharacter narrator) {
        return narrator.name().toLowerCase(Locale.ROOT) + ".md";
    }

    /** MARK -> Mark. */
    private static String displayName(JazzlogsCharacter narrator) {
        String lower = narrator.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
