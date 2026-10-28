package com.jazzlogs.backend.vocabulary;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Syncs the controlled editorial vocabulary enums into Neo4j as nodes, one
 * label per vocabulary (:Style, :Rhythm, :Mood, :Context, :Instrument).
 * Runs on every app startup; MERGE keeps it idempotent.
 */
@Slf4j
@Component
@Profile("!test")
@AllArgsConstructor
public class VocabularySeeder implements ApplicationRunner {

    private final Neo4jClient neo4jClient;

    @Override
    public void run(ApplicationArguments args) {
        seed("Style", StyleVocabulary.values());
        seed("Rhythm", RhythmVocabulary.values());
        seed("Mood", MoodVocabulary.values());
        seed("Context", ContextVocabulary.values());
        seed("Instrument", InstrumentVocabulary.values());
    }

    private <E extends Enum<E> & EditorialVocabularyValue> void seed(String nodeLabel, E[] values) {
        List<Map<String, Object>> rows = Arrays.stream(values)
            .map(value -> Map.<String, Object>of("code", value.name(), "label", value.getLabel()))
            .toList();

        neo4jClient.query(
                "UNWIND $rows AS row MERGE (n:" + nodeLabel + " {code: row.code}) SET n.label = row.label")
            .bind(rows).to("rows")
            .run();

        log.info("Synced {} {} nodes", rows.size(), nodeLabel);
    }
}
