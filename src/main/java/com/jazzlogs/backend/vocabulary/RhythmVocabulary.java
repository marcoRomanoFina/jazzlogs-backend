package com.jazzlogs.backend.vocabulary;

public enum RhythmVocabulary implements EditorialVocabularyValue {

    MEDIUM_SWING("Medium Swing"),
    UP_TEMPO_SWING("Up-Tempo Swing"),
    SLOW_SWING("Slow Swing"),
    TWO_FEEL("Two-Feel"),
    SHUFFLE("Shuffle"),
    JAZZ_WALTZ("Jazz Waltz"),
    GYPSY_SWING("Gypsy Swing"),
    STRAIGHT_EIGHTH("Straight-Eighth"),
    FUNK_GROOVE("Funk Groove"),
    SOUL_JAZZ_BOOGALOO("Soul-Jazz Boogaloo"),
    ROCK_BEAT("Rock Beat"),
    HIP_HOP_BEAT("Hip-Hop Beat"),
    NEO_SOUL_GROOVE("Neo-Soul Groove"),
    DRUM_AND_BASS("Drum and Bass"),
    BOSSA_NOVA("Bossa Nova"),
    SAMBA("Samba"),
    AFRO_CUBAN("Afro-Cuban"),
    AFRO_CUBAN_6_8("Afro-Cuban 6/8"),
    MAMBO("Mambo"),
    CHA_CHA_CHA("Cha-Cha-Cha"),
    BOLERO("Bolero"),
    CALYPSO("Calypso"),
    TANGO("Tango"),
    FLAMENCO("Flamenco"),
    AFROBEAT("Afrobeat"),
    RUBATO("Rubato"),
    FREE_TIME("Free Time"),
    ODD_METER("Odd Meter"),
    VAMP("Vamp"),
    SECOND_LINE("Second Line"),
    GOSPEL_TRIPLET("Gospel Triplet");

    private final String label;

    RhythmVocabulary(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
