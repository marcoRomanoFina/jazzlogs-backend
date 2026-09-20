package com.jazzlogs.backend.playlist;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.listen.ListenableEntityType;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistSummaryDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistUpsertRequest;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.saveditem.SavedItemService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.trackrating.TrackRating;
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
    private final ListenService listenService;
    private final SavedItemService savedItemService;
    private final NoteService noteService;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;
    private final ImageStorageService imageStorageService;
    private final EntityManager entityManager;

    /**
     * Metadata only — see PlaylistUpsertRequest; the tracklist is empty until
     * addTrack is called. Returns the saved entity, not a DTO — the
     * controller responds 201 with just its id, so there's no caller left
     * that needs the full getPlaylistDetail fan-out here.
     *
     * @throws ResponseStatusException 409 if another playlist already has this title
     */
    @Transactional
    public Playlist create(PlaylistUpsertRequest request) {
        assertTitleAvailable(request.title(), null);
        Playlist playlist = new Playlist(
            request.title(), request.tagline(), request.description(),
            request.coverImageUrl(), request.spotifyUrl(), request.type()
        );
        Playlist saved = playlistRepository.save(playlist);
        flushOrThrowOnTitleConflict(request.title());
        graphService.syncPlaylistNode(saved.getId(), saved.getTitle());
        replaceTags(saved, request.styleCodes(), request.moodCodes(), request.contextCodes());
        return saved;
    }

    /**
     * Metadata only — never touches playlist_tracks or published, see
     * addTrack/removeTrack/updateTrackNote/reorderTracks/publish/unpublish.
     *
     * @throws ResponseStatusException 404 if the playlist doesn't exist, 409
     *                                  if another playlist already has this title
     */
    @Transactional
    public PlaylistDetailDto update(UUID id, PlaylistUpsertRequest request) {
        Playlist playlist = getPlaylistOrThrow(id);
        assertTitleAvailable(request.title(), id);
        playlist.update(
            request.title(), request.tagline(), request.description(),
            request.coverImageUrl(), request.spotifyUrl(), request.type()
        );
        flushOrThrowOnTitleConflict(request.title());
        graphService.syncPlaylistNode(playlist.getId(), playlist.getTitle());
        replaceTags(playlist, request.styleCodes(), request.moodCodes(), request.contextCodes());
        return getPlaylistDetail(id, null, true);
    }

    /**
     * Up-front check — the common-path way create/update reject a duplicate
     * title, with a clean message. {@code excludingPlaylistId} lets update
     * re-save a playlist under its own unchanged title without tripping over
     * itself; pass {@code null} from create, where nothing should be excluded.
     *
     * @throws ResponseStatusException 409 if a different playlist already has this title
     */
    private void assertTitleAvailable(String title, UUID excludingPlaylistId) {
        playlistRepository.findByTitle(title)
            .filter(existing -> !existing.getId().equals(excludingPlaylistId))
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A playlist titled \"" + title + "\" already exists");
            });
    }

    /**
     * {@code flush()} forces the pending INSERT/UPDATE to run right here
     * instead of at commit time — same reasoning as LikeService.addLike:
     * without it, a title collision from a concurrent request (that snuck in
     * between {@link #assertTitleAvailable} and this point) would blow up
     * much later, at commit, well outside this try/catch, instead of
     * surfacing here as a clean 409. uq_playlists_title (V29) is what
     * actually guarantees no two playlists share a title under concurrent
     * writes; this is just what turns that into a 409 instead of a 500.
     */
    private void flushOrThrowOnTitleConflict(String title) {
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException | ConstraintViolationException concurrentTitle) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "A playlist titled \"" + title + "\" already exists", concurrentTitle
            );
        }
    }

    /**
     * Hard-deletes this playlist and everything that points at it:
     * <ul>
     *   <li>its playlist_tracks rows — deleted up front (also backed by the
     *       FK's cascade, see V28, as a safety net for anything that ever
     *       deletes a playlist row some other way)</li>
     *   <li>any likes/listens/saved_items referencing it — polymorphic
     *       tables with no FK to cascade off of, so cleaned up explicitly</li>
     *   <li>its Neo4j node and every relationship on it (BELONGS_TO, tag
     *       edges, LISTENED, ...) via {@code DETACH DELETE}, fire-and-forget
     *       with retry like every other Neo4j write here</li>
     * </ul>
     *
     * @param playlistId the playlist to delete
     * @throws ResponseStatusException 404 if it doesn't exist
     */
    @Transactional
    public void delete(UUID playlistId) {
        getPlaylistOrThrow(playlistId);
        playlistTrackRepository.deleteByPlaylistId(playlistId);
        likeService.deleteAllFor(LikeableEntityType.PLAYLIST, playlistId);
        listenService.deleteAllFor(ListenableEntityType.PLAYLIST, playlistId);
        savedItemService.deleteAllFor(SaveableEntityType.PLAYLIST, playlistId);
        playlistRepository.deleteById(playlistId);

        syncExecutor.sync(
            SyncFailureEntityType.PLAYLIST_DELETED,
            Map.of("playlistId", playlistId.toString()),
            () -> graphService.deletePlaylistNode(playlistId)
        );
    }

    /** Publishes this playlist, making it visible to non-admins. */
    @Transactional
    public void publish(UUID playlistId) {
        getPlaylistOrThrow(playlistId).publish();
    }

    /**
     * Reverts this playlist to draft — a no-op if it was already a draft.
     * Also clears the featured flag if this was THE featured playlist:
     * setFeatured requires published (see {@link #setFeatured}), so
     * unpublishing keeps that invariant true going the other way too,
     * instead of leaving a featured-but-unpublished row that only
     * {@link #getFeatured}'s admin check papers over.
     */
    @Transactional
    public void unpublish(UUID playlistId) {
        getPlaylistOrThrow(playlistId).unpublish();
        playlistRepository.unmarkFeatured(playlistId);
    }

    /**
     * Uploads a new cover image for this playlist — see {@link
     * ImageStorageService#upload}. One object per playlist ({@code
     * playlists/{id}/cover.<ext>}), so re-uploading overwrites the old
     * cover instead of leaving it orphaned in storage.
     *
     * @param id   the playlist
     * @param file the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setCoverImage(UUID id, MultipartFile file) {
        Playlist playlist = getPlaylistOrThrow(id);
        String url = imageStorageService.upload("playlists/" + id + "/cover", file);
        playlist.updateCoverImageUrl(url);
    }

    /**
     * Marks this playlist as THE featured one, unfeaturing whichever one (if
     * any) held that spot before. {@code idx_playlists_only_one_featured}
     * (see V26) is what actually guarantees at most one stays featured
     * under concurrent calls — same reasoning as {@code AlbumService#setFeatured}.
     *
     * @param playlistId the playlist
     * @throws ResponseStatusException 404 if the playlist doesn't exist, 409
     *                                  if it isn't published yet (a featured
     *                                  playlist non-admins can't see would be
     *                                  a broken link) or if a concurrent call
     *                                  already featured a different playlist
     */
    @Transactional
    public void setFeatured(UUID playlistId) {
        Playlist playlist = getPlaylistOrThrow(playlistId);
        if (!playlist.isPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Playlist isn't published yet, can't be featured");
        }
        playlistRepository.clearFeatured();
        try {
            playlistRepository.markFeatured(playlistId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Another playlist was just featured concurrently — try again", e
            );
        }
    }

    /** Removes this playlist from being THE featured one — a no-op if it wasn't. */
    @Transactional
    public void unsetFeatured(UUID playlistId) {
        getPlaylistOrThrow(playlistId);
        playlistRepository.unmarkFeatured(playlistId);
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

        return toTrackDetailDto(playlistTrack, ratingStatsFor(trackId), null, false, List.of());
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

        return toTrackDetailDto(playlistTrack, ratingStatsFor(trackId), null, false, List.of());
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
    public List<PlaylistSummaryDto> list(boolean includeUnpublished, UUID currentUserId) {
        List<Playlist> playlists = includeUnpublished
            ? playlistRepository.findAllByOrderByCreatedAtDesc()
            : playlistRepository.findByPublishedTrueOrderByCreatedAtDesc();
        return toSummaryDtos(playlists, currentUserId);
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

        Set<UUID> listenedTrackIds = currentUserId == null ? Set.of() : listenService.getListenedTrackIds(currentUserId, trackIds);
        Map<UUID, List<NoteDto>> myNotesByTrack = currentUserId == null ? Map.of() : noteService.getMyNotesForTracks(trackIds, currentUserId);
        Map<UUID, BigDecimal> myRatingByTrack = currentUserId == null || trackIds.isEmpty()
            ? Map.of()
            : trackRatingRepository.findByUserIdAndTrackIdIn(currentUserId, trackIds).stream()
                .collect(Collectors.toMap(tr -> tr.getTrack().getId(), TrackRating::getRating));

        List<PlaylistTrackDetailDto> trackDtos = playlistTracks.stream()
            .map(pt -> toTrackDetailDto(
                pt, statsByTrack.get(pt.getTrack().getId()), myRatingByTrack.get(pt.getTrack().getId()),
                listenedTrackIds.contains(pt.getTrack().getId()), myNotesByTrack.getOrDefault(pt.getTrack().getId(), List.of())
            ))
            .toList();

        boolean liked = currentUserId != null && likeService.hasUserLiked(currentUserId, LikeableEntityType.PLAYLIST, id);
        boolean saved = currentUserId != null && savedItemService.isSaved(currentUserId, SaveableEntityType.PLAYLIST, id);

        List<VocabularyTag> styleTags = graphService.getPlaylistStyles(id);
        List<VocabularyTag> moodTags = graphService.getPlaylistMoods(id);
        List<VocabularyTag> contextTags = graphService.getPlaylistContexts(id);

        return new PlaylistDetailDto(
            playlist.getId(), playlist.getTitle(), playlist.getTagline(), playlist.getDescription(),
            playlist.getCoverImageUrl(), playlist.getSpotifyUrl(), playlist.getType(), playlist.isPublished(),
            playlist.getLikeCount(), liked, saved, playlist.getTrackCount(), playlist.getDurationMs(), trackDtos,
            styleTags, moodTags, contextTags, playlist.getCreatedAt(), playlist.getUpdatedAt()
        );
    }

    /**
     * The singleton featured playlist — see {@code Playlist#featured}/{@code
     * idx_playlists_only_one_featured} (V26), same "at most one" pattern as
     * {@code Album#featured}. Same lean summary shape as every other playlist
     * listing (no tracklist) — see {@link PlaylistDetailDto}/{@code GET
     * /playlists/{id}} for the full track/album/artist fan-out.
     *
     * @return the featured playlist, empty if none is featured, or if the
     *         featured one isn't published and the caller isn't an admin
     */
    @Transactional(readOnly = true)
    public Optional<PlaylistSummaryDto> getFeatured(UUID currentUserId, boolean isAdmin) {
        return playlistRepository.findByFeaturedTrue()
            .filter(playlist -> playlist.isPublished() || isAdmin)
            .map(playlist -> toSummaryDtos(List.of(playlist), currentUserId).get(0));
    }

    /**
     * The "Journey" section — the most recently published {@code JOURNEY}
     * playlist, if any (see {@code PlaylistRepository.findFirstByPublishedTrueAndTypeOrderByCreatedAtDesc}
     * for what "most recently published" actually means here). Unlike
     * {@link #getFeatured} this is derived, not admin-curated — no
     * set/unset endpoint, no singleton flag.
     *
     * @return the journey playlist, empty if no JOURNEY playlist has been published yet
     */
    @Transactional(readOnly = true)
    public Optional<PlaylistSummaryDto> getJourney(UUID currentUserId) {
        return playlistRepository.findFirstByPublishedTrueAndTypeOrderByCreatedAtDesc(PlaylistType.JOURNEY)
            .map(playlist -> toSummaryDtos(List.of(playlist), currentUserId).get(0));
    }

    /**
     * A page of one {@code type}, newest first, no other filters — the
     * playlist equivalent of "The Catalogue". Tags are fetched in 3 batch
     * queries for the whole page (see {@code GraphService.getPlaylistStylesBatch}
     * and its mood/context siblings) instead of 3 per playlist — the
     * per-row fan-out {@link #getFeatured}/{@link #getJourney} do is fine
     * for a single playlist, not for a page of them.
     *
     * @param type               which type to list — JOURNEY or STANDARD, one at a time
     * @param includeUnpublished true for admins (drafts included), false otherwise
     * @param currentUserId      for likedByCurrentUser per row, or null if not resolvable
     * @param pageable           page/size/sort — callers default to createdAt desc
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<PlaylistSummaryDto> getCatalogue(PlaylistType type, boolean includeUnpublished, UUID currentUserId, Pageable pageable) {
        Page<Playlist> page = includeUnpublished
            ? playlistRepository.findByType(type, pageable)
            : playlistRepository.findByTypeAndPublishedTrue(type, pageable);
        return toSummaryPage(page, currentUserId);
    }

    /**
     * Same as {@link #getCatalogue(PlaylistType, boolean, UUID, Pageable)},
     * every type mixed together instead of one at a time — {@code GET
     * /playlists/catalogue}, the one that doesn't split journeys from the rest.
     *
     * @param includeUnpublished true for admins (drafts included), false otherwise
     * @param currentUserId      for likedByCurrentUser per row, or null if not resolvable
     * @param pageable           page/size/sort — callers default to createdAt desc
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<PlaylistSummaryDto> getCatalogue(boolean includeUnpublished, UUID currentUserId, Pageable pageable) {
        Page<Playlist> page = includeUnpublished
            ? playlistRepository.findAll(pageable)
            : playlistRepository.findByPublishedTrue(pageable);
        return toSummaryPage(page, currentUserId);
    }

    private Page<PlaylistSummaryDto> toSummaryPage(Page<Playlist> page, UUID currentUserId) {
        return new PageImpl<>(toSummaryDtos(page.getContent(), currentUserId), page.getPageable(), page.getTotalElements());
    }

    /** Shared by list/getJourney/getCatalogue — one batch of likes + one batch per tag type for the whole set, not one per playlist. */
    private List<PlaylistSummaryDto> toSummaryDtos(List<Playlist> playlists, UUID currentUserId) {
        List<UUID> playlistIds = playlists.stream().map(Playlist::getId).toList();
        Set<UUID> likedIds = currentUserId == null
            ? Set.of()
            : likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.PLAYLIST, playlistIds);
        Map<UUID, List<VocabularyTag>> styleTagsByPlaylist = graphService.getPlaylistStylesBatch(playlistIds);
        Map<UUID, List<VocabularyTag>> moodTagsByPlaylist = graphService.getPlaylistMoodsBatch(playlistIds);
        Map<UUID, List<VocabularyTag>> contextTagsByPlaylist = graphService.getPlaylistContextsBatch(playlistIds);

        return playlists.stream()
            .map(playlist -> new PlaylistSummaryDto(
                playlist.getId(), playlist.getTitle(), playlist.getTagline(), playlist.getDescription(),
                playlist.getCoverImageUrl(), playlist.getSpotifyUrl(), playlist.getType(), playlist.isPublished(),
                playlist.getLikeCount(), likedIds.contains(playlist.getId()), playlist.getTrackCount(), playlist.getDurationMs(),
                styleTagsByPlaylist.getOrDefault(playlist.getId(), List.of()),
                moodTagsByPlaylist.getOrDefault(playlist.getId(), List.of()),
                contextTagsByPlaylist.getOrDefault(playlist.getId(), List.of()),
                playlist.getCreatedAt(), playlist.getUpdatedAt()
            ))
            .toList();
    }

    private TrackRatingRepository.TrackRatingStats ratingStatsFor(UUID trackId) {
        return trackRatingRepository.getRatingStatsForTracks(List.of(trackId)).stream().findFirst().orElse(null);
    }

    private PlaylistTrackDetailDto toTrackDetailDto(
        PlaylistTrack playlistTrack, TrackRatingRepository.TrackRatingStats stats, BigDecimal myRating, boolean listened, List<NoteDto> myNotes
    ) {
        Track track = playlistTrack.getTrack();
        Album album = track.getAlbum();
        Artist artist = album.getArtist();
        return new PlaylistTrackDetailDto(
            track.getId(), track.getName(), track.getDurationMs(), track.getSpotifyUrl(),
            album.getId(), album.getName(), album.getImageUrl(),
            artist.getId(), artist.getName(),
            playlistTrack.getPosition(), playlistTrack.getTitle(), playlistTrack.getCuratorNote(),
            stats == null ? null : stats.getAvgRating(),
            stats == null ? 0 : stats.getCount(),
            myRating, listened, myNotes
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
