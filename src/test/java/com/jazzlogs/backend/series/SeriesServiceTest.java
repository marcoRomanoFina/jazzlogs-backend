package com.jazzlogs.backend.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.series.dto.ChapterStatus;
import com.jazzlogs.backend.series.dto.SeriesChapterDetailDto;
import com.jazzlogs.backend.series.dto.SeriesChapterInput;
import com.jazzlogs.backend.series.dto.SeriesChapterTrackDto;
import com.jazzlogs.backend.series.dto.SeriesDetailDto;
import com.jazzlogs.backend.series.dto.FeaturedSeriesDto;
import com.jazzlogs.backend.series.dto.SeriesSummaryDto;
import com.jazzlogs.backend.series.dto.SeriesUpsertRequest;
import com.jazzlogs.backend.storage.AudioStorageService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.trackrating.TrackRating;
import com.jazzlogs.backend.trackrating.TrackRatingRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

@SpringBootTest
@Transactional
class SeriesServiceTest {

    @Autowired
    private SeriesService seriesService;

    @Autowired
    private SeriesRepository seriesRepository;

    @Autowired
    private SeriesChapterRepository seriesChapterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private TrackRatingRepository trackRatingRepository;

    @Autowired
    private ListenService listenService;

    @MockitoBean
    private ImageStorageService imageStorageService;

    @MockitoBean
    private AudioStorageService audioStorageService;

    @MockitoBean
    private GraphService graphService;

    @Test
    void create_rejectsDuplicateTitle() {
        SeriesUpsertRequest request = new SeriesUpsertRequest("Behind The Bandstand", null, null, SeriesVoice.MARK, null, null, null, null);
        seriesService.create(request);

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class, () -> seriesService.create(request));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void update_rejectsRenamingToAnotherSeriesTitle() {
        seriesService.create(new SeriesUpsertRequest("Behind The Bandstand", null, null, SeriesVoice.MARK, null, null, null, null));
        UUID otherId = seriesService.create(
            new SeriesUpsertRequest("Standards Explained", null, null, SeriesVoice.LAURA, null, null, null, null)
        ).id();

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> seriesService.update(otherId, new SeriesUpsertRequest("Behind The Bandstand", null, null, SeriesVoice.LAURA, null, null, null, null)));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** Saving a series' metadata without changing its title must not trip over its own row. */
    @Test
    void update_allowsSavingUnderItsOwnUnchangedTitle() {
        UUID seriesId = seriesService.create(
            new SeriesUpsertRequest("Behind The Bandstand", null, null, SeriesVoice.MARK, null, null, null, null)
        ).id();

        SeriesDetailDto updated = seriesService.update(
            seriesId, new SeriesUpsertRequest("Behind The Bandstand", null, null, SeriesVoice.MARK, null, null, null, null)
        );

        assertThat(updated.title()).isEqualTo("Behind The Bandstand");
    }

    private static final Sort CATALOGUE_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void getCatalogue_returnsOnlyTheRequestedVoice() {
        persistSeries("Catalogue Mark", SeriesVoice.MARK, true);
        persistSeries("Catalogue Laura", SeriesVoice.LAURA, true);

        Page<SeriesSummaryDto> page = seriesService.getCatalogue(SeriesVoice.MARK, false, PageRequest.of(0, 50, CATALOGUE_SORT));

        assertThat(page.getContent()).extracting(SeriesSummaryDto::title)
            .contains("Catalogue Mark")
            .doesNotContain("Catalogue Laura");
    }

    @Test
    void getCatalogue_excludesDraftsUnlessIncludeUnpublished() {
        UUID publishedId = persistSeries("Catalogue Published", SeriesVoice.JAMES, true);
        UUID draftId = persistSeries("Catalogue Draft", SeriesVoice.JAMES, false);

        Page<SeriesSummaryDto> nonAdminPage = seriesService.getCatalogue(SeriesVoice.JAMES, false, PageRequest.of(0, 50, CATALOGUE_SORT));
        assertThat(nonAdminPage.getContent()).extracting(SeriesSummaryDto::id).contains(publishedId).doesNotContain(draftId);

        Page<SeriesSummaryDto> adminPage = seriesService.getCatalogue(SeriesVoice.JAMES, true, PageRequest.of(0, 50, CATALOGUE_SORT));
        assertThat(adminPage.getContent()).extracting(SeriesSummaryDto::id).contains(publishedId, draftId);
    }

    @Test
    void getCatalogue_ordersNewestFirst() {
        UUID olderId = persistSeries("Catalogue Older", SeriesVoice.ALICE, true);
        UUID newerId = persistSeries("Catalogue Newer", SeriesVoice.ALICE, true);

        Page<SeriesSummaryDto> page = seriesService.getCatalogue(SeriesVoice.ALICE, false, PageRequest.of(0, 50, CATALOGUE_SORT));

        List<UUID> ids = page.getContent().stream().map(SeriesSummaryDto::id).toList();
        assertThat(ids.indexOf(newerId)).isLessThan(ids.indexOf(olderId));
    }

    /** The no-voice-filter overload — same query shape, just every voice mixed together. */
    @Test
    void getCatalogue_withoutAVoiceFilter_mixesEveryVoice() {
        UUID markId = persistSeries("Mixed Catalogue Mark", SeriesVoice.MARK, true);
        UUID adamId = persistSeries("Mixed Catalogue Adam", SeriesVoice.ADAM, true);

        Page<SeriesSummaryDto> page = seriesService.getCatalogue(null, false, PageRequest.of(0, 50, CATALOGUE_SORT));

        assertThat(page.getContent()).extracting(SeriesSummaryDto::id).contains(markId, adamId);
    }

    @Test
    void getCatalogue_withoutAVoiceFilter_excludesDraftsUnlessIncludeUnpublished() {
        UUID publishedId = persistSeries("Mixed Catalogue Published", SeriesVoice.MARK, true);
        UUID draftId = persistSeries("Mixed Catalogue Draft", SeriesVoice.ADAM, false);

        Page<SeriesSummaryDto> nonAdminPage = seriesService.getCatalogue(null, false, PageRequest.of(0, 50, CATALOGUE_SORT));
        assertThat(nonAdminPage.getContent()).extracting(SeriesSummaryDto::id).contains(publishedId).doesNotContain(draftId);

        Page<SeriesSummaryDto> adminPage = seriesService.getCatalogue(null, true, PageRequest.of(0, 50, CATALOGUE_SORT));
        assertThat(adminPage.getContent()).extracting(SeriesSummaryDto::id).contains(publishedId, draftId);
    }

    /** styleTags/moodTags/contextTags come from batch queries for the whole page — see GraphService.getSeriesStylesBatch. */
    @Test
    void getCatalogue_attachesBatchedTags() {
        UUID seriesId = persistSeries("Tagged Catalogue Series", SeriesVoice.MARK, true);
        when(graphService.getSeriesStylesBatch(any())).thenReturn(Map.of(seriesId, List.of(new VocabularyTag("SWING", "Swing"))));

        Page<SeriesSummaryDto> page = seriesService.getCatalogue(null, false, PageRequest.of(0, 50, CATALOGUE_SORT));

        SeriesSummaryDto dto = page.getContent().stream().filter(s -> s.id().equals(seriesId)).findFirst().orElseThrow();
        assertThat(dto.styleTags()).containsExactly(new VocabularyTag("SWING", "Swing"));
    }

    @Test
    void create_syncsTheNeo4jNodeAndSetsTags() {
        seriesService.create(new SeriesUpsertRequest(
            "Tagged On Create", null, null, SeriesVoice.MARK, List.of("SWING"), List.of(), List.of(), List.of()
        ));

        verify(graphService).syncSeriesNode(any(), eq("Tagged On Create"));
        verify(graphService).setSeriesTags(any(), eq(List.of("SWING")), eq(List.of()), eq(List.of()), eq(List.of()));
    }

    @Test
    void getSeriesDetail_includesTags() {
        UUID seriesId = persistSeries();
        when(graphService.getSeriesMoods(seriesId)).thenReturn(List.of(new VocabularyTag("MELLOW", "Mellow")));

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);

        assertThat(detail.moodTags()).containsExactly(new VocabularyTag("MELLOW", "Mellow"));
    }

    @Test
    void addChapter_appendsAtEndWithSequentialPositions() {
        UUID seriesId = persistSeries();

        SeriesChapterDetailDto first = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto second = seriesService.addChapter(seriesId, null, introInput());

        assertThat(first.position()).isEqualTo(0);
        assertThat(second.position()).isEqualTo(1);
    }

    @Test
    void removeChapter_doesNotRenumberRemainingRows() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto a = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto b = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto c = seriesService.addChapter(seriesId, null, introInput());

        seriesService.removeChapter(seriesId, b.id());

        List<SeriesChapter> remaining = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);
        assertThat(remaining).extracting(SeriesChapter::getId).containsExactly(a.id(), c.id());
        assertThat(remaining).extracting(SeriesChapter::getPosition).containsExactly(0, 2);
    }

    @Test
    void addChapter_rejectsTrackTypeWithoutTrackId() {
        UUID seriesId = persistSeries();
        SeriesChapterInput input = new SeriesChapterInput(ChapterType.TRACK, null, "Ep 1", null, null);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.addChapter(seriesId, null, input));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addChapter_rejectsIntroTypeWithoutTrackId() {
        UUID seriesId = persistSeries();
        SeriesChapterInput input = new SeriesChapterInput(ChapterType.INTRO, null, null, null, null);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.addChapter(seriesId, null, input));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addChapter_rejectsOutroTypeWithTrackId() {
        UUID seriesId = persistSeries();
        Track track = persistTrack(persistAlbum(persistArtist()));
        SeriesChapterInput input = new SeriesChapterInput(ChapterType.OUTRO, track.getId(), null, null, null);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.addChapter(seriesId, null, input));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Position 0 is always DONE or CURRENT, never LOCKED. */
    @Test
    void chapterStatus_firstChapterIsNeverLocked() {
        UUID seriesId = persistSeries();
        seriesService.addChapter(seriesId, null, introInput());
        User user = persistUser();

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, user.getId(), true);

        assertThat(detail.chapters().get(0).status()).isIn(ChapterStatus.CURRENT, ChapterStatus.DONE);
    }

    @Test
    void chapterStatus_onlyCurrentUnlocksAfterCompletingPreviousOnes() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto a = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto b = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto c = seriesService.addChapter(seriesId, null, introInput());
        User user = persistUser();

        SeriesDetailDto beforeAnyCompletion = seriesService.getSeriesDetail(seriesId, user.getId(), true);
        assertThat(statusOf(beforeAnyCompletion, a.id())).isEqualTo(ChapterStatus.CURRENT);
        assertThat(statusOf(beforeAnyCompletion, b.id())).isEqualTo(ChapterStatus.LOCKED);
        assertThat(statusOf(beforeAnyCompletion, c.id())).isEqualTo(ChapterStatus.LOCKED);

        seriesService.completeChapter(seriesId, a.id(), user.getId());

        SeriesDetailDto afterFirstCompletion = seriesService.getSeriesDetail(seriesId, user.getId(), true);
        assertThat(statusOf(afterFirstCompletion, a.id())).isEqualTo(ChapterStatus.DONE);
        assertThat(statusOf(afterFirstCompletion, b.id())).isEqualTo(ChapterStatus.CURRENT);
        assertThat(statusOf(afterFirstCompletion, c.id())).isEqualTo(ChapterStatus.LOCKED);
    }

    /** LOCKED is display-only — completing out of order is allowed. */
    @Test
    void completeChapter_allowsCompletingALockedChapter() {
        UUID seriesId = persistSeries();
        seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto lockedChapter = seriesService.addChapter(seriesId, null, introInput());
        User user = persistUser();

        SeriesChapterDetailDto completed = seriesService.completeChapter(seriesId, lockedChapter.id(), user.getId());

        assertThat(completed.status()).isEqualTo(ChapterStatus.DONE);
    }

    @Test
    void completeChapter_isIdempotent() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        User user = persistUser();

        seriesService.completeChapter(seriesId, chapter.id(), user.getId());
        SeriesChapterDetailDto secondCompletion = seriesService.completeChapter(seriesId, chapter.id(), user.getId());

        assertThat(secondCompletion.status()).isEqualTo(ChapterStatus.DONE);
    }

    @Test
    void reorderChapters_rejectsMismatchedChapterIdSet() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto a = seriesService.addChapter(seriesId, null, introInput());
        seriesService.addChapter(seriesId, null, introInput());

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> seriesService.reorderChapters(seriesId, List.of(a.id(), UUID.randomUUID())));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Swapping two positions must survive uq_series_chapters_series_position (checked per-statement, not deferred). */
    @Test
    void reorderChapters_swapsPositionsWithoutViolatingUniqueConstraint() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto a = seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto b = seriesService.addChapter(seriesId, null, introInput());

        seriesService.reorderChapters(seriesId, List.of(b.id(), a.id()));

        List<SeriesChapter> reordered = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);
        assertThat(reordered).extracting(SeriesChapter::getId).containsExactly(b.id(), a.id());
        assertThat(reordered).extracting(SeriesChapter::getPosition).containsExactly(0, 1);
    }

    @Test
    void create_alwaysStartsAsADraft() {
        SeriesUpsertRequest request = new SeriesUpsertRequest("Draft By Default", null, null, SeriesVoice.MARK, null, null, null, null);

        UUID seriesId = seriesService.create(request).id();

        assertThat(seriesRepository.findById(seriesId).orElseThrow().isPublished()).isFalse();
    }

    @Test
    void setCoverImage_uploadsUnderTheSeriesOwnKeyAndPersistsTheReturnedUrl() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("series/" + seriesId + "/cover", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/cover.jpg");

        seriesService.setCoverImage(seriesId, file);

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);
        assertThat(detail.coverImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/cover.jpg");
    }

    @Test
    void setCoverImage_rejectsUnknownSeries() {
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setCoverImage(UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setPrincipalImage_uploadsUnderTheSeriesOwnKeyAndPersistsTheReturnedUrl() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "principal.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("series/" + seriesId + "/principal", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/principal.jpg");

        seriesService.setPrincipalImage(seriesId, file);

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);
        assertThat(detail.principalImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/principal.jpg");
    }

    @Test
    void setPrincipalImage_rejectsUnknownSeries() {
        MockMultipartFile file = new MockMultipartFile("file", "principal.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setPrincipalImage(UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setBannerImage_uploadsUnderTheSeriesOwnKeyAndPersistsTheReturnedUrl() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "banner.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("series/" + seriesId + "/banner", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/banner.jpg");

        seriesService.setBannerImage(seriesId, file);

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);
        assertThat(detail.bannerImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/banner.jpg");
    }

    @Test
    void setBannerImage_rejectsUnknownSeries() {
        MockMultipartFile file = new MockMultipartFile("file", "banner.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setBannerImage(UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFooterImage_uploadsUnderTheSeriesOwnKeyAndPersistsTheReturnedUrl() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "footer.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageStorageService.upload("series/" + seriesId + "/footer", file))
            .thenReturn("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/footer.jpg");

        seriesService.setFooterImage(seriesId, file);

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);
        assertThat(detail.footerImageUrl()).isEqualTo("http://localhost:9000/jazzlogs-images/series/" + seriesId + "/footer.jpg");
    }

    @Test
    void setFooterImage_rejectsUnknownSeries() {
        MockMultipartFile file = new MockMultipartFile("file", "footer.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setFooterImage(UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setChapterCoverImage_uploadsUnderTheChapterOwnKeyAndPersistsTheReturnedUrl() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());
        String expectedUrl = "http://localhost:9000/jazzlogs-images/series/" + seriesId + "/chapters/" + chapter.id() + "/cover.jpg";
        when(imageStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/cover", file)).thenReturn(expectedUrl);

        seriesService.setChapterCoverImage(seriesId, chapter.id(), file);

        SeriesDetailDto detail = seriesService.getSeriesDetail(seriesId, null, true);
        assertThat(detail.chapters().get(0).imageUrl()).isEqualTo(expectedUrl);
    }

    @Test
    void setChapterCoverImage_rejectsUnknownChapter() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setChapterCoverImage(seriesId, UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Distinct from setChapterCoverImage/imageUrl — its own column, its own key, never overwrites the other. */
    @Test
    void setChapterLandscapeImage_uploadsUnderItsOwnKeyAndPersistsTheReturnedUrl_separateFromCoverImage() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        MockMultipartFile coverFile = new MockMultipartFile("file", "cover.jpg", "image/jpeg", "cover-bytes".getBytes());
        MockMultipartFile landscapeFile = new MockMultipartFile("file", "landscape.jpg", "image/jpeg", "landscape-bytes".getBytes());
        String coverUrl = "http://localhost:9000/jazzlogs-images/series/" + seriesId + "/chapters/" + chapter.id() + "/cover.jpg";
        String landscapeUrl = "http://localhost:9000/jazzlogs-images/series/" + seriesId + "/chapters/" + chapter.id() + "/landscape-cover.jpg";
        when(imageStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/cover", coverFile)).thenReturn(coverUrl);
        when(imageStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/landscape-cover", landscapeFile)).thenReturn(landscapeUrl);

        seriesService.setChapterCoverImage(seriesId, chapter.id(), coverFile);
        seriesService.setChapterLandscapeImage(seriesId, chapter.id(), landscapeFile);

        SeriesChapterDetailDto reloaded = seriesService.getSeriesDetail(seriesId, null, true).chapters().get(0);
        assertThat(reloaded.imageUrl()).isEqualTo(coverUrl);
        assertThat(reloaded.landscapeImageUrl()).isEqualTo(landscapeUrl);
    }

    @Test
    void setChapterLandscapeImage_rejectsUnknownChapter() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "landscape.jpg", "image/jpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setChapterLandscapeImage(seriesId, UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** audioUrl/audioContentType/audioFileSizeBytes all come from the upload itself, never client-supplied. */
    @Test
    void setChapterAudio_uploadsUnderTheChapterOwnKeyAndPersistsObjectKeyContentTypeAndSize() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "fake-audio-bytes".getBytes());
        String expectedKey = "series/" + seriesId + "/chapters/" + chapter.id() + "/audio.mp3";
        when(audioStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/audio", file))
            .thenReturn(new AudioStorageService.UploadedAudio(expectedKey, "audio/mpeg", file.getSize()));

        seriesService.setChapterAudio(seriesId, chapter.id(), file);

        SeriesChapterDetailDto reloaded = seriesService.getSeriesDetail(seriesId, null, true).chapters().get(0);
        assertThat(reloaded.audioObjectKey()).isEqualTo(expectedKey);
        assertThat(reloaded.audioContentType()).isEqualTo("audio/mpeg");
        assertThat(reloaded.audioFileSizeBytes()).isEqualTo(file.getSize());
    }

    @Test
    void getChapter_returnsAPresignedAudioUrlForTheStoredKey() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "fake-audio-bytes".getBytes());
        String key = "series/" + seriesId + "/chapters/" + chapter.id() + "/audio.mp3";
        String presignedUrl = "http://localhost:9000/jazzlogs-audio/" + key + "?X-Amz-Signature=fake";
        when(audioStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/audio", file))
            .thenReturn(new AudioStorageService.UploadedAudio(key, "audio/mpeg", file.getSize()));
        seriesService.setChapterAudio(seriesId, chapter.id(), file);
        when(audioStorageService.presignPlaybackUrl(key)).thenReturn(presignedUrl);

        SeriesChapterDetailDto result = seriesService.getChapter(seriesId, chapter.id(), null, false);

        assertThat(result.audioUrl()).isEqualTo(presignedUrl);
    }

    @Test
    void getChapter_leavesAudioUrlNullWhenNoAudioUploadedYet() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());

        SeriesChapterDetailDto result = seriesService.getChapter(seriesId, chapter.id(), null, false);

        assertThat(result.audioUrl()).isNull();
    }

    @Test
    void getChapter_rejectsUnknownChapter() {
        UUID seriesId = persistSeries();

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.getChapter(seriesId, UUID.randomUUID(), null, false)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setChapterAudio_rejectsUnknownChapter() {
        UUID seriesId = persistSeries();
        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "fake-bytes".getBytes());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setChapterAudio(seriesId, UUID.randomUUID(), file)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getChapter_includesTheFullTrackWithRatingListenedAndTags() {
        UUID seriesId = persistSeries();
        Artist artist = persistArtist();
        Album album = persistAlbum(artist);
        Track track = persistTrack(album);
        SeriesChapterDetailDto chapter = seriesService.addChapter(
            seriesId, null, new SeriesChapterInput(ChapterType.TRACK, track.getId(), null, null, null)
        );
        User user = persistUser();
        trackRatingRepository.save(new TrackRating(user, track, new BigDecimal("4.5")));
        listenService.markTrackListened(user.getId(), track.getId());
        when(graphService.getTrackMoods(track.getId())).thenReturn(List.of(new VocabularyTag("MELLOW", "Mellow")));

        SeriesChapterTrackDto trackDto = seriesService.getChapter(seriesId, chapter.id(), user.getId(), false).track();

        assertThat(trackDto.id()).isEqualTo(track.getId());
        assertThat(trackDto.name()).isEqualTo("Test Track");
        assertThat(trackDto.albumId()).isEqualTo(album.getId());
        assertThat(trackDto.artistId()).isEqualTo(artist.getId());
        assertThat(trackDto.myRating()).isEqualByComparingTo("4.5");
        assertThat(trackDto.hasListened()).isTrue();
        assertThat(trackDto.moods()).containsExactly(new VocabularyTag("MELLOW", "Mellow"));
    }

    @Test
    void getChapter_leavesTrackNullForOutroChapters() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(
            seriesId, null, new SeriesChapterInput(ChapterType.OUTRO, null, "Wrap-up", "Thanks for listening", null)
        );

        SeriesChapterDetailDto result = seriesService.getChapter(seriesId, chapter.id(), null, false);

        assertThat(result.track()).isNull();
    }

    @Test
    void publish_marksTheSeriesPublished() {
        SeriesUpsertRequest request = new SeriesUpsertRequest("To Publish", null, null, SeriesVoice.MARK, null, null, null, null);
        UUID seriesId = seriesService.create(request).id();

        seriesService.publish(seriesId);

        assertThat(seriesRepository.findById(seriesId).orElseThrow().isPublished()).isTrue();
    }

    @Test
    void publish_rejectsUnknownSeries() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.publish(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unpublish_revertsToDraft() {
        UUID seriesId = persistSeries();

        seriesService.unpublish(seriesId);

        assertThat(seriesRepository.findById(seriesId).orElseThrow().isPublished()).isFalse();
    }

    @Test
    void unpublish_rejectsUnknownSeries() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.unpublish(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unpublish_clearsTheFeaturedFlagIfItWasTheFeaturedSeries() {
        UUID seriesId = persistSeries();
        seriesService.setFeatured(seriesId);

        seriesService.unpublish(seriesId);

        Series reloaded = seriesRepository.findById(seriesId).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
        assertThat(reloaded.isFeatured()).isFalse();
    }

    @Test
    void setFeatured_marksExactlyOneSeries_clearingWhicheverWasFeaturedBefore() {
        UUID seriesA = persistSeries();
        UUID seriesB = persistSeries();

        seriesService.setFeatured(seriesA);
        assertThat(seriesRepository.findByFeaturedTrue().map(Series::getId)).contains(seriesA);

        seriesService.setFeatured(seriesB);
        assertThat(seriesRepository.findByFeaturedTrue().map(Series::getId)).contains(seriesB);
    }

    @Test
    void setFeatured_rejectsUnknownSeries() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setFeatured(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void setFeatured_rejectsUnpublishedSeries() {
        UUID seriesId = persistSeries(false);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.setFeatured(seriesId));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seriesRepository.findByFeaturedTrue().map(Series::getId)).isNotEqualTo(Optional.of(seriesId));
    }

    @Test
    void unsetFeatured_isNoOpWhenTheSeriesWasNeverFeatured() {
        UUID seriesId = persistSeries();

        seriesService.unsetFeatured(seriesId);

        assertThat(seriesRepository.findById(seriesId).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_removesTheFeaturedFlag() {
        UUID seriesId = persistSeries();
        seriesService.setFeatured(seriesId);

        seriesService.unsetFeatured(seriesId);

        assertThat(seriesRepository.findById(seriesId).orElseThrow().isFeatured()).isFalse();
    }

    @Test
    void unsetFeatured_rejectsUnknownSeries() {
        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.unsetFeatured(UUID.randomUUID()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getFeatured_returnsTheFeaturedSeries() {
        UUID seriesId = persistSeries();
        seriesService.setFeatured(seriesId);

        Optional<FeaturedSeriesDto> featured = seriesService.getFeatured(false);

        assertThat(featured).isPresent();
        assertThat(featured.get().id()).isEqualTo(seriesId);
    }

    @Test
    void getFeatured_includesEachChaptersTitleAndNote() {
        UUID seriesId = persistSeries();
        seriesService.addChapter(seriesId, null, new SeriesChapterInput(ChapterType.OUTRO, null, "Wrap-up", "Thanks for listening", null));
        seriesService.setFeatured(seriesId);

        FeaturedSeriesDto featured = seriesService.getFeatured(false).orElseThrow();

        assertThat(featured.chapters()).hasSize(1);
        assertThat(featured.chapters().get(0).title()).isEqualTo("Wrap-up");
        assertThat(featured.chapters().get(0).note()).isEqualTo("Thanks for listening");
    }

    // The real "Let Me Show You Around" series already exists in the dev DB
    // (real content, not mock data) — these tests use it directly rather than
    // creating another row under the same title (uq_series_title, V39, would
    // reject that anyway). No "doesn't exist yet" test for the same reason
    // the Journey playlist tests don't assert on an empty table.

    @Test
    void getOnboardingSeries_returnsTheSeriesWithTheFixedTitle() {
        UUID id = onboardingSeriesId();
        seriesService.publish(id);

        SeriesSummaryDto onboarding = seriesService.getOnboardingSeries(false).orElseThrow();

        assertThat(onboarding.id()).isEqualTo(id);
    }

    @Test
    void getOnboardingSeries_hidesAnUnpublishedOneFromNonAdmins() {
        UUID id = onboardingSeriesId();
        seriesService.unpublish(id);

        assertThat(seriesService.getOnboardingSeries(false)).isEmpty();
        assertThat(seriesService.getOnboardingSeries(true)).isPresent();
    }

    @Test
    void getFeatured_hidesAnUnpublishedFeaturedSeriesFromNonAdmins() {
        UUID seriesId = persistSeries();
        seriesService.setFeatured(seriesId);
        seriesService.unpublish(seriesId);
        // unpublish clears featured too, so mark it featured again directly to
        // exercise getFeatured's own admin check in isolation.
        seriesRepository.markFeatured(seriesId);

        assertThat(seriesService.getFeatured(false)).isEmpty();
        assertThat(seriesService.getFeatured(true)).isPresent();
    }

    private ChapterStatus statusOf(SeriesDetailDto detail, UUID chapterId) {
        return detail.chapters().stream()
            .filter(chapter -> chapter.id().equals(chapterId))
            .findFirst()
            .orElseThrow()
            .status();
    }

    /** INTRO chapters need a trackId too now (only OUTRO doesn't) — gives each call its own fresh track. */
    private SeriesChapterInput introInput() {
        Track track = persistTrack(persistAlbum(persistArtist()));
        return new SeriesChapterInput(ChapterType.INTRO, track.getId(), null, null, null);
    }

    private UUID persistSeries() {
        return persistSeries(true);
    }

    private UUID persistSeries(boolean published) {
        return persistSeries("Test Series " + UUID.randomUUID(), SeriesVoice.MARK, published);
    }

    private UUID persistSeries(String title, SeriesVoice voice, boolean published) {
        SeriesUpsertRequest request = new SeriesUpsertRequest(title, null, null, voice, null, null, null, null);
        UUID id = seriesService.create(request).id();
        if (published) {
            seriesService.publish(id);
        }
        return id;
    }

    /**
     * Find-or-create: the local dev DB has a real, hand-curated "Let Me Show
     * You Around" series (uq_series_title blocks a second one), but CI's
     * fresh DB has none — creating it here makes these tests pass in both.
     */
    private UUID onboardingSeriesId() {
        return seriesRepository.findByTitle(SeriesService.ONBOARDING_SERIES_TITLE)
            .map(Series::getId)
            .orElseGet(() -> persistSeries(SeriesService.ONBOARDING_SERIES_TITLE, SeriesVoice.MARK, false));
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com"));
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, null, null
        ));
    }

    private Track persistTrack(Album album) {
        return trackRepository.save(new Track(
            album, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
    }
}
