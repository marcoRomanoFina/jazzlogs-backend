package com.jazzlogs.backend.debug;

import java.util.Map;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Neo4jPingController {

    private final Neo4jClient neo4jClient;

    public Neo4jPingController(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @GetMapping("/public/debug/neo4j-ping")
    public Map<String, Object> ping() {
        Map<String, Object> row = neo4jClient.query("RETURN 1 AS result")
            .fetch()
            .one()
            .orElseThrow(() -> new IllegalStateException("Neo4j returned no rows for ping query"));

        return Map.of("status", "ok", "result", row.get("result"));
    }
}
