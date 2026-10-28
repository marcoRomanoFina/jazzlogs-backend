package com.jazzlogs.backend.vocabulary;

public enum MoodVocabulary implements EditorialVocabularyValue {

    // High energy, positive
    ENERGETIC("Energetic"),
    FIERY("Fiery"),
    JOYFUL("Joyful"),
    PLAYFUL("Playful"),
    TRIUMPHANT("Triumphant"),
    EXUBERANT("Exuberant"),
    UPLIFTING("Uplifting"),
    CONFIDENT("Confident"),

    // High energy, tense
    FRENETIC("Frenetic"),
    CHAOTIC("Chaotic"),
    URGENT("Urgent"),
    TENSE("Tense"),
    REBELLIOUS("Rebellious"),
    MENACING("Menacing"),

    // Warm, romantic
    WARM("Warm"),
    RELAXED("Relaxed"),
    COOL("Cool"),
    ROMANTIC("Romantic"),
    SOOTHING("Soothing"),
    INTIMATE("Intimate"),
    SENSUAL("Sensual"),

    // Melancholic, introspective
    DREAMY("Dreamy"),
    MELANCHOLIC("Melancholic"),
    LATE_NIGHT("Late-Night"),
    SOMBER("Somber"),
    LONELY("Lonely"),
    WISTFUL("Wistful"),
    BROODING("Brooding"),
    NOIR("Noir"),
    INTROSPECTIVE("Introspective"),
    MYSTERIOUS("Mysterious"),

    // Transcendent
    SPIRITUAL("Spiritual"),
    ETHEREAL("Ethereal"),
    SOULFUL("Soulful"),
    HYPNOTIC("Hypnotic"),
    CEREBRAL("Cerebral"),

    // Sonic texture
    ELEGANT("Elegant"),
    SMOOTH("Smooth"),
    GROOVY("Groovy"),
    GRITTY("Gritty"),
    SPACIOUS("Spacious"),
    BLUESY("Bluesy");

    private final String label;

    MoodVocabulary(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
