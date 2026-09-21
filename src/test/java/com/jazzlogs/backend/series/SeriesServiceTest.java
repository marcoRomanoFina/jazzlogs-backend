package com.jazzlogs.backend.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import java.util.List;
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
import com.jazzlogs.backend.series.dto.ChapterStatus;
import com.jazzlogs.backend.series.dto.SeriesChapterDetailDto;
import com.jazzlogs.backend.series.dto.SeriesChapterInput;
import com.jazzlogs.backend.series.dto.SeriesDetailDto;
import com.jazzlogs.backend.series.dto.SeriesUpsertRequest;
import com.jazzlogs.backend.storage.AudioStorageService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
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

    @MockitoBean
    private ImageStorageService imageStorageService;

    @MockitoBean
    private AudioStorageService audioStorageService;

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

    @Test
    void completeChapter_rejectsLockedChapter() {
        UUID seriesId = persistSeries();
        seriesService.addChapter(seriesId, null, introInput());
        SeriesChapterDetailDto lockedChapter = seriesService.addChapter(seriesId, null, introInput());
        User user = persistUser();

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
            () -> seriesService.completeChapter(seriesId, lockedChapter.id(), user.getId()));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
        SeriesUpsertRequest request = new SeriesUpsertRequest("Draft By Default", null, null, SeriesVoice.MARK);

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
    void getChapterAudioUrl_returnsAPresignedUrlForTheStoredKey() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());
        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "fake-audio-bytes".getBytes());
        String key = "series/" + seriesId + "/chapters/" + chapter.id() + "/audio.mp3";
        String presignedUrl = "http://localhost:9000/jazzlogs-audio/" + key + "?X-Amz-Signature=fake";
        when(audioStorageService.upload("series/" + seriesId + "/chapters/" + chapter.id() + "/audio", file))
            .thenReturn(new AudioStorageService.UploadedAudio(key, "audio/mpeg", file.getSize()));
        seriesService.setChapterAudio(seriesId, chapter.id(), file);
        when(audioStorageService.presignPlaybackUrl(key)).thenReturn(presignedUrl);

        String url = seriesService.getChapterAudioUrl(seriesId, chapter.id(), null, false);

        assertThat(url).isEqualTo(presignedUrl);
    }

    @Test
    void getChapterAudioUrl_rejectsAChapterWithNoAudioUploadedYet() {
        UUID seriesId = persistSeries();
        SeriesChapterDetailDto chapter = seriesService.addChapter(seriesId, null, introInput());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.getChapterAudioUrl(seriesId, chapter.id(), null, false)
        );

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getChapterAudioUrl_rejectsUnknownChapter() {
        UUID seriesId = persistSeries();

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.getChapterAudioUrl(seriesId, UUID.randomUUID(), null, false)
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
    void publish_marksTheSeriesPublished() {
        SeriesUpsertRequest request = new SeriesUpsertRequest("To Publish", null, null, SeriesVoice.MARK);
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
        SeriesUpsertRequest request = new SeriesUpsertRequest("Test Series", null, null, SeriesVoice.MARK);
        UUID id = seriesService.create(request).id();
        seriesService.publish(id);
        return id;
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com"));
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

    private Track persistTrack(Album album) {
        return trackRepository.save(new Track(
            album, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
    }
}
