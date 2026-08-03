package com.jazzlogs.backend.playlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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
import com.jazzlogs.backend.playlist.dto.PlaylistDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistTrackDetailDto;
import com.jazzlogs.backend.playlist.dto.PlaylistUpsertRequest;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — this
// class tests PlaylistService's own Postgres/validation logic (addTrack/
// removeTrack/updateTrackNote/reorderTracks, vocab code validation), not Neo4j
// behavior. See PlaylistTrackSyncFailureTest for the real-GraphService,
// Neo4j-down-doesn't-break-the-endpoint coverage.
@SpringBootTest
@Transactional
class PlaylistServiceTest {

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistTrackRepository playlistTrackRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @MockitoBean
    private GraphService graphService;

    @Test
    void addTrack_updatesTrackCountAndDurationMs() {
        UUID playlistId = persistPlaylist("stats-add");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A", 180_000);
        Track trackB = persistTrack(album, "Track B", 240_000);

        PlaylistTrackDetailDto first = playlistService.addTrack(playlistId, trackA.getId(), null, "great opener");
        assertThat(first.position()).isEqualTo(0);

        PlaylistTrackDetailDto second = playlistService.addTrack(playlistId, trackB.getId(), null, null);
        assertThat(second.position()).isEqualTo(1);

        PlaylistDetailDto detail = playlistService.getPlaylistDetail(playlistId, null, true);
        assertThat(detail.trackCount()).isEqualTo(2);
        assertThat(detail.durationMs()).isEqualTo(420_000L);
    }

    @Test
    void addTrack_rejectsDuplicateTrack() {
        UUID playlistId = persistPlaylist("dup-add");
        Track track = persistTrack(persistAlbum(persistArtist()), "Track A");
        playlistService.addTrack(playlistId, track.getId(), null, null);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.addTrack(playlistId, track.getId(), null, null));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addTrack_rejectsUnknownTrack() {
        UUID playlistId = persistPlaylist("missing-track");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> playlistService.addTrack(playlistId, UUID.randomUUID(), null, null));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeTrack_updatesStatsAndIsNullSafeForMissingDuration() {
        UUID playlistId = persistPlaylist("stats-remove");
        Album album = persistAlbum(persistArtist());
        Track trackA = persistTrack(album, "Track A", 180_000);
        Track trackB = persistTrack(album, "Track B", null);
        playlistService.addTrack(playlistId, trackA.getId(), null, null);
        playlistService.addTrack(playlistId, trackB.getId(), null, null);

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
        playlistService.addTrack(playlistId, trackA.getId(), null, null);
        playlistService.addTrack(playlistId, trackB.getId(), null, null);

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
        playlistService.addTrack(playlistId, trackA.getId(), null, null);
        playlistService.addTrack(playlistId, trackB.getId(), null, null);

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
        playlistService.addTrack(playlistId, track.getId(), null, null);
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
        PlaylistDetailDto created = playlistService.create(upsertRequestWithTags("tags-cut", List.of("SWING", "BEBOP")));

        verify(graphService).setPlaylistTags(created.id(), List.of("SWING", "BEBOP"), List.of(), List.of());
    }

    private UUID persistPlaylist(String slug) {
        PlaylistUpsertRequest request = new PlaylistUpsertRequest(
            slug, "Test Playlist", null, null, null, null, true, List.of(), List.of(), List.of()
        );
        return playlistService.create(request).id();
    }

    private PlaylistUpsertRequest upsertRequestWithTags(String slug, List<String> styleCodes) {
        return new PlaylistUpsertRequest(slug, "Test Playlist", null, null, null, null, true, styleCodes, List.of(), List.of());
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1",
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
