package com.jazzlogs.backend.series;

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
    private SeriesChapterRepository seriesChapterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private TrackRepository trackRepository;

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
        SeriesChapterInput input = new SeriesChapterInput(ChapterType.TRACK, null, "Ep 1", null, null, null, null, null);

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> seriesService.addChapter(seriesId, null, input));
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addChapter_rejectsNonTrackTypeWithTrackId() {
        UUID seriesId = persistSeries();
        Track track = persistTrack(persistAlbum(persistArtist()));
        SeriesChapterInput input = new SeriesChapterInput(ChapterType.INTRO, track.getId(), null, null, null, null, null, null);

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

    private ChapterStatus statusOf(SeriesDetailDto detail, UUID chapterId) {
        return detail.chapters().stream()
            .filter(chapter -> chapter.id().equals(chapterId))
            .findFirst()
            .orElseThrow()
            .status();
    }

    private SeriesChapterInput introInput() {
        return new SeriesChapterInput(ChapterType.INTRO, null, null, null, null, null, null, null);
    }

    private UUID persistSeries() {
        SeriesUpsertRequest request = new SeriesUpsertRequest("Test Series", null, null, null, SeriesStatus.PUBLISHED);
        return seriesService.create(request).id();
    }

    private User persistUser() {
        return userRepository.save(new User(UUID.randomUUID(), "test-" + UUID.randomUUID() + "@example.com"));
    }

    private Artist persistArtist() {
        return artistRepository.save(new Artist("Test Artist", null, null, null));
    }

    private Album persistAlbum(Artist artist) {
        return albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, null,
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));
    }

    private Track persistTrack(Album album) {
        return trackRepository.save(new Track(
            album, null, null, "Test Track", null, null, null, false,
            null, null, null, null, null, null
        ));
    }
}
