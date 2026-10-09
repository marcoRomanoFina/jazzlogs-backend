package com.jazzlogs.backend.agent.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.jazzlogs.backend.agent.ToolCallRequest;
import com.jazzlogs.backend.agent.ToolExecutionResult;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.tracksearch.TrackCandidate;
import com.jazzlogs.backend.tracksearch.TrackSearchCriteria;
import com.jazzlogs.backend.tracksearch.TrackSearchService;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

/**
 * The agent's one way of looking for something to recommend. A thin adapter:
 * it turns the model's JSON into a {@link TrackSearchCriteria}, rejecting
 * anything outside the vocabularies, and serializes what {@link
 * TrackSearchService} finds — every decision about what matches and in what
 * order lives there, not here.
 */
@Component
public class FindTracksTool extends JazzTool {

    public static final String NAME = "FIND_TRACKS";

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.ofEntries(
            Map.entry("lookingFor", Map.of(
                "type", "string",
                "description", "The music you are after, described the way a log would describe it — its mood, "
                    + "atmosphere, character, how it is played (\"slow, spacious piano trio that never raises "
                    + "its voice\"). Candidates are ranked by how closely their logs speak to this. Describe "
                    + "only what the user actually indicated: do not invent detail to make it fuller, and "
                    + "leave out instructions like \"recommend\" or \"find\"."
            )),
            Map.entry("styles", codesOf(StyleVocabulary.class)),
            Map.entry("moods", codesOf(MoodVocabulary.class)),
            Map.entry("contexts", codesOf(ContextVocabulary.class)),
            Map.entry("rhythms", codesOf(RhythmVocabulary.class)),
            Map.entry("instruments", codesOf(InstrumentVocabulary.class)),
            Map.entry("energy", level("How energetic the track is.")),
            Map.entry("accessibility", level("How easy the track is to get into: HIGH for a newcomer, LOW for demanding listening.")),
            Map.entry("moodIntensity", level("How strongly the track's mood comes across.")),
            Map.entry("writtenBy", Map.of(
                "type", "string",
                "enum", Arrays.stream(JazzlogsCharacter.values()).map(Enum::name).toList(),
                "description", "Only tracks whose log this narrator wrote — your own name for your own logs."
            )),
            Map.entry("albumId", Map.of("type", "string", "description", "Only tracks on this album.")),
            Map.entry("artistId", Map.of("type", "string", "description", "Only tracks this artist plays on, as leader or sideman."))
        ),
        "required", List.of()
    );

    private final JsonMapper objectMapper;
    private final TrackSearchService trackSearchService;

    public FindTracksTool(TrackSearchService trackSearchService, JsonMapper objectMapper) {
        super(
            NAME,
            "Find tracks to recommend. Say what you are after in any combination of: lookingFor (a "
                + "description of the music), vocabulary tags (styles, moods, contexts, rhythms, "
                + "instruments), levels (energy, accessibility, moodIntensity), a narrator (writtenBy), and "
                + "a scope (albumId and/or artistId, from RESOLVE_JAZZLOGS_ENTITY) — at least one of them. "
                + "Tags, levels, narrator and scope decide which tracks are eligible: a track carrying any one of the style, mood, "
                + "context or rhythm tags qualifies, and matchedTags shows which it actually carries. "
                + "instruments is strict — a track must feature every instrument you list — so list one "
                + "only when the user wants to hear that instrument. A level is strict too — "
                + "only tracks at exactly that level — so set one only when the user asked for it in so many "
                + "words (\"something low-energy\", \"an easy way in\"); never infer it from a mood or "
                + "the hour, that is what tags and lookingFor are for. writtenBy is for when the "
                + "conversation is about someone's logs — yours or another narrator's. "
                + "lookingFor decides the order, and each candidate then comes with closestPassage, the "
                + "part of its log nearest to what you described. Returns a handful of candidates, best "
                + "first, each with its entityId, entityName, artist, album, the narrator who wrote its "
                + "log (writtenBy) and whether the user has already listened to it. A candidate is not "
                + "yet something to write about: read the one you choose with EDITORIAL_CONTENT.",
            "Buscando en el catálogo"
        );
        this.trackSearchService = trackSearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> schema() {
        return SCHEMA;
    }

    /** Runs one track search for the current user and hands back its candidates. */
    @Override
    public ToolExecutionResult execute(ToolCallRequest call, UUID userId) {
        Args args = parseArgs(call.argumentsJson());
        TrackSearchCriteria criteria = new TrackSearchCriteria(
            parseEnumList(args.styles(), StyleVocabulary.class, "styles"),
            parseEnumList(args.moods(), MoodVocabulary.class, "moods"),
            parseEnumList(args.contexts(), ContextVocabulary.class, "contexts"),
            parseEnumList(args.rhythms(), RhythmVocabulary.class, "rhythms"),
            parseEnumList(args.instruments(), InstrumentVocabulary.class, "instruments"),
            parseOptionalLevel(args.energy(), "energy"),
            parseOptionalLevel(args.accessibility(), "accessibility"),
            parseOptionalLevel(args.moodIntensity(), "moodIntensity"),
            parseOptionalNarrator(args.writtenBy()),
            parseOptionalId(args.albumId(), "albumId"),
            parseOptionalId(args.artistId(), "artistId"),
            args.lookingFor()
        );

        List<TrackCandidate> candidates = trackSearchService.search(criteria, userId);

        return new ToolExecutionResult(writeJson(new Output(buildContent(candidates), new Metadata(candidates))), true);
    }

    /** Parses the model's raw JSON args, rejecting malformed JSON. */
    private Args parseArgs(String argumentsJson) {
        try {
            return objectMapper.readValue(argumentsJson, Args.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(NAME + " arguments were not valid JSON: " + e.getMessage(), e);
        }
    }

    /** A scope id is optional — missing or blank means "no scope" — but one that is given must be a real id. */
    private static UUID parseOptionalId(String raw, String kind) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(kind + " is not a valid id: " + raw);
        }
    }

    /** A level is optional — missing means "any" — but one that is given must be a real {@link Level}. */
    private static Level parseOptionalLevel(String raw, String kind) {
        return raw == null || raw.isBlank() ? null : parseEnumValue(raw, Level.class, kind);
    }

    /** A narrator is optional — missing means "anyone's logs" — but one that is given must be one of the eight. */
    private static JazzlogsCharacter parseOptionalNarrator(String raw) {
        return raw == null || raw.isBlank() ? null : parseEnumValue(raw, JazzlogsCharacter.class, "writtenBy");
    }

    /** The summary line the model reads first — on an empty result, it says what to try instead of leaving a dead end. */
    private static String buildContent(List<TrackCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "No tracks matched. Try fewer or different tags, drop a level, the narrator or the album/artist scope, or describe what you are looking for instead.";
        }
        return "Found " + candidates.size() + " track(s), best match first.";
    }

    /** Serializes the tool's output — a failure here is our bug, not the model's, hence {@link IllegalStateException}. */
    private String writeJson(Output output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + NAME + " output", e);
        }
    }

    /** One vocabulary's schema entry: an optional list restricted to that vocabulary's codes. */
    private static <E extends Enum<E>> Map<String, Object> codesOf(Class<E> vocabulary) {
        List<String> codes = Arrays.stream(vocabulary.getEnumConstants()).map(Enum::name).toList();
        return Map.of("type", "array", "items", Map.of("type", "string", "enum", codes));
    }

    /** One level's schema entry: optional, one of {@link Level}'s names. */
    private static Map<String, Object> level(String description) {
        List<String> names = Arrays.stream(Level.values()).map(Enum::name).toList();
        return Map.of("type", "string", "enum", names, "description", description);
    }

    /** The model's raw tool-call arguments, before validation. */
    private record Args(
        String lookingFor,
        List<String> styles,
        List<String> moods,
        List<String> contexts,
        List<String> rhythms,
        List<String> instruments,
        String energy,
        String accessibility,
        String moodIntensity,
        String writtenBy,
        String albumId,
        String artistId
    ) {
    }

    /** The tool's structured payload, alongside {@link #buildContent}'s summary. */
    private record Metadata(List<TrackCandidate> candidates) {
    }

    /** The tool's full JSON result shape — conversational summary plus structured metadata. */
    private record Output(String content, Metadata metadata) {
    }
}
