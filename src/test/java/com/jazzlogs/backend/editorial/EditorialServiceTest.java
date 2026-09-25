package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.editorial.dto.BlockRequest;
import com.jazzlogs.backend.editorial.dto.EditorialTrackSummaryDto;
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.LatestEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.storage.ImageStorageService;
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
    private TrackEditorialRepository trackEditorialRepository;

    @Autowired
    private LikeService likeService;

    @MockitoBean
    private GraphService graphService;

    @MockitoBean
    private ImageStorageService imageStorageService;

    @MockitoBean
    private EmbeddingService embeddingService;

    // Real, currently-curated editorials in the shared dev DB this suite runs
    // against can already carry any byline — the getRecentByByline_* tests
    // need a known starting set (none), not whatever's actually written live.
    @BeforeEach
    void clearRealBylines() {
        entityManager.createQuery("UPDATE TrackEditorial te SET te.byline = :jazzlogs")
            .setParameter("jazzlogs", EditorialByline.JAZZLOGS)
            .executeUpdate();
    }

    @Test
    void upsertTrackEditorial_persists() {
        Track track = persistTrack("Test Track");

        TrackEditorialRequest request = new TrackEditorialRequest("A Title", "1", "A dek", EditorialByline.JAZZLOGS, List.of());

        TrackEditorial saved = editorialService.upsertTrackEditorial(track.getId(), request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("A Title");
    }

    /** Unsigned pieces (byline omitted) default to the outlet itself. */
    @Test
    void upsertTrackEditorial_defaultsBylineToJazzlogsWhenOmitted() {
        Track track = persistTrack("Byline Default Track");

        TrackEditorial saved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("No Byline Title", "1", "dek", null, List.of())
        );

        assertThat(saved.getByline()).isEqualTo(EditorialByline.JAZZLOGS);
    }

    @Test
    void upsertTrackEditorial_rejectsDuplicateTitleAcrossDifferentTracks() {
        Track trackA = persistTrack("Unique Title Track A");
        Track trackB = persistTrack("Unique Title Track B");
        editorialService.upsertTrackEditorial(
            trackA.getId(), new TrackEditorialRequest("Duplicate Title Test", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class,
            () -> editorialService.upsertTrackEditorial(
                trackB.getId(), new TrackEditorialRequest("Duplicate Title Test", "1", "dek", EditorialByline.JAZZLOGS, List.of())
            )
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void upsertTrackEditorial_allowsReSavingWithItsOwnUnchangedTitle() {
        Track track = persistTrack("Resave Title Track");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Resave Same Title", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        TrackEditorial resaved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Resave Same Title", "1", "new dek", EditorialByline.JAZZLOGS, List.of())
        );

        assertThat(resaved.getDek()).isEqualTo("new dek");
    }

    @Test
    void upsertTrackEditorial_embedsBlocksWithTrackAlbumArtistMetadata() {
        Album album = persistAlbum("Metadata Test Album");
        Track track = trackRepository.save(new Track(
            album, null, "Metadata Test Track", null, null, null, null, null, null, null, null, null
        ));
        when(embeddingService.embedBatch(List.of("Some editorial prose."))).thenReturn(List.of(new float[] {0.1f}));

        BlockRequest block = new BlockRequest(EditorialBlockType.PARA, null, "Some editorial prose.", BlockContentCategory.CONTEXT);
        TrackEditorial saved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Metadata Test Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of(block))
        );

        Map<String, Object> metadata = saved.getBlocks().get(0).getEmbeddingMetadata();
        assertThat(metadata)
            .containsEntry("editorialType", "TrackEditorial")
            .containsEntry("editorialId", saved.getId().toString())
            .containsEntry("trackName", "Metadata Test Track")
            .containsEntry("albumName", "Metadata Test Album")
            .containsEntry("artistName", "Metadata Test Album Artist");
    }

    @Test
    void countEditorials_reflectsTrackEditorialRowCount() {
        long before = editorialService.countEditorials();

        editorialService.upsertTrackEditorial(
            persistTrack("Count Test Track").getId(),
            new TrackEditorialRequest("Count Test Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        assertThat(editorialService.countEditorials()).isEqualTo(before + 1);
    }

    @Test
    void listEditorials_matchesByEditorialTitleOrTrackName() {
        Track track = persistTrack("Searchable Track Name");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("A Wholly Different Title", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        Page<TrackEditorialCatalogueDto> byTitle = editorialService.listEditorials(
            "wholly different", PageRequest.of(0, 10), UUID.randomUUID()
        );
        Page<TrackEditorialCatalogueDto> byTrackName = editorialService.listEditorials(
            "searchable track", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(byTitle.getContent()).extracting(TrackEditorialCatalogueDto::trackId).contains(track.getId());
        assertThat(byTrackName.getContent()).extracting(TrackEditorialCatalogueDto::trackId).contains(track.getId());
    }

    @Test
    void listEditorials_returnsTrackAlbumFieldsAndLikeState() {
        Album album = persistAlbum("Catalogue Test Album");
        Track track = trackRepository.save(new Track(
            album, null, "Catalogue Test Track", null, null, "http://img.example/track.jpg",
            null, null, null, null, null, null
        ));
        TrackEditorial editorial = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Catalogue Test Editorial", "1", "A dek", EditorialByline.JAZZLOGS, List.of())
        );
        editorial.updateCoverImageUrl("http://img.example/editorial-cover.jpg");

        Page<TrackEditorialCatalogueDto> page = editorialService.listEditorials(
            "Catalogue Test Editorial", PageRequest.of(0, 10), UUID.randomUUID()
        );

        assertThat(page.getContent()).hasSize(1);
        TrackEditorialCatalogueDto dto = page.getContent().get(0);
        assertThat(dto.trackId()).isEqualTo(track.getId());
        assertThat(dto.trackName()).isEqualTo("Catalogue Test Track");
        assertThat(dto.editorialCoverUrl()).isEqualTo("http://img.example/editorial-cover.jpg");
        assertThat(dto.albumName()).isEqualTo("Catalogue Test Album");
        assertThat(dto.albumId()).isEqualTo(album.getId());
        assertThat(dto.artistName()).isEqualTo("Catalogue Test Album Artist");
        assertThat(dto.dek()).isEqualTo("A dek");
        assertThat(dto.likedByCurrentUser()).isFalse();
    }

    @Test
    void listEditorials_filtersByBylineAndKeepsPagination() {
        Track markTrack = persistTrack("Paged Mark Track");
        Track adamTrack = persistTrack("Paged Adam Track");
        editorialService.upsertTrackEditorial(
            markTrack.getId(), new TrackEditorialRequest("Paged Mark Editorial", "1", "dek", EditorialByline.MARK, List.of())
        );
        editorialService.upsertTrackEditorial(
            adamTrack.getId(), new TrackEditorialRequest("Paged Adam Editorial", "1", "dek", EditorialByline.ADAM, List.of())
        );

        Page<TrackEditorialCatalogueDto> page = editorialService.listEditorials(
            null, EditorialByline.MARK, PageRequest.of(0, 1), UUID.randomUUID()
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).byline()).isEqualTo(EditorialByline.MARK);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getFeaturedTracks_returnsOnlyTracksMarkedFeatured() {
        // Clears any real featured track first — this test needs a known
        // starting set, not whatever's actually curated live.
        entityManager.createQuery("UPDATE Track t SET t.featured = false").executeUpdate();

        Album album = persistAlbum("Featured Track Album");
        Track featuredTrack = trackRepository.save(new Track(
            album, null, "Featured Track", null, null, "http://img.example/featured-track.jpg",
            null, null, null, null, null, null
        ));
        Track otherTrack = trackRepository.save(new Track(
            album, null, "Not Featured Track", null, null, null, null, null, null, null, null, null
        ));
        TrackEditorial featuredEditorial = editorialService.upsertTrackEditorial(
            featuredTrack.getId(), new TrackEditorialRequest("Featured Track Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );
        featuredEditorial.updateCoverImageUrl("http://img.example/featured-editorial-cover.jpg");
        editorialService.upsertTrackEditorial(
            otherTrack.getId(), new TrackEditorialRequest("Not Featured Track Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );

        trackRepository.markFeatured(featuredTrack.getId());

        List<FeaturedTrackDto> featured = editorialService.getFeaturedTracks(UUID.randomUUID());

        assertThat(featured).extracting("title").containsExactly("Featured Track Editorial");
        FeaturedTrackDto dto = featured.get(0);
        assertThat(dto.trackName()).isEqualTo("Featured Track");
        assertThat(dto.imageUrl()).isEqualTo("http://img.example/featured-editorial-cover.jpg");
        assertThat(dto.albumName()).isEqualTo("Featured Track Album");
        assertThat(dto.artistName()).isEqualTo("Featured Track Album Artist");
    }

    @Test
    void setTrackEditorialCoverImage_uploadsUnderItsOwnKeyAndPersistsTheReturnedUrl() {
        Track track = persistTrack("Track Image Track");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Track Image Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/cover", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/track-editorials/" + track.getId() + "/cover.jpg");

        editorialService.setTrackEditorialCoverImage(track.getId(), file);

        TrackEditorial reloaded = trackEditorialRepository.findByTrackId(track.getId()).orElseThrow();
        assertThat(reloaded.getCoverImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/track-editorials/" + track.getId() + "/cover.jpg");
    }

    @Test
    void setTrackEditorialCoverImage_rejectsATrackWithNoEditorialYet() {
        Track track = persistTrack("No Editorial Track");
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> editorialService.setTrackEditorialCoverImage(track.getId(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setTrackEditorialPrincipalSecondaryBannerFooterImages_eachUploadUnderTheirOwnKey() {
        Track track = persistTrack("Track Layout Images Track");
        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Track Layout Images Editorial", "1", "dek", EditorialByline.JAZZLOGS, List.of())
        );
        MockMultipartFile principal = new MockMultipartFile("file", "principal.jpg", "image/jpeg", "principal-bytes".getBytes());
        MockMultipartFile secondary = new MockMultipartFile("file", "secondary.jpg", "image/jpeg", "secondary-bytes".getBytes());
        MockMultipartFile banner = new MockMultipartFile("file", "banner.jpg", "image/jpeg", "banner-bytes".getBytes());
        MockMultipartFile footer = new MockMultipartFile("file", "footer.jpg", "image/jpeg", "footer-bytes".getBytes());
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/principal", principal)).thenReturn("http://img/principal.jpg");
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/secondary", secondary)).thenReturn("http://img/secondary.jpg");
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/banner", banner)).thenReturn("http://img/banner.jpg");
        when(imageStorageService.upload("track-editorials/" + track.getId() + "/footer", footer)).thenReturn("http://img/footer.jpg");

        editorialService.setTrackEditorialPrincipalImage(track.getId(), principal);
        editorialService.setTrackEditorialSecondaryImage(track.getId(), secondary);
        editorialService.setTrackEditorialBannerImage(track.getId(), banner);
        editorialService.setTrackEditorialFooterImage(track.getId(), footer);

        TrackEditorial reloaded = trackEditorialRepository.findByTrackId(track.getId()).orElseThrow();
        assertThat(reloaded.getPrincipalImageUrl()).isEqualTo("http://img/principal.jpg");
        assertThat(reloaded.getSecondaryImageUrl()).isEqualTo("http://img/secondary.jpg");
        assertThat(reloaded.getBannerImageUrl()).isEqualTo("http://img/banner.jpg");
        assertThat(reloaded.getFooterImageUrl()).isEqualTo("http://img/footer.jpg");
    }

    @Test
    void getRecentByByline_returnsOnlyThatBylinesEditorials_newestFirst() {
        Track markOld = persistTrack("Mark Old Track");
        Track adam = persistTrack("Adam Track");
        Track markNew = persistTrack("Mark New Track");
        editorialService.upsertTrackEditorial(
            markOld.getId(), new TrackEditorialRequest("Mark Old Editorial", "1", "dek", EditorialByline.MARK, List.of())
        );
        editorialService.upsertTrackEditorial(
            adam.getId(), new TrackEditorialRequest("Adam Editorial", "1", "dek", EditorialByline.ADAM, List.of())
        );
        editorialService.upsertTrackEditorial(
            markNew.getId(), new TrackEditorialRequest("Mark New Editorial", "1", "dek", EditorialByline.MARK, List.of())
        );

        List<EditorialTrackSummaryDto> result = editorialService.getRecentByByline(EditorialByline.MARK, 10, UUID.randomUUID());

        assertThat(result).extracting(EditorialTrackSummaryDto::title).containsExactly("Mark New Editorial", "Mark Old Editorial");
        assertThat(result).allSatisfy(dto -> assertThat(dto.byline()).isEqualTo(EditorialByline.MARK));
    }

    @Test
    void getRecentByByline_clampsNAboveTenToTen() {
        for (int i = 0; i < 11; i++) {
            Track track = persistTrack("Clamp Track " + i);
            editorialService.upsertTrackEditorial(
                track.getId(), new TrackEditorialRequest("Clamp Editorial " + i, "1", "dek", EditorialByline.LAURA, List.of())
            );
        }

        List<EditorialTrackSummaryDto> result = editorialService.getRecentByByline(EditorialByline.LAURA, 100, UUID.randomUUID());

        assertThat(result).hasSize(10);
    }

    @Test
    void getRecentEditorials_clampsNToFifteenAcrossAllBylines() {
        for (int i = 0; i < 16; i++) {
            Track track = persistTrack("Recent Editorial Track " + i);
            editorialService.upsertTrackEditorial(
                track.getId(), new TrackEditorialRequest(
                    "Recent Editorial " + i, "1", "dek",
                    i % 2 == 0 ? EditorialByline.MARK : EditorialByline.ADAM, List.of()
                )
            );
        }

        List<EditorialTrackSummaryDto> result = editorialService.getRecentEditorials(100, UUID.randomUUID());

        assertThat(result).hasSize(15);
        assertThat(result).extracting(EditorialTrackSummaryDto::byline)
            .contains(EditorialByline.MARK, EditorialByline.ADAM);
    }

    @Test
    void getLatestEditorial_returnsTheNewestEditorialAndItsHook() {
        Track track = persistTrack("Latest Editorial Track");
        when(embeddingService.embedBatch(List.of("A hook preview"))).thenReturn(List.of(new float[1536]));

        editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest(
                "Latest Editorial", "42", "A dek", EditorialByline.MARK,
                List.of(new BlockRequest(EditorialBlockType.LEAD, null, "A hook preview", BlockContentCategory.HOOK))
            )
        );

        LatestEditorialDto result = editorialService.getLatestEditorial(UUID.randomUUID());

        assertThat(result.trackId()).isEqualTo(track.getId());
        assertThat(result.hook()).isEqualTo("A hook preview");
    }

    @Test
    void getRecentByByline_reflectsTheRequestingUsersOwnLike_notJustTheRawCount() {
        Track track = persistTrack("Liked Byline Track");
        TrackEditorial saved = editorialService.upsertTrackEditorial(
            track.getId(), new TrackEditorialRequest("Liked Byline Editorial", "1", "dek", EditorialByline.BOB, List.of())
        );
        UUID likingUserId = UUID.randomUUID();
        likeService.addLike(likingUserId, LikeableEntityType.EDITORIAL, saved.getId());

        List<EditorialTrackSummaryDto> likedByRequester = editorialService.getRecentByByline(EditorialByline.BOB, 10, likingUserId);
        List<EditorialTrackSummaryDto> seenByAnotherUser = editorialService.getRecentByByline(EditorialByline.BOB, 10, UUID.randomUUID());

        assertThat(likedByRequester.get(0).likeCount()).isEqualTo(1);
        assertThat(likedByRequester.get(0).likedByCurrentUser()).isTrue();
        assertThat(seenByAnotherUser.get(0).likeCount()).isEqualTo(1);
        assertThat(seenByAnotherUser.get(0).likedByCurrentUser()).isFalse();
    }

    private Album persistAlbum(String name) {
        Artist artist = artistRepository.save(new Artist(name + " Artist", null, null, null));
        return albumRepository.save(new Album(
            artist, name, null, null, null, 2024, 1
        ));
    }

    private Track persistTrack(String name) {
        Album album = persistAlbum(name + " Album");
        return trackRepository.save(new Track(
            album, null, name, null, null, null, null, null, null, null, null, null
        ));
    }
}
