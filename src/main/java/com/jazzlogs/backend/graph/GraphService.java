package com.jazzlogs.backend.graph;

import java.util.UUID;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes to Neo4j on behalf of other domains (User, and later Album/Artist/Rating).
 */
@Slf4j
@Service
@AllArgsConstructor
public class GraphService {

    private final Neo4jClient neo4jClient;

    public void createUserNode(UUID userId) {
        try {
            neo4jClient.query("MERGE (u:User {id: $id})")
                .bind(userId.toString()).to("id")
                .run();
        } catch (Exception ex) {
            log.error("Failed to create Neo4j :User node for id={}", userId, ex);
            throw new GraphWriteException("Failed to create Neo4j :User node for id=" + userId, ex);
        }
    }
}
