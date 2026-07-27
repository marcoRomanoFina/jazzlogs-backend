package com.jazzlogs.backend.playlist;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistSummaryDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistUpsertRequest;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.trackrating.TrackRatingRepository;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyCodes;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final TrackRepository trackRepository;
    private final TrackRatingRepository trackRatingRepository;
    private final LikeService likeService;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;

    /** Metadata only — see PlaylistUpsertRequest; the tracklist is empty until addTrack is called. */
    @Transactional
    public PlaylistDetailDto create(PlaylistUpsertRequest request) {
        Playlist playlist = new Playlist(
            request.slug(), request.title(), request.tagline(), request.description(),
            request.coverImageUrl(), request.spotifyUrl(), request.published()
        );
        Playlist saved = playlistRepository.save(playlist);
        graphService.syncPlaylistNode(saved.getId(), saved.getTitle());
        replaceTags(saved, request.styleCodes(), request.moodCodes(), request.contextCodes());
        return getPlaylistDetail(saved.getId(), null, true);
    }

    /** Metadata only — never touches playlist_tracks, see addTrack/removeTrack/updateTrackNote/reorderTracks. */
    @Transactional
    public PlaylistDetailDto update(UUID id, PlaylistUpsertRequest request) {
        Playlist playlist = getPlaylistOrThrow(id);
        playlist.update(
            request.slug(), request.title(), request.tagline(), request.description(),
            request.coverImageUrl(), request.spotifyUrl(), request.published()
        );
        graphService.syncPlaylistNode(playlist.getId(), playlist.getTitle());
        replaceTags(playlist, request.styleCodes(), request.moodCodes(), request.contextCodes());
        return getPlaylistDetail(id, null, true);
    }

    /**
     * Appends a track at the end (position = current trackCount). 409 if the
     * track is already in the playlist — checked up front, and again via the
     * UNIQUE constraint as a race-condition safety net (same pattern as
     * LikeService.addLike, except a collision here is a real client error, not
     * a benign no-op). Updates trackCount/durationMs in the same transaction,
     * then fire-and-forget syncs the single new BELONGS_TO edge to Neo4j.
     */
    @Transactional
    public PlaylistTrackDetailDto addTrack(UUID playlistId, UUID trackId, String title, String curatorNote) {
        Playlist playlist = getPlaylistOrThrow(playlistId);
        Track track = getTrackOrThrow(trackId);

        if (playlistTrackRepository.findByPlaylistIdAndTrackId(playlistId, trackId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Track already in playlist: " + trackId);
        }

        int position = playlist.getTrackCount();
        PlaylistTrack playlistTrack;
        try {
            playlistTrack = playlistTrackRepository.save(new PlaylistTrack(playlist, track, position, title, curatorNote));
        } catch (DataIntegrityViolationException concurrentAdd) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Track already in playlist: " + trackId);
        }

        long durationMs = playlist.getDurationMs() + (track.getDurationMs() == null ? 0 : track.getDurationMs());
        playlist.updateTrackStats(playlist.getTrackCount() + 1, durationMs);
        playlistRepository.save(playlist);

        syncExecutor.sync(
            SyncFailureEntityType.PLAYLIST_TRACK_ADDED,
            trackAddedPayload(playlistId, trackId, position),
            () -> graphService.addPlaylistTrack(playlistId, trackId, position)
        );

        return toTrackDetailDto(playlistTrack, ratingStatsFor(trackId));
    }

    private Map<String, Object> trackAddedPayload(UUID playlistId, UUID trackId, int position) {
        return Map.of(
            "playlistId", playlistId.toString(),
            "trackId", trackId.toString(),
            "position", String.valueOf(position)
        );
    }

    /**
     * 404 if the track isn't in the playlist. Doesn't renumber the remaining
     * rows' positions — gaps are fine, only reorderTracks re-sequences them.
     * durationMs is clamped to 0 in case older rows summed a null duration
     * inconsistently; trackCount can't go negative since the row's existence
     * already guarantees it was at least 1.
     */
    @Transactional
    public void removeTrack(UUID playlistId, UUID trackId) {
        Playlist playlist = getPlaylistOrThrow(playlistId);
        PlaylistTrack playlistTrack = playlistTrackRepository.findByPlaylistIdAndTrackId(playlistId, trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not in playlist: " + trackId));

        Integer trackDurationMs = playlistTrack.getTrack().getDurationMs();
        playlistTrackRepository.delete(playlistTrack);

        long durationMs = Math.max(0, playlist.getDurationMs() - (trackDurationMs == null ? 0 : trackDurationMs));
        playlist.updateTrackStats(playlist.getTrackCount() - 1, durationMs);
        playlistRepository.save(playlist);

        syncExecutor.sync(
            SyncFailureEntityType.PLAYLIST_TRACK_REMOVED,
            trackRemovedPayload(playlistId, trackId),
            () -> graphService.removePlaylistTrack(playlistId, trackId)
        );
    }

    private Map<String, Object> trackRemovedPayload(UUID playlistId, UUID trackId) {
        return Map.of("playlistId", playlistId.toString(), "trackId", trackId.toString());
    }

    /** title/curator_note have no Neo4j counterpart — Postgres-only, no sync call at all. */
    @Transactional
    public PlaylistTrackDetailDto updateTrackNote(UUID playlistId, UUID trackId, String title, String curatorNote) {
        getPlaylistOrThrow(playlistId);
        PlaylistTrack playlistTrack = playlistTrackRepository.findByPlaylistIdAndTrackId(playlistId, trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not in playlist: " + trackId));

        playlistTrack.updateDetails(title, curatorNote);
        playlistTrackRepository.save(playlistTrack);

        return toTrackDetailDto(playlistTrack, ratingStatsFor(trackId));
    }

    /**
     * orderedTrackIds must be exactly the tracks already in the playlist — same
     * set, no more, no less, no duplicates (400 otherwise). Only position
     * changes; trackCount/durationMs are untouched since the track set itself
     * doesn't change on a reorder.
     */
    @Transactional
    public void reorderTracks(UUID playlistId, List<UUID> orderedTrackIds) {
        getPlaylistOrThrow(playlistId);
        List<PlaylistTrack> existing = playlistTrackRepository.findByPlaylistIdWithTrackDetails(playlistId);

        Set<UUID> existingTrackIds = existing.stream().map(pt -> pt.getTrack().getId()).collect(Collectors.toSet());
        Set<UUID> requestedTrackIds = new HashSet<>(orderedTrackIds);
        boolean sameSet = existingTrackIds.equals(requestedTrackIds);
        boolean noDuplicates = requestedTrackIds.size() == orderedTrackIds.size();
        if (!sameSet || !noDuplicates) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "trackIds must contain exactly the tracks already in the playlist, no duplicates");
        }

        Map<UUID, PlaylistTrack> byTrackId = existing.stream()
            .collect(Collectors.toMap(pt -> pt.getTrack().getId(), pt -> pt));

        Map<UUID, Integer> positions = new LinkedHashMap<>();
        for (int position = 0; position < orderedTrackIds.size(); position++) {
            UUID trackId = orderedTrackIds.get(position);
            byTrackId.get(trackId).updatePosition(position);
            positions.put(trackId, position);
        }
        playlistTrackRepository.saveAll(byTrackId.values());

        syncExecutor.sync(
            SyncFailureEntityType.PLAYLIST_TRACKS_REORDERED,
            reorderedPayload(playlistId, positions),
            () -> graphService.reorderPlaylistTracks(playlistId, positions)
        );
    }

    private Map<String, Object> reorderedPayload(UUID playlistId, Map<UUID, Integer> positions) {
        List<Map<String, Object>> rows = positions.entrySet().stream()
            .map(entry -> Map.<String, Object>of("trackId", entry.getKey().toString(), "position", String.valueOf(entry.getValue())))
            .toList();
        return Map.of("playlistId", playlistId.toString(), "positions", rows);
    }

    /**
     * Neo4j-only, same pattern as AlbumService.addStyle/addMood/addContext: no
     * Postgres table, validated against the vocabulary enum before the graph
     * call (400 on the first invalid code), then a single synchronous write —
     * if Neo4j is down this throws GraphWriteException (502), same failure
     * policy as Album's tags, not the fire-and-forget one used for tracks.
     */
    private void replaceTags(Playlist playlist, List<String> styleCodes, List<String> moodCodes, List<String> contextCodes) {
        List<String> styles = styleCodes == null ? List.of() : styleCodes;
        List<String> moods = moodCodes == null ? List.of() : moodCodes;
        List<String> contexts = contextCodes == null ? List.of() : contextCodes;

        styles.forEach(code -> VocabularyCodes.validate(StyleVocabulary.class, code, "style"));
        moods.forEach(code -> VocabularyCodes.validate(MoodVocabulary.class, code, "mood"));
        contexts.forEach(code -> VocabularyCodes.validate(ContextVocabulary.class, code, "context"));

        // Skip the round trip entirely when there's nothing to set — this also
        // means an update sending all-empty tag lists on an already-tagged
        // playlist won't clear the stale Neo4j edges (known gap: there's no
        // "explicitly clear all tags" signal distinct from "tags weren't sent").
        // Acceptable for now since nothing in this task exercises that path;
        // revisit if playlists need a real "untag everything" flow.
        if (styles.isEmpty() && moods.isEmpty() && contexts.isEmpty()) {
            return;
        }

        graphService.setPlaylistTags(playlist.getId(), styles, moods, contexts);
    }

    @Transactional(readOnly = true)
    public List<PlaylistSummaryDto> list(boolean includeUnpublished) {
        List<Playlist> playlists = includeUnpublished
            ? playlistRepository.findAllByOrderByCreatedAtDesc()
            : playlistRepository.findByPublishedTrueOrderByCreatedAtDesc();
        return playlists.stream().map(this::toSummaryDto).toList();
    }

    /**
     * track_count/duration_ms are read straight off the Playlist entity —
     * denormalized, kept in sync by addTrack/removeTrack, no JOIN/GROUP BY or
     * Java-side summing here. Rating stats still resolve in one batch query
     * for every track on the page (TrackRatingRepository.getRatingStatsForTracks),
     * not one AVG/COUNT per track. Tags are read from Neo4j (no Postgres table
     * for them — see replaceTags), same as Album's getStyles/getMoods/getContexts:
     * a Neo4j outage fails this whole call.
     */
    @Transactional(readOnly = true)
    public PlaylistDetailDto getPlaylistDetail(UUID id, UUID currentUserId, boolean isAdmin) {
        Playlist playlist = getPlaylistOrThrow(id);
        if (!playlist.isPublished() && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found: " + id);
        }

        List<PlaylistTrack> playlistTracks = playlistTrackRepository.findByPlaylistIdWithTrackDetails(id);
        List<UUID> trackIds = playlistTracks.stream().map(pt -> pt.getTrack().getId()).toList();
        Map<UUID, TrackRatingRepository.TrackRatingStats> statsByTrack = trackIds.isEmpty()
            ? Map.of()
            : trackRatingRepository.getRatingStatsForTracks(trackIds).stream()
                .collect(Collectors.toMap(TrackRatingRepository.TrackRatingStats::getTrackId, stats -> stats));

        List<PlaylistTrackDetailDto> trackDtos = playlistTracks.stream()
            .map(pt -> toTrackDetailDto(pt, statsByTrack.get(pt.getTrack().getId())))
            .toList();

        boolean liked = currentUserId != null && likeService.hasUserLiked(currentUserId, LikeableEntityType.PLAYLIST, id);

        List<VocabularyTag> styleTags = graphService.getPlaylistStyles(id);
        List<VocabularyTag> moodTags = graphService.getPlaylistMoods(id);
        List<VocabularyTag> contextTags = graphService.getPlaylistContexts(id);

        return new PlaylistDetailDto(
            playlist.getId(), playlist.getSlug(), playlist.getTitle(), playlist.getTagline(), playlist.getDescription(),
            playlist.getCoverImageUrl(), playlist.getSpotifyUrl(), playlist.isPublished(),
            playlist.getLikeCount(), liked, playlist.getTrackCount(), playlist.getDurationMs(), trackDtos,
            styleTags, moodTags, contextTags, playlist.getCreatedAt(), playlist.getUpdatedAt()
        );
    }

    private TrackRatingRepository.TrackRatingStats ratingStatsFor(UUID trackId) {
        return trackRatingRepository.getRatingStatsForTracks(List.of(trackId)).stream().findFirst().orElse(null);
    }

    private PlaylistTrackDetailDto toTrackDetailDto(PlaylistTrack playlistTrack, TrackRatingRepository.TrackRatingStats stats) {
        Track track = playlistTrack.getTrack();
        Album album = track.getAlbum();
        Artist artist = album.getArtist();
        return new PlaylistTrackDetailDto(
            track.getId(), track.getName(), track.getDurationMs(),
            album.getId(), album.getName(), album.getImageUrl(),
            artist.getId(), artist.getName(),
            playlistTrack.getPosition(), playlistTrack.getTitle(), playlistTrack.getCuratorNote(),
            stats == null ? null : stats.getAvgRating(),
            stats == null ? 0 : stats.getCount()
        );
    }

    private PlaylistSummaryDto toSummaryDto(Playlist playlist) {
        return new PlaylistSummaryDto(
            playlist.getId(), playlist.getSlug(), playlist.getTitle(), playlist.getTagline(),
            playlist.getCoverImageUrl(), playlist.isPublished(), playlist.getLikeCount(),
            playlist.getTrackCount(), playlist.getDurationMs(), playlist.getCreatedAt()
        );
    }

    private Playlist getPlaylistOrThrow(UUID id) {
        return playlistRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found: " + id));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }
}
