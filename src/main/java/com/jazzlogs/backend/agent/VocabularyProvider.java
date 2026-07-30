package com.jazzlogs.backend.agent;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.EditorialVocabularyValue;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;

// Formats the controlled-vocabulary enums into the comma-separated, readable
// lists the CANONICAL FILTER VOCABULARY prompt section needs — one place so
// ChatContextBuilder never hardcodes a vocabulary list itself.
@Component
public class VocabularyProvider {

    public String styles() {
        return labels(StyleVocabulary.values());
    }

    public String moods() {
        return labels(MoodVocabulary.values());
    }

    public String rhythms() {
        return labels(RhythmVocabulary.values());
    }

    public String contexts() {
        return labels(ContextVocabulary.values());
    }

    public String instruments() {
        return labels(InstrumentVocabulary.values());
    }

    // BlockContentCategory has no EditorialVocabularyValue label — humanize the
    // enum constant itself (HISTORICAL_CONTEXT -> "Historical Context").
    public String editorialCategories() {
        return Arrays.stream(BlockContentCategory.values())
            .map(VocabularyProvider::humanize)
            .collect(Collectors.joining(", "));
    }

    private static String labels(EditorialVocabularyValue[] values) {
        return Arrays.stream(values).map(EditorialVocabularyValue::getLabel).collect(Collectors.joining(", "));
    }

    private static String humanize(Enum<?> value) {
        return Arrays.stream(value.name().split("_"))
            .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(" "));
    }
}
