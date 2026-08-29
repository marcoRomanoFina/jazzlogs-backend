package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;

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
    private EntityManager entityManager;

    @Autowired
    private EditorialRepository editorialRepository;

    @Autowired
    private EditorialSummaryRepository editorialSummaryRepository;

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
}
