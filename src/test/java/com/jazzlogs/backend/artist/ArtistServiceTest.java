package com.jazzlogs.backend.artist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.dto.ArtistHeaderDto;
import com.jazzlogs.backend.artist.dto.EssentialListeningAlbumDto;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.review.ReviewService;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest: getArtistHeader doesn't call it at all, but
// syncArtistNode (called by other ArtistService methods this test doesn't
// exercise) does. getEssentialListening does call it (getEntryPointAlbumIds),
// which is exactly what's stubbed per test below.
@SpringBootTest
@Transactional
class ArtistServiceTest {

    @Autowired
    private ArtistService artistService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

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

    @Test
    void getEssentialListening_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> artistService.getEssentialListening(UUID.randomUUID(), PageRequest.of(0, 5))
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getEssentialListening_returnsEmptyPage_whenNoEntryPointAlbums() {
        Artist artist = persistArtist("No Entry Points Artist");
        when(graphService.getEntryPointAlbumIds(artist.getId())).thenReturn(List.of());

        Page<EssentialListeningAlbumDto> page = artistService.getEssentialListening(artist.getId(), PageRequest.of(0, 5));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void getEssentialListening_ordersByReleaseYearAscending_andIncludesTheAlbumsOwnArtist() {
        Artist target = persistArtist("Target Artist");
        // getEssentialListening trusts whatever GraphService.getEntryPointAlbumIds
        // returns and resolves each album's own artist from Postgres — this
        // stubs a mismatched artist purely to prove that resolution is
        // correct, not because AlbumService.markEntryPoint would ever let a
        // real request create one (it enforces album.artist == artistId).
        Artist otherArtist = persistArtist("Other Artist");
        Album newer = persistAlbum(otherArtist, "Newer Album", 2020);
        Album older = persistAlbum(otherArtist, "Older Album", 1965);
        when(graphService.getEntryPointAlbumIds(target.getId())).thenReturn(List.of(newer.getId(), older.getId()));

        Page<EssentialListeningAlbumDto> page = artistService.getEssentialListening(target.getId(), PageRequest.of(0, 5));

        assertThat(page.getContent()).extracting(EssentialListeningAlbumDto::name).containsExactly("Older Album", "Newer Album");
        assertThat(page.getContent().get(0).artistId()).isEqualTo(otherArtist.getId());
        assertThat(page.getContent().get(0).artistName()).isEqualTo("Other Artist");
    }

    @Test
    void getEssentialListening_includesRatingAndDek_nullWhenNeitherExists() {
        Artist target = persistArtist("Rated Test Artist");
        Artist albumArtist = persistArtist("Rated Album Artist");
        Album rated = persistAlbum(albumArtist, "Rated Album", 2000);
        Album bare = persistAlbum(albumArtist, "Bare Album", 2001);
        when(graphService.getEntryPointAlbumIds(target.getId())).thenReturn(List.of(rated.getId(), bare.getId()));

        User reviewer = userRepository.save(new User(UUID.randomUUID(), "essential-listening-" + UUID.randomUUID() + "@example.com"));
        reviewService.createReview(reviewer.getId(), rated.getId(), new BigDecimal("4.5"), null, List.of());
        editorialService.upsertAlbumEditorial(rated.getId(), new AlbumEditorialRequest("Title", "A great dek", null, List.of()));
        entityManager.flush();
        entityManager.clear();

        Page<EssentialListeningAlbumDto> page = artistService.getEssentialListening(target.getId(), PageRequest.of(0, 5));

        EssentialListeningAlbumDto ratedDto = page.getContent().stream().filter(a -> a.id().equals(rated.getId())).findFirst().orElseThrow();
        EssentialListeningAlbumDto bareDto = page.getContent().stream().filter(a -> a.id().equals(bare.getId())).findFirst().orElseThrow();

        assertThat(ratedDto.avgRating()).isEqualByComparingTo("4.5");
        assertThat(ratedDto.dek()).isEqualTo("A great dek");
        assertThat(bareDto.avgRating()).isNull();
        assertThat(bareDto.dek()).isNull();
    }

    private Artist persistArtist(String name) {
        return artistRepository.save(new Artist(name, null, null, null));
    }

    private Album persistAlbum(Artist artist, String name, Integer releaseYear) {
        return albumRepository.save(new Album(
            artist, name, null, null, null, releaseYear, 1,
            "LOG-" + UUID.randomUUID(), "LABEL", VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }
}
