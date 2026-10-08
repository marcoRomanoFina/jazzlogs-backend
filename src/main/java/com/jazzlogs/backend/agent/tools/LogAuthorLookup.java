package com.jazzlogs.backend.agent.tools;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.editorial.TrackEditorialRepository.LogAuthorRow;

import lombok.AllArgsConstructor;

/**
 * Tells the agent's tools which narrator signed a track's log, so every tool
 * result can carry a {@code writtenBy} — the agent's persona needs it to tell
 * its own logs from a friend's and cite each accordingly (see {@code
 * NarratorPersonas}).
 */
@Component
@AllArgsConstructor
public class LogAuthorLookup {

    private final TrackEditorialRepository trackEditorialRepository;

    /** @return the author of each track's log, keyed by track id; tracks with no log are absent */
    public Map<UUID, JazzlogsCharacter> byTrackIds(Collection<UUID> trackIds) {
        if (trackIds.isEmpty()) {
            return Map.of();
        }
        return trackEditorialRepository.findAuthorsByTrackIds(trackIds).stream()
            .collect(Collectors.toMap(LogAuthorRow::getTrackId, LogAuthorRow::getByline));
    }

    public Optional<JazzlogsCharacter> byEditorialId(UUID editorialId) {
        return trackEditorialRepository.findBylineById(editorialId);
    }
}
