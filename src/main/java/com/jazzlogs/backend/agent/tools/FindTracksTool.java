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
        "properties", Map.of(
            "lookingFor", Map.of(
                "type", "string",
                "description", "The music you are after, described the way a log would describe it — its mood, "
                    + "atmosphere, character, how it is played (\"slow, spacious piano trio that never raises "
                    + "its voice\"). Candidates are ranked by how closely their logs speak to this. Describe "
                    + "only what the user actually indicated: do not invent detail to make it fuller, and "
                    + "leave out instructions like \"recommend\" or \"find\"."
            ),
            "styles", codesOf(StyleVocabulary.class),
            "moods", codesOf(MoodVocabulary.class),
            "contexts", codesOf(ContextVocabulary.class),
            "rhythms", codesOf(RhythmVocabulary.class),
            "instruments", codesOf(InstrumentVocabulary.class),
            "albumId", Map.of("type", "string", "description", "Only tracks on this album."),
            "artistId", Map.of("type", "string", "description", "Only tracks this artist plays on, as leader or sideman.")
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
                + "instruments), and a scope (albumId and/or artistId, from RESOLVE_JAZZLOGS_ENTITY) — at "
                + "least one of them. Tags and scope decide which tracks are eligible: a track carrying "
                + "any one of the tags qualifies, and matchedTags shows which it actually carries. "
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

    /** The summary line the model reads first — on an empty result, it says what to try instead of leaving a dead end. */
    private static String buildContent(List<TrackCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "No tracks matched. Try fewer or different tags, drop the album/artist scope, or describe what you are looking for instead.";
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

    /** The model's raw tool-call arguments, before validation. */
    private record Args(
        String lookingFor,
        List<String> styles,
        List<String> moods,
        List<String> contexts,
        List<String> rhythms,
        List<String> instruments,
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
