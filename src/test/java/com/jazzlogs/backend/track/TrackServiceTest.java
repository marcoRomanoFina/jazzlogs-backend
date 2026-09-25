package com.jazzlogs.backend.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialByline;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.spotify.SpotifyTrackAlbumData;
import com.jazzlogs.backend.spotify.SpotifyTrackArtistData;
import com.jazzlogs.backend.spotify.SpotifyTrackData;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;
import com.jazzlogs.backend.track.dto.TrackDetailDto;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest/ArtistServiceTest: createOrUpdateTrack is
// the test below that actually reaches it. SpotifyCatalogService is mocked
// too — createOrUpdateTrack calls out to the real Spotify API otherwise,
// which a unit test can't rely on.
@SpringBootTest
@Transactional
class TrackServiceTest {

    @Autowired
    private TrackService trackService;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private TrackEditorialRepository trackEditorialRepository;

    @Autowired
    private LikeService likeService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private SpotifyCatalogService spotifyCatalogService;

    // Real, currently-curated tracks can already be at (or near) the cap in
    // the shared dev DB this suite runs against — every test here needs a
    // known starting count (0), not whatever's actually featured live.
    @BeforeEach
    void clearRealFeaturedTracks() {
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();
    }

    @Test
    void setFeatured_marksTrackAsFeatured() {
        Track track = persistTrack("Set Featured Track");

        trackService.setFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void unsetFeatured_removesFeaturedFlag() {
        Track track = persistTrack("Unset Featured Track");
        trackService.setFeatured(track.getId());

        trackService.unsetFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_isNoOpWhenTrackWasNeverFeatured() {
        Track track = persistTrack("Never Featured Track");

        trackService.unsetFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void setFeatured_rejectsUnknownTrack() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFeatured_rejectsTrackWithNoEditorialYet() {
        Track track = persistTrackWithoutEditorial("No Editorial Track");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(track.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void setFeatured_isIdempotentForAlreadyFeaturedTrack() {
        Track track = persistTrack("Idempotent Featured Track");
        trackService.setFeatured(track.getId());

        trackService.setFeatured(track.getId());

        assertThat(trackRepository.findById(track.getId()).orElseThrow().isFeatured()).isTrue();
    }

    @Test
    void setFeatured_rejectsASeventhTrackOnceAtTheCap() {
        for (int i = 0; i < TrackService.MAX_FEATURED_TRACKS; i++) {
            trackService.setFeatured(persistTrack("Cap Track " + i).getId());
        }
        Track seventh = persistTrack("Cap Track Overflow");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.setFeatured(seventh.getId())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(trackRepository.findById(seventh.getId()).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void createOrUpdateTrack_assignsSequentialTrackNumberByUploadOrder_andBumpsTotalTracks() {
        Artist artist = artistRepository.save(new Artist("Upload Order Test Artist", "spotify-artist-upload-order", null, null));
        Album album = albumRepository.save(new Album(
            artist, "Upload Order Test Album", "spotify-album-upload-order", null, null, 2024, 0
        ));
        SpotifyTrackAlbumData albumData = new SpotifyTrackAlbumData("spotify-album-upload-order", "Upload Order Test Album", null, null, 2024);
        SpotifyTrackArtistData artistData = new SpotifyTrackArtistData("spotify-artist-upload-order", "Upload Order Test Artist", null);
        // Spotify's own trackNumber (99/1) is deliberately wrong/out of order here —
        // it must be ignored in favor of upload order.
        when(spotifyCatalogService.fetchTrack("spotify-track-first"))
            .thenReturn(new SpotifyTrackData("spotify-track-first", "First Track", 200000, null, 99, null, albumData, artistData));
        when(spotifyCatalogService.fetchTrack("spotify-track-second"))
            .thenReturn(new SpotifyTrackData("spotify-track-second", "Second Track", 200000, null, 1, null, albumData, artistData));
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of(), List.of(new TrackPlacement(UUID.randomUUID(), 1)));

        Track first = trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-first", null, null, null, null, null, null));
        Track second = trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-second", null, null, null, null, null, null));

        verify(graphService).addTrackToAlbum(album.getId(), first.getId(), 1);
        verify(graphService).addTrackToAlbum(album.getId(), second.getId(), 2);
        assertThat(albumRepository.findById(album.getId()).orElseThrow().getTotalTracks()).isEqualTo(2);
    }

    @Test
    void createOrUpdateTrack_onUpdate_doesNotReassignTrackNumberOrTotalTracks() {
        Artist artist = artistRepository.save(new Artist("Update Test Artist", "spotify-artist-update", null, null));
        Album album = albumRepository.save(new Album(
            artist, "Update Test Album", "spotify-album-update", null, null, 2024, 0
        ));
        SpotifyTrackAlbumData albumData = new SpotifyTrackAlbumData("spotify-album-update", "Update Test Album", null, null, 2024);
        SpotifyTrackArtistData artistData = new SpotifyTrackArtistData("spotify-artist-update", "Update Test Artist", null);
        when(spotifyCatalogService.fetchTrack("spotify-track-update"))
            .thenReturn(new SpotifyTrackData("spotify-track-update", "Original Name", 200000, null, 1, null, albumData, artistData));
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of());
        trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-update", null, null, null, null, null, null));

        // Re-post the same track — a metadata refresh, not a new upload.
        when(spotifyCatalogService.fetchTrack("spotify-track-update"))
            .thenReturn(new SpotifyTrackData("spotify-track-update", "Renamed", 200000, null, 1, null, albumData, artistData));
        trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-update", null, null, null, null, null, null));

        verify(graphService, times(1)).addTrackToAlbum(any(), any(), any(Integer.class));
        assertThat(albumRepository.findById(album.getId()).orElseThrow().getTotalTracks()).isEqualTo(1);
    }

    @Test
    void createOrUpdateTrack_createsMinimalAlbumAndArtist_whenNeitherExistsYet() {
        SpotifyTrackAlbumData albumData = new SpotifyTrackAlbumData(
            "spotify-album-new", "Brand New Album", "http://img.example/album.jpg", "http://open.spotify.com/album/new", 2023
        );
        SpotifyTrackArtistData artistData = new SpotifyTrackArtistData(
            "spotify-artist-new", "Brand New Artist", "http://open.spotify.com/artist/new"
        );
        when(spotifyCatalogService.fetchTrack("spotify-track-new"))
            .thenReturn(new SpotifyTrackData("spotify-track-new", "Brand New Track", 200000, null, 1, "http://img.example/album.jpg", albumData, artistData));
        when(graphService.getTrackPlacements(any())).thenReturn(List.of());

        Track track = trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-new", null, null, null, null, null, null));

        Album album = track.getAlbum();
        assertThat(album.getSpotifyAlbumId()).isEqualTo("spotify-album-new");
        assertThat(album.getName()).isEqualTo("Brand New Album");
        assertThat(album.getReleaseYear()).isEqualTo(2023);
        Artist artist = album.getArtist();
        assertThat(artist.getSpotifyArtistId()).isEqualTo("spotify-artist-new");
        assertThat(artist.getName()).isEqualTo("Brand New Artist");
        assertThat(artist.getImageUrl()).isNull();
    }

    @Test
    void createOrUpdateTrack_reusesTheSameAlbumAndArtist_forASecondTrackWithTheSameSpotifyIds() {
        SpotifyTrackAlbumData albumData = new SpotifyTrackAlbumData("spotify-album-shared", "Shared Album", null, null, 2022);
        SpotifyTrackArtistData artistData = new SpotifyTrackArtistData("spotify-artist-shared", "Shared Artist", null);
        when(spotifyCatalogService.fetchTrack("spotify-track-shared-1"))
            .thenReturn(new SpotifyTrackData("spotify-track-shared-1", "Track One", 200000, null, 1, null, albumData, artistData));
        when(spotifyCatalogService.fetchTrack("spotify-track-shared-2"))
            .thenReturn(new SpotifyTrackData("spotify-track-shared-2", "Track Two", 200000, null, 2, null, albumData, artistData));
        when(graphService.getTrackPlacements(any())).thenReturn(List.of(), List.of(new TrackPlacement(UUID.randomUUID(), 1)));

        Track first = trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-shared-1", null, null, null, null, null, null));
        Track second = trackService.createOrUpdateTrack(new CreateTrackRequest("spotify-track-shared-2", null, null, null, null, null, null));

        assertThat(second.getAlbum().getId()).isEqualTo(first.getAlbum().getId());
        assertThat(second.getAlbum().getArtist().getId()).isEqualTo(first.getAlbum().getArtist().getId());
    }

    @Test
    void getTrackDetail_includesArtistAndAlbumContextAlongsideTheTracksOwnData() {
        Track track = persistTrack("Detail Track");
        Album album = track.getAlbum();
        Artist artist = album.getArtist();
        when(graphService.getTrackPlacement(track.getId())).thenReturn(new TrackPlacement(track.getId(), 3));

        TrackDetailDto detail = trackService.getTrackDetail(track.getId(), UUID.randomUUID());

        assertThat(detail.artistId()).isEqualTo(artist.getId());
        assertThat(detail.artistName()).isEqualTo(artist.getName());
        assertThat(detail.albumId()).isEqualTo(album.getId());
        assertThat(detail.albumName()).isEqualTo(album.getName());
        assertThat(detail.albumReleaseYear()).isEqualTo(album.getReleaseYear());
        assertThat(detail.track().id()).isEqualTo(track.getId());
        assertThat(detail.track().name()).isEqualTo("Detail Track");
        assertThat(detail.track().trackNumber()).isEqualTo(3);
        assertThat(detail.track().editorial()).isNotNull();
        assertThat(detail.track().editorial().likeCount()).isZero();
        assertThat(detail.track().editorial().likedByCurrentUser()).isFalse();
        assertThat(detail.track().hasListened()).isFalse();
        assertThat(detail.track().isSaved()).isFalse();
        assertThat(detail.track().myRating()).isNull();
    }

    @Test
    void getTrackDetail_reflectsTheRequestingUsersOwnLike_notJustTheRawCount() {
        Track track = persistTrack("Liked Detail Track");
        UUID likingUserId = UUID.randomUUID();
        UUID editorialId = trackEditorialRepository.findByTrackId(track.getId()).orElseThrow().getId();
        likeService.addLike(likingUserId, LikeableEntityType.EDITORIAL, editorialId);
        // incrementLikeCount is a bulk UPDATE — it never touches the TrackEditorial
        // instance findByTrackId above already loaded into this transaction's
        // persistence context, so without clearing it, every later fetch by id
        // would keep returning that same stale (pre-increment) managed instance.
        entityManager.clear();
        when(graphService.getTrackPlacement(track.getId())).thenReturn(new TrackPlacement(track.getId(), 1));

        TrackDetailDto likedByRequester = trackService.getTrackDetail(track.getId(), likingUserId);
        TrackDetailDto seenByAnotherUser = trackService.getTrackDetail(track.getId(), UUID.randomUUID());

        assertThat(likedByRequester.track().editorial().likeCount()).isEqualTo(1);
        assertThat(likedByRequester.track().editorial().likedByCurrentUser()).isTrue();
        assertThat(seenByAnotherUser.track().editorial().likeCount()).isEqualTo(1);
        assertThat(seenByAnotherUser.track().editorial().likedByCurrentUser()).isFalse();
    }

    @Test
    void getTrackDetail_rejectsUnknownTrack() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> trackService.getTrackDetail(UUID.randomUUID(), UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // setFeatured requires a TrackEditorial to already exist — every scenario
    // here needs one except the dedicated "rejects with no editorial" test.
    private Track persistTrack(String name) {
        Track track = persistTrackWithoutEditorial(name);
        editorialService.upsertTrackEditorial(track.getId(), new TrackEditorialRequest(name + " Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of()));
        return track;
    }

    private Track persistTrackWithoutEditorial(String name) {
        Artist artist = artistRepository.save(new Artist("Featured Test Artist " + UUID.randomUUID(), null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Featured Test Album " + UUID.randomUUID(), null, null, null, 2024, 1
        ));
        return trackRepository.save(new Track(
            album, null, name, null, null, null, null, null, null, null, null, null
        ));
    }
}
