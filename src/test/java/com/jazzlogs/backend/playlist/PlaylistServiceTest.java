package com.jazzlogs.backend.playlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.playlist.dto.JourneyPlaylistDto;
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistUpsertRequest;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.saveditem.SavedItemService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — this
// class tests PlaylistService's own Postgres/validation logic (addTrack/
// removeTrack/updateTrackNote/reorderTracks, vocab code validation), not Neo4j
// behavior. See PlaylistTrackSyncFailureTest for the real-GraphService,
// Neo4j-down-doesn't-break-the-endpoint coverage. ImageStorageService is
// mocked too — see ImageStorageServiceTest for the real-MinIO upload coverage.
@SpringBootTest
@Transactional
class PlaylistServiceTest {

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistTrackRepository playlistTrackRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private ListenService listenService;

    @Autowired
    private SavedItemService savedItemService;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private ImageStorageService imageStorageService;

    @Test
    void addTrack_updatesTrackCountAndDurationMs() {
        UUID playlistId = persistPlaylist("stats-add");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A", 180_000);
        Track trackB = persistTrack(album, "Track B", 240_000);

        PlaylistTrackDetailDto first = playlistService.addTrack(playlistId, trackA.getId(), "Opening Track", "great opener");
        assertThat(first.position()).isEqualTo(0);

        PlaylistTrackDetailDto second = playlistService.addTrack(playlistId, trackB.getId(), "Track Entry", "curator note");
        assertThat(second.position()).isEqualTo(1);

        PlaylistDetailDto detail = playlistService.getPlaylistDetail(playlistId, null, true);
        assertThat(detail.trackCount()).isEqualTo(2);
        assertThat(detail.durationMs()).isEqualTo(420_000L);
    }

    @Test
    void addTrack_rejectsDuplicateTrack() {
        UUID playlistId = persistPlaylist("dup-add");
        Track track = persistTrack(persistAlbum(persistArtist()), "Track A");
        playlistService.addTrack(playlistId, track.getId(), "Track Entry", "curator note");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.addTrack(playlistId, track.getId(), "Track Entry", "curator note"));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addTrack_rejectsUnknownTrack() {
        UUID playlistId = persistPlaylist("missing-track");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.addTrack(playlistId, UUID.randomUUID(), "Track Entry", "curator note"));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeTrack_updatesStatsAndIsNullSafeForMissingDuration() {
        UUID playlistId = persistPlaylist("stats-remove");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A", 180_000);
        Track trackB = persistTrack(album, "Track B", null);
        playlistService.addTrack(playlistId, trackA.getId(), "Track Entry", "curator note");
        playlistService.addTrack(playlistId, trackB.getId(), "Track Entry", "curator note");

        playlistService.removeTrack(playlistId, trackB.getId());

        PlaylistDetailDto afterRemovingNullDuration = playlistService.getPlaylistDetail(playlistId, null, true);
        assertThat(afterRemovingNullDuration.trackCount()).isEqualTo(1);
        assertThat(afterRemovingNullDuration.durationMs()).isEqualTo(180_000L);

        playlistService.removeTrack(playlistId, trackA.getId());

        PlaylistDetailDto empty = playlistService.getPlaylistDetail(playlistId, null, true);
        assertThat(empty.trackCount()).isZero();
        assertThat(empty.durationMs()).isZero();
    }

    @Test
    void removeTrack_rejects404WhenTrackNotInPlaylist() {
        UUID playlistId = persistPlaylist("remove-missing");
        Track track = persistTrack(persistAlbum(persistArtist()), "Track A");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.removeTrack(playlistId, track.getId()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reorderTracks_rejectsMismatchedTrackIdSet() {
        UUID playlistId = persistPlaylist("reorder-mismatch");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A");
        Track trackB = persistTrack(album, "Track B");
        playlistService.addTrack(playlistId, trackA.getId(), "Track Entry", "curator note");
        playlistService.addTrack(playlistId, trackB.getId(), "Track Entry", "curator note");

        // Missing trackB, includes an id that isn't in the playlist at all.
        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> playlistService.reorderTracks(playlistId, List.of(trackA.getId(), UUID.randomUUID())));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reorderTracks_updatesPositions() {
        UUID playlistId = persistPlaylist("reorder-ok");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A");
        Track trackB = persistTrack(album, "Track B");
        playlistService.addTrack(playlistId, trackA.getId(), "Track Entry", "curator note");
        playlistService.addTrack(playlistId, trackB.getId(), "Track Entry", "curator note");

        playlistService.reorderTracks(playlistId, List.of(trackB.getId(), trackA.getId()));

        List<PlaylistTrack> tracks = playlistTrackRepository.findByPlaylistIdWithTrackDetails(playlistId);
        assertThat(tracks).extracting(pt -> pt.getTrack().getId()).containsExactly(trackB.getId(), trackA.getId());
        assertThat(tracks).extracting(PlaylistTrack::getPosition).containsExactly(0, 1);
    }

    /** title/curator_note have no Neo4j counterpart — this must never touch graphService. */
    @Test
    void updateTrackNote_updatesTitleAndNoteAndDoesNotCallGraphService() {
        UUID playlistId = persistPlaylist("note-only");
        Track track = persistTrack(persistAlbum(persistArtist()), "Track A");
        playlistService.addTrack(playlistId, track.getId(), "Track Entry", "curator note");
        clearInvocations(graphService); // addTrack above already called addPlaylistTrack — only care about what updateTrackNote itself does

        PlaylistTrackDetailDto updated = playlistService.updateTrackNote(playlistId, track.getId(), "Chapter One", "new note");

        assertThat(updated.title()).isEqualTo("Chapter One");
        assertThat(updated.curatorNote()).isEqualTo("new note");
        verify(graphService, never()).addPlaylistTrack(any(), any(), anyInt());
        verify(graphService, never()).removePlaylistTrack(any(), any());
        verify(graphService, never()).reorderPlaylistTracks(any(), any());
    }

    /**
     * Validation runs before the Neo4j call — an invalid code is a 400, not an
     * attempted write. Doesn't touch graphService at all (mocked or not), since
     * VocabularyCodes.validate throws first.
     */
    @Test
    void replaceTags_rejectsInvalidStyleCode() {
        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> playlistService.create(upsertRequestWithTags("invalid-tag", List.of("NOT_A_REAL_STYLE"))));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("style");
    }

    /** Valid codes reach GraphService.setPlaylistTags — same call graphService.getPlaylistDetail's tag reads rely on. */
    @Test
    void replaceTags_callsGraphServiceWithValidCodes() {
        Playlist created = playlistService.create(upsertRequestWithTags("tags-cut", List.of("SWING", "BEBOP")));

        verify(graphService).setPlaylistTags(created.getId(), List.of("SWING", "BEBOP"), List.of(), List.of());
    }

    @Test
    void create_rejectsDuplicateTitle() {
        persistPlaylist("Late Night Hours");

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> playlistService.create(upsertRequestWithTags("Late Night Hours", List.of())));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void update_rejectsRenamingToAnotherPlaylistsTitle() {
        persistPlaylist("Late Night Hours");
        UUID otherId = persistPlaylist("Morning Coffee");

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> playlistService.update(otherId, upsertRequestWithTags("Late Night Hours", List.of())));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** Saving a playlist's metadata without changing its title must not trip over its own row. */
    @Test
    void update_allowsSavingUnderItsOwnUnchangedTitle() {
        UUID playlistId = persistPlaylist("Late Night Hours");

        PlaylistDetailDto updated = playlistService.update(playlistId, upsertRequestWithTags("Late Night Hours", List.of()));

        assertThat(updated.title()).isEqualTo("Late Night Hours");
    }

    @Test
    void create_persistsType() {
        PlaylistUpsertRequest request = new PlaylistUpsertRequest(
            "The Long Road", null, null, null, null, PlaylistType.JOURNEY, List.of(), List.of(), List.of()
        );

        Playlist created = playlistService.create(request);

        assertThat(playlistRepository.findById(created.getId()).orElseThrow().getType()).isEqualTo(PlaylistType.JOURNEY);
    }

    // No "returns empty when nothing is published" test here — this table
    // can carry real, deliberately-created data (unlike every other fixture
    // in this class), so a test can't assume it starts out with zero
    // JOURNEY playlists. The tests below only ever assert on which playlist
    // *wins*, never on the table being otherwise empty.

    @Test
    void getJourney_ignoresStandardPlaylistsEvenIfNewer() {
        persistPlaylist("An Old Journey", PlaylistType.JOURNEY, true);
        persistPlaylist("A Newer Standard Playlist", PlaylistType.STANDARD, true);

        JourneyPlaylistDto journey = playlistService.getJourney(null).orElseThrow();

        assertThat(journey.title()).isEqualTo("An Old Journey");
    }

    @Test
    void getJourney_ignoresUnpublishedJourneyPlaylistsEvenIfNewer() {
        persistPlaylist("The Published Journey", PlaylistType.JOURNEY, true);
        persistPlaylist("A Newer Draft Journey", PlaylistType.JOURNEY, false);

        JourneyPlaylistDto journey = playlistService.getJourney(null).orElseThrow();

        assertThat(journey.title()).isEqualTo("The Published Journey");
    }

    /** "Most recently published" is approximated by createdAt — see PlaylistRepository. */
    @Test
    void getJourney_returnsTheMostRecentlyCreatedPublishedJourneyPlaylist() {
        persistPlaylist("The First Journey", PlaylistType.JOURNEY, true);
        persistPlaylist("The Second Journey", PlaylistType.JOURNEY, true);

        JourneyPlaylistDto journey = playlistService.getJourney(null).orElseThrow();

        assertThat(journey.title()).isEqualTo("The Second Journey");
        assertThat(journey.type()).isEqualTo(PlaylistType.JOURNEY);
    }

    @Test
    void delete_rejectsUnknownPlaylist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.delete(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_cascadesPlaylistTracksAndSyncsTheNodeDeletionToNeo4j() {
        UUID playlistId = persistPlaylist("delete-cascade-tracks");
        Track track = persistTrack(persistAlbum(persistArtist()), "Track A");
        playlistService.addTrack(playlistId, track.getId(), "Track Entry", "curator note");

        playlistService.delete(playlistId);

        assertThat(playlistRepository.findById(playlistId)).isEmpty();
        assertThat(playlistTrackRepository.findByPlaylistIdWithTrackDetails(playlistId)).isEmpty();
        verify(graphService).deletePlaylistNode(playlistId);
    }

    /** likes/listens/saved_items are polymorphic tables with no FK to the playlist — delete has to clean them up itself. */
    @Test
    void delete_removesLikesListensAndSavedItems() {
        UUID playlistId = persistPlaylist("delete-cross-refs");
        User user = userRepository.save(new User(UUID.randomUUID(), "delete-test-" + UUID.randomUUID() + "@example.com"));
        likeService.addLike(user.getId(), LikeableEntityType.PLAYLIST, playlistId);
        listenService.markPlaylistListened(user.getId(), playlistId);
        savedItemService.save(user.getId(), SaveableEntityType.PLAYLIST, playlistId);

        playlistService.delete(playlistId);

        assertThat(likeService.hasUserLiked(user.getId(), LikeableEntityType.PLAYLIST, playlistId)).isFalse();
        assertThat(listenService.hasListenedToPlaylist(user.getId(), playlistId)).isFalse();
        assertThat(savedItemService.isSaved(user.getId(), SaveableEntityType.PLAYLIST, playlistId)).isFalse();
    }

    @Test
    void setCoverImage_uploadsUnderThePlaylistsOwnKeyAndPersistsTheReturnedUrl() {
        UUID playlistId = persistPlaylist("cover-set");
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("playlists/" + playlistId + "/cover", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/playlists/" + playlistId + "/cover.jpg");

        playlistService.setCoverImage(playlistId, file);

        PlaylistDetailDto dto = playlistService.getPlaylistDetail(playlistId, null, true);
        assertThat(dto.coverImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/playlists/" + playlistId + "/cover.jpg");
    }

    @Test
    void setCoverImage_rejectsUnknownPlaylist() {
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.setCoverImage(UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_alwaysStartsAsADraft() {
        UUID playlistId = persistPlaylist("draft-by-default", false);

        assertThat(playlistRepository.findById(playlistId).orElseThrow().isPublished()).isFalse();
    }

    @Test
    void publish_marksThePlaylistPublished() {
        UUID playlistId = persistPlaylist("to-publish", false);

        playlistService.publish(playlistId);

        assertThat(playlistRepository.findById(playlistId).orElseThrow().isPublished()).isTrue();
    }

    @Test
    void publish_rejectsUnknownPlaylist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.publish(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unpublish_revertsToDraft_andClearsFeaturedIfItWasTheFeaturedOne() {
        UUID playlistId = persistPlaylist("to-unpublish");
        playlistService.setFeatured(playlistId);

        playlistService.unpublish(playlistId);

        Playlist reloaded = playlistRepository.findById(playlistId).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
        assertThat(reloaded.isFeatured()).isFalse();
    }

    @Test
    void unpublish_rejectsUnknownPlaylist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.unpublish(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFeatured_marksExactlyOnePlaylist_clearingWhicheverWasFeaturedBefore() {
        UUID playlistA = persistPlaylist("featured-a");
        UUID playlistB = persistPlaylist("featured-b");

        playlistService.setFeatured(playlistA);
        assertThat(playlistRepository.findByFeaturedTrue().map(Playlist::getId)).contains(playlistA);

        playlistService.setFeatured(playlistB);
        assertThat(playlistRepository.findByFeaturedTrue().map(Playlist::getId)).contains(playlistB);
    }

    @Test
    void setFeatured_rejectsUnknownPlaylist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.setFeatured(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFeatured_rejectsUnpublishedPlaylist() {
        UUID playlistId = persistPlaylist("featured-unpublished", false);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.setFeatured(playlistId));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(playlistRepository.findByFeaturedTrue().map(Playlist::getId)).isNotEqualTo(Optional.of(playlistId));
    }

    @Test
    void unsetFeatured_isNoOpWhenThePlaylistWasNeverFeatured() {
        UUID playlistId = persistPlaylist("unset-never-featured");

        playlistService.unsetFeatured(playlistId);

        assertThat(playlistRepository.findById(playlistId).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_removesTheFeaturedFlag() {
        UUID playlistId = persistPlaylist("unset-featured");
        playlistService.setFeatured(playlistId);

        playlistService.unsetFeatured(playlistId);

        assertThat(playlistRepository.findById(playlistId).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_rejectsUnknownPlaylist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.unsetFeatured(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID persistPlaylist(String title) {
        return persistPlaylist(title, true);
    }

    private UUID persistPlaylist(String title, boolean published) {
        return persistPlaylist(title, PlaylistType.STANDARD, published);
    }

    private UUID persistPlaylist(String title, PlaylistType type, boolean published) {
        PlaylistUpsertRequest request = new PlaylistUpsertRequest(
            title, null, null, null, null, type, List.of(), List.of(), List.of()
        );
        UUID id = playlistService.create(request).getId();
        if (published) {
            playlistService.publish(id);
        }
        return id;
    }

    private PlaylistUpsertRequest upsertRequestWithTags(String title, List<String> styleCodes) {
        return new PlaylistUpsertRequest(title, null, null, null, null, PlaylistType.STANDARD, styleCodes, List.of(), List.of());
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1", "LABEL-1",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    private Track persistTrack(Album album, String name) {
        return persistTrack(album, name, null);
    }

    private Track persistTrack(Album album, String name, Integer durationMs) {
        return trackRepository.save(new Track(
            album, null, name, durationMs, null, null, false,
            null, null, null, null, null, null
        ));
    }
}
