package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import static org.mockito.Mockito.when;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.editorial.dto.CatalogueEditorialDto;
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.LastLogDto;
import com.jazzlogs.backend.editorial.dto.RecentAlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

@SpringBootTest
@Transactional
class EditorialServiceTest {

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EditorialRepository editorialRepository;

    @Autowired
    private EditorialSummaryRepository editorialSummaryRepository;

    // GraphService is mocked here — trackNumber lives only in Neo4j (see
    // Track's own comment), so getLastLog's tests stub getTrackPlacements
    // directly instead of touching the real Neo4j instance.
    @MockitoBean
    private GraphService graphService;

    @Test
    void upsertAlbumEditorial_persistsAcrossJoinedInheritanceTables() {
        Artist artist = artistRepository.save(new Artist("Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1", "LABEL-1",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));

        AlbumEditorialRequest request = new AlbumEditorialRequest("A Title", "A dek", "A byline", List.of());

        AlbumEditorial saved = editorialService.upsertAlbumEditorial(album.getId(), request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("A Title");
    }

    @Test
    void countEditorials_reflectsEditorialSummariesRowCount() {
        long before = editorialService.countEditorials();

        Artist artist = artistRepository.save(new Artist("Count Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Count Test Album", null, null, null, 2024, 1, "LOG-COUNT", "LABEL-COUNT",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(album.getId(), new AlbumEditorialRequest("Title", "Dek", "Byline", List.of()));

        // editorial_summaries is a plain SQL view — Hibernate's dirty-checking
        // has no idea writes to editorials/album_editorials/albums touch it, so
        // it won't auto-flush before the count query the way it would for a
        // query against those entities directly. Flush explicitly, or this
        // count query runs against pre-insert state.
        entityManager.flush();

        assertThat(editorialService.countEditorials()).isEqualTo(before + 1);
    }

    @Test
    void getFeatured_returnsEmpty_whenNothingIsFeatured() {
        editorialRepository.clearFeaturated();

        assertThat(editorialService.getFeatured(UUID.randomUUID())).isEmpty();
    }

    @Test
    void setFeaturated_marksExactlyOneEditorial_clearingWhicheverWasFeaturedBefore() {
        Artist artist = artistRepository.save(new Artist("Featured Test Artist", null, null, null));
        Album albumA = albumRepository.save(new Album(
            artist, "Featured Album A", null, null, null, 2024, 1, "LOG-FEAT-A", "LABEL-FEAT-A",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        Album albumB = albumRepository.save(new Album(
            artist, "Featured Album B", null, null, null, 2024, 1, "LOG-FEAT-B", "LABEL-FEAT-B",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        AlbumEditorial editorialA = editorialService.upsertAlbumEditorial(
            albumA.getId(), new AlbumEditorialRequest("A", "dek", "byline", List.of())
        );
        AlbumEditorial editorialB = editorialService.upsertAlbumEditorial(
            albumB.getId(), new AlbumEditorialRequest("B", "dek", "byline", List.of())
        );

        editorialService.setFeaturated(editorialA.getId());
        assertThat(editorialSummaryRepository.findFirstByFeaturatedTrue().map(EditorialSummary::getId))
            .contains(editorialA.getId());

        editorialService.setFeaturated(editorialB.getId());
        assertThat(editorialSummaryRepository.findFirstByFeaturatedTrue().map(EditorialSummary::getId))
            .contains(editorialB.getId());
    }

    @Test
    void setFeaturated_rejectsUnknownEditorialId() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> editorialService.setFeaturated(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listEditorials_populatesLogNumberFromTheAlbum() {
        Artist artist = artistRepository.save(new Artist("Catalogue Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Catalogue Test Album", null, null, null, 2024, 1, "LOG-CATALOGUE-1", "LABEL-CAT",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(
            album.getId(), new AlbumEditorialRequest("Catalogue Test Title", "dek", "byline", List.of())
        );
        entityManager.flush();

        Page<CatalogueEditorialDto> page = editorialService.listEditorials(
            EditorialOwnerType.ALBUM, "Catalogue Test Title", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(page.getContent()).hasSize(1);
        CatalogueEditorialDto dto = page.getContent().get(0);
        assertThat(dto.ownerName()).isEqualTo("Catalogue Test Album");
        assertThat(dto.contextName()).isEqualTo("Catalogue Test Artist");
        assertThat(dto.logNumber()).isEqualTo("LOG-CATALOGUE-1");
    }

    @Test
    void listEditorials_artistHasNoLogNumber() {
        Artist artist = artistRepository.save(new Artist("Catalogue Artist No Log", null, null, null));
        editorialService.upsertArtistEditorial(
            artist.getId(), new ArtistEditorialRequest("Catalogue Artist Title", "dek", "byline", List.of())
        );
        entityManager.flush();

        Page<CatalogueEditorialDto> page = editorialService.listEditorials(
            EditorialOwnerType.ARTIST, "Catalogue Artist Title", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).logNumber()).isNull();
    }

    @Test
    void upsertAlbumEditorial_rejectsDuplicateTitleAcrossDifferentAlbums() {
        Artist artist = artistRepository.save(new Artist("Unique Title Artist", null, null, null));
        Album albumA = albumRepository.save(new Album(
            artist, "Unique Title Album A", null, null, null, 2024, 1, "LOG-UNIQ-A", "LABEL-UNIQ-A",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        Album albumB = albumRepository.save(new Album(
            artist, "Unique Title Album B", null, null, null, 2024, 1, "LOG-UNIQ-B", "LABEL-UNIQ-B",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(
            albumA.getId(), new AlbumEditorialRequest("Duplicate Title Test", "dek", "byline", List.of())
        );

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> editorialService.upsertAlbumEditorial(
                albumB.getId(), new AlbumEditorialRequest("Duplicate Title Test", "dek", "byline", List.of())
            )
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void upsertAlbumEditorial_allowsReSavingWithItsOwnUnchangedTitle() {
        Artist artist = artistRepository.save(new Artist("Resave Title Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Resave Title Album", null, null, null, 2024, 1, "LOG-RESAVE", "LABEL-RESAVE",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(
            album.getId(), new AlbumEditorialRequest("Resave Same Title", "dek", "byline", List.of())
        );

        AlbumEditorial resaved = editorialService.upsertAlbumEditorial(
            album.getId(), new AlbumEditorialRequest("Resave Same Title", "new dek", "new byline", List.of())
        );

        assertThat(resaved.getDek()).isEqualTo("new dek");
    }

    @Test
    void getRecentAlbumEditorials_returnsAlbumEditorialShapeWithLogNumber() {
        Artist artist = artistRepository.save(new Artist("Recent Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Recent Test Album", null, null, "http://img.example/album.jpg", 2024, 1,
            "LOG-RECENT-1", "LABEL-RECENT", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(
            album.getId(), new AlbumEditorialRequest("Recent Test Editorial Title", "dek", "byline", List.of())
        );
        entityManager.flush();

        // Just-created, so it has the newest possible createdAt — always
        // within the top RECENT_ALBUMS_LIMIT results, never flaky here.
        List<RecentAlbumEditorialDto> recent = editorialService.getRecentAlbumEditorials(UUID.randomUUID());

        RecentAlbumEditorialDto dto = recent.stream()
            .filter(r -> r.title().equals("Recent Test Editorial Title"))
            .findFirst()
            .orElseThrow();
        assertThat(dto.albumId()).isEqualTo(album.getId());
        assertThat(dto.artistId()).isEqualTo(artist.getId());
        assertThat(dto.imageUrl()).isEqualTo("http://img.example/album.jpg");
        assertThat(dto.albumName()).isEqualTo("Recent Test Album");
        assertThat(dto.artistName()).isEqualTo("Recent Test Artist");
        assertThat(dto.logNumber()).isEqualTo("LOG-RECENT-1");
    }

    @Test
    void getLastLog_returnsNewestAlbumEditorialWithTracksOrderedByTrackNumber() {
        Artist artist = artistRepository.save(new Artist("Last Log Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Last Log Album", null, null, "http://img.example/last-log.jpg", 2023, 1,
            "LOG-LAST", "LABEL-LAST", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        editorialService.upsertAlbumEditorial(
            album.getId(), new AlbumEditorialRequest("Last Log Editorial", "dek", "byline", List.of())
        );

        Track trackA = trackRepository.save(new Track(
            album, null, "Track A", null, null, null, false, null, null, null, null, null, null
        ));
        Track trackB = trackRepository.save(new Track(
            album, null, "Track B", null, null, null, false, null, null, null, null, null, null
        ));
        editorialService.upsertTrackEditorial(
            trackA.getId(), new TrackEditorialRequest("Track A Editorial", "dek A", "byline A", List.of())
        );
        editorialService.upsertTrackEditorial(
            trackB.getId(), new TrackEditorialRequest("Track B Editorial", "dek B", "byline B", List.of())
        );
        entityManager.flush();

        // Track B placed first (trackNumber 1) despite being upserted second —
        // asserts getLastLog sorts by trackNumber, not insertion/query order.
        when(graphService.getTrackPlacements(album.getId())).thenReturn(List.of(
            new TrackPlacement(trackA.getId(), 2),
            new TrackPlacement(trackB.getId(), 1)
        ));

        // Just-created, so it has the newest possible createdAt — always
        // the single most recent row here, never flaky.
        LastLogDto dto = editorialService.getLastLog(UUID.randomUUID()).orElseThrow();

        assertThat(dto.title()).isEqualTo("Last Log Editorial");
        assertThat(dto.artistName()).isEqualTo("Last Log Artist");
        assertThat(dto.releaseYear()).isEqualTo(2023);
        assertThat(dto.imageUrl()).isEqualTo("http://img.example/last-log.jpg");
        assertThat(dto.tracks()).extracting("title").containsExactly("Track B Editorial", "Track A Editorial");
        assertThat(dto.tracks()).extracting("trackNumber").containsExactly(1, 2);
    }

    @Test
    void getFeaturedTracks_returnsOnlyTracksMarkedFeatured() {
        // Clears any real featured track first — this test needs a known
        // starting set, not whatever's actually curated live.
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();

        Artist artist = artistRepository.save(new Artist("Featured Track Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Featured Track Album", null, null, null, 2022, 1,
            "LOG-FEATURED-TRACK", "LABEL-FT", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
        Track featuredTrack = trackRepository.save(new Track(
            album, null, "Featured Track", null, null, "http://img.example/featured-track.jpg", false,
            null, null, null, null, null, null
        ));
        Track otherTrack = trackRepository.save(new Track(
            album, null, "Not Featured Track", null, null, null, false, null, null, null, null, null, null
        ));
        editorialService.upsertTrackEditorial(
            featuredTrack.getId(), new TrackEditorialRequest("Featured Track Editorial", "dek", "byline", List.of())
        );
        editorialService.upsertTrackEditorial(
            otherTrack.getId(), new TrackEditorialRequest("Not Featured Track Editorial", "dek", "byline", List.of())
        );

        trackRepository.markFeatured(featuredTrack.getId());

        List<FeaturedTrackDto> featured = editorialService.getFeaturedTracks(UUID.randomUUID());

        assertThat(featured).extracting("title").containsExactly("Featured Track Editorial");
        FeaturedTrackDto dto = featured.get(0);
        assertThat(dto.trackName()).isEqualTo("Featured Track");
        assertThat(dto.imageUrl()).isEqualTo("http://img.example/featured-track.jpg");
        assertThat(dto.albumName()).isEqualTo("Featured Track Album");
        assertThat(dto.albumId()).isEqualTo(album.getId());
        assertThat(dto.logNumber()).isEqualTo("LOG-FEATURED-TRACK");
    }

    @Test
    void getLastLog_returnsEmpty_whenNoAlbumEditorialExists() {
        editorialRepository.deleteAll();
        entityManager.flush();

        assertThat(editorialService.getLastLog(UUID.randomUUID())).isEmpty();
    }
}
