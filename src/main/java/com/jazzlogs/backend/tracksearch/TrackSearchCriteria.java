package com.jazzlogs.backend.tracksearch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jazzlogs.backend.graph.VocabularyDimension;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

/**
 * What a track search can be asked for — see {@link TrackSearchService}.
 * Three independent kinds of input, any combination of which is valid as
 * long as there is at least one:
 * <ul>
 *   <li><b>tags</b> — vocabulary codes the track should carry;</li>
 *   <li><b>scope</b> — an album and/or an artist to stay inside;</li>
 *   <li><b>lookingFor</b> — a phrase describing the music, matched against
 *       what the logs actually say.</li>
 * </ul>
 * Normalizes itself: a missing tag list is an empty one and a blank phrase
 * is no phrase, so nothing downstream has to check for null.
 *
 * @param artistId an artist who plays on the track, as leader or sideman
 */
public record TrackSearchCriteria(
    List<StyleVocabulary> styles,
    List<MoodVocabulary> moods,
    List<ContextVocabulary> contexts,
    List<RhythmVocabulary> rhythms,
    List<InstrumentVocabulary> instruments,
    UUID albumId,
    UUID artistId,
    String lookingFor
) {

    public TrackSearchCriteria {
        styles = styles == null ? List.of() : List.copyOf(styles);
        moods = moods == null ? List.of() : List.copyOf(moods);
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        rhythms = rhythms == null ? List.of() : List.copyOf(rhythms);
        instruments = instruments == null ? List.of() : List.copyOf(instruments);
        lookingFor = lookingFor == null || lookingFor.isBlank() ? null : lookingFor.strip();
    }

    public boolean hasTags() {
        return !(styles.isEmpty() && moods.isEmpty() && contexts.isEmpty() && rhythms.isEmpty() && instruments.isEmpty());
    }

    public boolean hasScope() {
        return albumId != null || artistId != null;
    }

    public boolean hasPhrase() {
        return lookingFor != null;
    }

    /** Whether there is nothing here to search by at all. */
    public boolean isEmpty() {
        return !hasTags() && !hasScope() && !hasPhrase();
    }

    /** The requested tags as the code strings the graph stores, per dimension. */
    public Map<VocabularyDimension, List<String>> tagCodes() {
        Map<VocabularyDimension, List<String>> codes = new EnumMap<>(VocabularyDimension.class);
        codes.put(VocabularyDimension.STYLE, namesOf(styles));
        codes.put(VocabularyDimension.MOOD, namesOf(moods));
        codes.put(VocabularyDimension.CONTEXT, namesOf(contexts));
        codes.put(VocabularyDimension.RHYTHM, namesOf(rhythms));
        codes.put(VocabularyDimension.INSTRUMENT, namesOf(instruments));
        return codes;
    }

    private static List<String> namesOf(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }
}
