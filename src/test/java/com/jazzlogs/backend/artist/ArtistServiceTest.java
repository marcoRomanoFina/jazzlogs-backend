package com.jazzlogs.backend.artist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.dto.ArtistTagsDto;
import com.jazzlogs.backend.artist.dto.ArtistHeaderDto;
import com.jazzlogs.backend.artist.dto.AlbumSummaryDto;
import com.jazzlogs.backend.artist.dto.SimilarArtistDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.SimilarArtistEntry;
import com.jazzlogs.backend.graph.VocabularyTag;

// GraphService is mocked here (not the real Neo4jClient-backed bean) — same
// reasoning as AlbumServiceTest: getArtistHeader doesn't call it at all, but
// syncArtistNode (called by other ArtistService methods this test doesn't
// exercise) does. getEssentialListening/getSidemanAlbums/getSimilarArtists
// do call it (getEntryPointAlbumIds/getSidemanAlbumIds/getSimilarArtists
// respectively), which is exactly what's stubbed per test below.
@SpringBootTest
@Transactional
class ArtistServiceTest {

    @Autowired
    private ArtistService artistService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @MockitoBean
    private GraphService graphService;

    @Test
    void getArtistHeader_returnsArtistFields() {
        Artist artist = persistArtist("Header Test Artist");

        ArtistHeaderDto dto = artistService.getArtistHeader(artist.getId());

        assertThat(dto.id()).isEqualTo(artist.getId());
        assertThat(dto.name()).isEqualTo("Header Test Artist");
    }

    @Test
    void getArtistHeader_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> artistService.getArtistHeader(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

        Page<AlbumSummaryDto> page = artistService.getEssentialListening(artist.getId(), PageRequest.of(0, 5));

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

        Page<AlbumSummaryDto> page = artistService.getEssentialListening(target.getId(), PageRequest.of(0, 5));

        assertThat(page.getContent()).extracting(AlbumSummaryDto::name).containsExactly("Older Album", "Newer Album");
        assertThat(page.getContent().get(0).artistId()).isEqualTo(otherArtist.getId());
        assertThat(page.getContent().get(0).artistName()).isEqualTo("Other Artist");
    }

    @Test
    void getSidemanAlbums_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> artistService.getSidemanAlbums(UUID.randomUUID(), PageRequest.of(0, 6))
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSidemanAlbums_returnsEmptyPage_whenNoSidemanAlbums() {
        Artist artist = persistArtist("No Sideman Albums Artist");
        when(graphService.getSidemanAlbumIds(artist.getId())).thenReturn(List.of());

        Page<AlbumSummaryDto> page = artistService.getSidemanAlbums(artist.getId(), PageRequest.of(0, 6));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void getSidemanAlbums_ordersByReleaseYearAscending_andIncludesTheAlbumsOwnArtist() {
        Artist sideman = persistArtist("Sideman Artist");
        Artist leader = persistArtist("Leader Artist");
        Album newer = persistAlbum(leader, "Newer Album", 2020);
        Album older = persistAlbum(leader, "Older Album", 1965);
        when(graphService.getSidemanAlbumIds(sideman.getId())).thenReturn(List.of(newer.getId(), older.getId()));

        Page<AlbumSummaryDto> page = artistService.getSidemanAlbums(sideman.getId(), PageRequest.of(0, 6));

        assertThat(page.getContent()).extracting(AlbumSummaryDto::name).containsExactly("Older Album", "Newer Album");
        assertThat(page.getContent().get(0).artistId()).isEqualTo(leader.getId());
        assertThat(page.getContent().get(0).artistName()).isEqualTo("Leader Artist");
    }

    @Test
    void getSimilarArtists_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> artistService.getSimilarArtists(UUID.randomUUID(), PageRequest.of(0, 6))
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSimilarArtists_returnsEmptyPage_whenNoSimilarArtists() {
        Artist artist = persistArtist("No Similar Artists Artist");
        when(graphService.getSimilarArtists(artist.getId())).thenReturn(List.of());

        Page<SimilarArtistDto> page = artistService.getSimilarArtists(artist.getId(), PageRequest.of(0, 6));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void getSimilarArtists_ordersByNameAscending_andIncludesTheCuratedReason() {
        Artist target = persistArtist("Target Artist");
        Artist zArtist = persistArtist("Zeta Artist");
        Artist aArtist = persistArtist("Alpha Artist");
        when(graphService.getSimilarArtists(target.getId())).thenReturn(List.of(
            new SimilarArtistEntry(zArtist.getId(), zArtist.getName(), "Same rhythm section"),
            new SimilarArtistEntry(aArtist.getId(), aArtist.getName(), "Same label, same era")
        ));

        Page<SimilarArtistDto> page = artistService.getSimilarArtists(target.getId(), PageRequest.of(0, 6));

        assertThat(page.getContent()).extracting(SimilarArtistDto::name).containsExactly("Alpha Artist", "Zeta Artist");
        assertThat(page.getContent().get(0).reason()).isEqualTo("Same label, same era");
        assertThat(page.getContent().get(1).reason()).isEqualTo("Same rhythm section");
    }

    @Test
    void removeSimilarArtist_removesUnidirectionalByDefault() {
        Artist artist = persistArtist("Remove Similar Test Artist");
        Artist similar = persistArtist("Remove Similar Test Similar Artist");

        artistService.removeSimilarArtist(artist.getId(), similar.getId(), false);

        verify(graphService).removeSimilarArtist(artist.getId(), similar.getId(), false);
    }

    @Test
    void removeSimilarArtist_rejectsUnknownArtist() {
        Artist similar = persistArtist("Known Similar Test Artist");

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> artistService.removeSimilarArtist(UUID.randomUUID(), similar.getId(), false)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getArtistTags_rejectsUnknownArtist() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> artistService.getArtistTags(UUID.randomUUID())
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getArtistTags_assemblesEveryGraphLookup() {
        Artist artist = persistArtist("Tags Test Artist");
        when(graphService.getArtistInstruments(artist.getId())).thenReturn(List.of(new VocabularyTag("PIANO", "Piano")));
        when(graphService.getArtistStyles(artist.getId())).thenReturn(List.of(new VocabularyTag("HARD_BOP", "Hard Bop")));
        when(graphService.getArtistContexts(artist.getId())).thenReturn(List.of(new VocabularyTag("LATE_NIGHT", "Late Night")));

        ArtistTagsDto dto = artistService.getArtistTags(artist.getId());

        assertThat(dto.instruments()).extracting(VocabularyTag::code).containsExactly("PIANO");
        assertThat(dto.styles()).extracting(VocabularyTag::code).containsExactly("HARD_BOP");
        assertThat(dto.contexts()).extracting(VocabularyTag::code).containsExactly("LATE_NIGHT");
    }

    private Artist persistArtist(String name) {
        return artistRepository.save(new Artist(name, null, null, null));
    }

    private Album persistAlbum(Artist artist, String name, Integer releaseYear) {
        return albumRepository.save(new Album(
            artist, name, null, null, null, releaseYear, 1
        ));
    }
}
