package com.jazzlogs.backend.vocabulary;

public enum StyleVocabulary implements EditorialVocabularyValue {

    RAGTIME("Ragtime"),
    NEW_ORLEANS_JAZZ("New Orleans Jazz"),
    DIXIELAND("Dixieland"),
    SWING("Swing"),
    BIG_BAND("Big Band"),
    GYPSY_JAZZ("Gypsy Jazz"),
    BEBOP("Bebop"),
    HARD_BOP("Hard Bop"),
    COOL_JAZZ("Cool Jazz"),
    WEST_COAST_JAZZ("West Coast Jazz"),
    POST_BOP("Post-Bop"),
    MODAL_JAZZ("Modal Jazz"),
    JAZZ_BLUES("Jazz Blues"),
    SOUL_JAZZ("Soul Jazz"),
    JAZZ_FUNK("Jazz-Funk"),
    ACID_JAZZ("Acid Jazz"),
    FREE_JAZZ("Free Jazz"),
    AVANT_GARDE_JAZZ("Avant-Garde Jazz"),
    SPIRITUAL_JAZZ("Spiritual Jazz"),
    THIRD_STREAM("Third Stream"),
    JAZZ_FUSION("Jazz Fusion"),
    SMOOTH_JAZZ("Smooth Jazz"),
    CONTEMPORARY_JAZZ("Contemporary Jazz"),
    NU_JAZZ("Nu Jazz"),
    CHAMBER_JAZZ("Chamber Jazz"),
    LATIN_JAZZ("Latin Jazz"),
    AFRO_CUBAN_JAZZ("Afro-Cuban Jazz"),
    BOSSA_NOVA("Bossa Nova"),
    AFROBEAT("Afrobeat"),
    ETHIO_JAZZ("Ethio-Jazz"),
    J_JAZZ("J-Jazz"),
    VOCAL_JAZZ("Vocal Jazz");

    private final String label;

    StyleVocabulary(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
