package com.jazzlogs.backend.artist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.artist.dto.ArtistHeaderDto;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest: getArtistHeader doesn't call it at all, but
// syncArtistNode (called by other ArtistService methods this test doesn't
// exercise) does.
@SpringBootTest
@Transactional
class ArtistServiceTest {

    @Autowired
    private ArtistService artistService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private LikeService likeService;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private GraphService graphService;

    @Test
    void getArtistHeader_returnsArtistFields_noEditorial() {
        Artist artist = persistArtist("Header Test Artist");

        ArtistHeaderDto dto = artistService.getArtistHeader(artist.getId(), UUID.randomUUID());

        assertThat(dto.id()).isEqualTo(artist.getId());
        assertThat(dto.name()).isEqualTo("Header Test Artist");
        assertThat(dto.editorial()).isNull();
    }

    @Test
    void getArtistHeader_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> artistService.getArtistHeader(UUID.randomUUID(), UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getArtistHeader_includesEditorialAndLikeState() {
        Artist artist = persistArtist("Editorial Test Artist");
        editorialService.upsertArtistEditorial(artist.getId(), new ArtistEditorialRequest("A Life in Jazz", "dek", "byline", List.of()));
        UUID viewer = UUID.randomUUID();

        ArtistHeaderDto before = artistService.getArtistHeader(artist.getId(), viewer);
        assertThat(before.editorial()).isNotNull();
        assertThat(before.editorial().title()).isEqualTo("A Life in Jazz");
        assertThat(before.editorial().likedByCurrentUser()).isFalse();

        likeService.addLike(viewer, LikeableEntityType.EDITORIAL, before.editorial().id());
        // likeCount is bumped via an atomic UPDATE (EditorialRepository.incrementLikeCount),
        // not a save() — the ArtistEditorial instance already in this
        // transaction's persistence context won't see it without a clear().
        entityManager.clear();
        ArtistHeaderDto after = artistService.getArtistHeader(artist.getId(), viewer);

        assertThat(after.editorial().likedByCurrentUser()).isTrue();
        assertThat(after.editorial().likeCount()).isEqualTo(1);
    }

    private Artist persistArtist(String name) {
        return artistRepository.save(new Artist(name, null, null, null));
    }
}
