package com.jazzlogs.backend.syncfailure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class TrackRatedSyncRetryHandler implements SyncRetryHandler {

    private final GraphService graphService;

    @Override
    public void retry(Map<String, Object> payload) {
        UUID userId = UUID.fromString((String) payload.get("userId"));
        UUID trackId = UUID.fromString((String) payload.get("trackId"));
        BigDecimal rating = new BigDecimal((String) payload.get("rating"));
        Instant ratedAt = Instant.parse((String) payload.get("ratedAt"));

        graphService.rateTrack(userId, trackId, rating, ratedAt);
    }
}
