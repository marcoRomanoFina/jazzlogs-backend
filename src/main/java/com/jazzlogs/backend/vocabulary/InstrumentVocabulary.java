package com.jazzlogs.backend.vocabulary;

public enum InstrumentVocabulary implements EditorialVocabularyValue {

    // Saxophones
    ALTO_SAXOPHONE("Alto Saxophone"),
    TENOR_SAXOPHONE("Tenor Saxophone"),
    SOPRANO_SAXOPHONE("Soprano Saxophone"),
    BARITONE_SAXOPHONE("Baritone Saxophone"),

    // Brass
    TRUMPET("Trumpet"),
    CORNET("Cornet"),
    TROMBONE("Trombone"),
    FLUGELHORN("Flugelhorn"),
    FRENCH_HORN("French Horn"),
    TUBA("Tuba"),

    // Woodwinds
    CLARINET("Clarinet"),
    FLUTE("Flute"),
    BASS_CLARINET("Bass Clarinet"),
    OBOE("Oboe"),

    // Keys
    PIANO("Piano"),
    ELECTRIC_PIANO("Electric Piano"),
    ORGAN("Organ"),
    SYNTHESIZER("Synthesizer"),

    // Strings
    DOUBLE_BASS("Double Bass"),
    ELECTRIC_BASS("Electric Bass"),
    GUITAR("Guitar"),
    ELECTRIC_GUITAR("Electric Guitar"),
    VIOLIN("Violin"),
    CELLO("Cello"),
    HARP("Harp"),
    BANJO("Banjo"),

    // Percussion
    DRUMS("Drums"),
    PERCUSSION("Percussion"),
    VIBRAPHONE("Vibraphone"),
    MARIMBA("Marimba"),
    CONGAS("Congas"),
    BONGOS("Bongos"),

    // Voice
    VOCALS("Vocals"),

    // Other
    HARMONICA("Harmonica"),
    ACCORDION("Accordion");

    private final String label;

    InstrumentVocabulary(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
