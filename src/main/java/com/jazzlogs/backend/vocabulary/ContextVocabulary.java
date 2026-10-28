package com.jazzlogs.backend.vocabulary;

public enum ContextVocabulary implements EditorialVocabularyValue {

    DEEP_FOCUS("Deep Focus"),
    CREATIVE_WORK("Creative Work"),
    BACKGROUND_AMBIENCE("Background Ambience"),
    CAFE_AMBIENCE("Cafe Ambience"),
    READING_A_BOOK("Reading a Book"),
    LATE_NIGHT_STUDY("Late-Night Study"),
    DINNER_PARTY("Dinner Party"),
    COCKTAIL_HOUR("Cocktail Hour"),
    INTIMATE_DATE("Intimate Date"),
    CASUAL_HANG("Casual Hang"),
    SPEAKEASY_VIBE("Speakeasy Vibe"),
    ELEGANT_GATHERING("Elegant Gathering"),
    GAMING_AND_RPGS("Gaming & RPGs"),
    LATE_NIGHT_WHISKEY("Late-Night Whiskey"),
    LATE_NIGHT_SMOKE("Late-Night Smoke"),
    AFTER_HOURS("After-Hours"),
    RAINY_DAY("Rainy Day"),
    WINTER_FIREPLACE("Winter Fireplace"),
    AUTUMN_AFTERNOON("Autumn Afternoon"),
    SUMMER_NIGHT("Summer Night"),
    BEACH_AND_SUN("Beach & Sun"),
    GOLDEN_HOUR("Golden Hour"),
    SUNRISE("Sunrise"),
    MIDNIGHT_CITY("Midnight City"),
    CABIN_IN_THE_WOODS("Cabin in the Woods"),
    LAZY_SUNDAY("Lazy Sunday"),
    UNWINDING("Unwinding"),
    SOLITARY_REFLECTION("Solitary Reflection"),
    BEDTIME("Bedtime"),
    BATH_AND_UNWIND("Bath & Unwind"),
    MORNING_COFFEE("Morning Coffee"),
    COOKING_DINNER("Cooking Dinner"),
    SOLO_COOKING("Solo Cooking"),
    HOUSE_CHORES("House Chores"),
    WAKING_UP("Waking Up"),
    GETTING_READY("Getting Ready"),
    WORKOUT_AND_CARDIO("Workout & Cardio"),
    MORNING_COMMUTE("Morning Commute"),
    LATE_COMMUTE("Late Commute"),
    LATE_NIGHT_DRIVE("Late-Night Drive"),
    URBAN_NIGHT_WALK("Urban Night Walk"),
    TRAIN_JOURNEY("Train Journey"),
    AIRPLANE_FLIGHT("Airplane Flight"),
    SCENIC_WALK("Scenic Walk"),
    ACTIVE_LISTENING("Active Listening"),
    VINYL_SESSION("Vinyl Session"),
    STUDYING_THE_GREATS("Studying the Greats"),
    WRITING_JOURNAL("Writing Journal"),
    DATE_NIGHT_AT_HOME("Date Night at Home"),
    NIGHT_OUT_JAZZ_CLUB("Night Out at a Jazz Club");

    private final String label;

    ContextVocabulary(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
