package com.jazzlogs.backend.series;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenRepository;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.listen.ListenableEntityType;
import com.jazzlogs.backend.series.dto.ChapterStatus;
import com.jazzlogs.backend.series.dto.SeriesChapterDetailDto;
import com.jazzlogs.backend.series.dto.SeriesChapterInput;
import com.jazzlogs.backend.series.dto.SeriesDetailDto;
import com.jazzlogs.backend.series.dto.SeriesSummaryDto;
import com.jazzlogs.backend.series.dto.SeriesUpsertRequest;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class SeriesService {

    private final SeriesRepository seriesRepository;
    private final SeriesChapterRepository seriesChapterRepository;
    private final TrackRepository trackRepository;
    private final LikeService likeService;
    private final ListenService listenService;
    private final ListenRepository listenRepository;

    /** Metadata only — the chapter list starts empty, see addChapter. */
    @Transactional
    public SeriesDetailDto create(SeriesUpsertRequest request) {
        Series series = new Series(request.title(), request.dek(), request.description(), request.coverImageUrl(), request.status());
        Series saved = seriesRepository.save(series);
        return getSeriesDetail(saved.getId(), null, true);
    }

    /** Metadata only — never touches series_chapters, see addChapter/removeChapter/updateChapter/reorderChapters. */
    @Transactional
    public SeriesDetailDto update(UUID id, SeriesUpsertRequest request) {
        Series series = getSeriesOrThrow(id);
        series.update(request.title(), request.dek(), request.description(), request.coverImageUrl(), request.status());
        return getSeriesDetail(id, null, true);
    }

    /** Appends a chapter at the end (position = current chapter count). No Neo4j — Series stays out of the graph. */
    @Transactional
    public SeriesChapterDetailDto addChapter(UUID seriesId, UUID userId, SeriesChapterInput input) {
        Series series = getSeriesOrThrow(seriesId);
        Track track = resolveTrackForType(input.type(), input.trackId());

        int position = (int) seriesChapterRepository.countBySeriesId(seriesId);
        SeriesChapter chapter = seriesChapterRepository.save(new SeriesChapter(
            series, position, input.type(), track, input.title(), input.note(),
            input.audioObjectKey(), input.audioDurationMs(), input.audioContentType(), input.audioFileSizeBytes()
        ));

        return toChapterDto(seriesId, userId, chapter.getId());
    }

    /** 404 if the chapter isn't part of this series. Doesn't renumber the remaining rows — only reorderChapters re-sequences. */
    @Transactional
    public void removeChapter(UUID seriesId, UUID chapterId) {
        getSeriesOrThrow(seriesId);
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        seriesChapterRepository.delete(chapter);
    }

    /** No Neo4j counterpart for any chapter field — Postgres-only, no sync call at all. */
    @Transactional
    public SeriesChapterDetailDto updateChapter(UUID seriesId, UUID chapterId, UUID userId, SeriesChapterInput input) {
        getSeriesOrThrow(seriesId);
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        Track track = resolveTrackForType(input.type(), input.trackId());

        chapter.updateDetails(
            input.type(), track, input.title(), input.note(),
            input.audioObjectKey(), input.audioDurationMs(), input.audioContentType(), input.audioFileSizeBytes()
        );
        seriesChapterRepository.save(chapter);

        return toChapterDto(seriesId, userId, chapterId);
    }

    /**
     * chapterIds must be exactly the chapters already in the series — same
     * set, no more, no less, no duplicates (400 otherwise).
     *
     * Two-phase update: uq_series_chapters_series_position is checked
     * per-statement (not deferred), so swapping two positions directly (e.g.
     * 0<->1) can transiently collide mid-flush even though the end state is
     * valid — bump everything to negative placeholder positions first, flush,
     * then set the real ones, so no intermediate state ever repeats a position.
     */
    @Transactional
    public void reorderChapters(UUID seriesId, List<UUID> orderedChapterIds) {
        getSeriesOrThrow(seriesId);
        List<SeriesChapter> existing = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);

        Set<UUID> existingIds = existing.stream().map(SeriesChapter::getId).collect(Collectors.toSet());
        Set<UUID> requestedIds = new HashSet<>(orderedChapterIds);
        boolean sameSet = existingIds.equals(requestedIds);
        boolean noDuplicates = requestedIds.size() == orderedChapterIds.size();
        if (!sameSet || !noDuplicates) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "chapterIds must contain exactly the chapters already in the series, no duplicates");
        }

        Map<UUID, SeriesChapter> byId = existing.stream()
            .collect(Collectors.toMap(SeriesChapter::getId, chapter -> chapter));

        for (int i = 0; i < orderedChapterIds.size(); i++) {
            byId.get(orderedChapterIds.get(i)).updatePosition(-(i + 1));
        }
        seriesChapterRepository.saveAll(byId.values());
        seriesChapterRepository.flush();

        for (int position = 0; position < orderedChapterIds.size(); position++) {
            byId.get(orderedChapterIds.get(position)).updatePosition(position);
        }
        seriesChapterRepository.saveAll(byId.values());
    }

    /** 403 if the chapter is LOCKED for this user; idempotent on CURRENT/DONE. */
    @Transactional
    public SeriesChapterDetailDto completeChapter(UUID seriesId, UUID chapterId, UUID userId) {
        getSeriesOrThrow(seriesId);
        List<SeriesChapter> chapters = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);
        if (chapters.stream().noneMatch(chapter -> chapter.getId().equals(chapterId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found: " + chapterId);
        }

        Map<UUID, ChapterStatus> statuses = computeStatuses(chapters, listenedChapterIds(userId, chapters));
        if (statuses.get(chapterId) == ChapterStatus.LOCKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chapter is locked: " + chapterId);
        }

        listenService.markSeriesChapterListened(userId, chapterId);

        return toChapterDto(seriesId, userId, chapterId);
    }

    @Transactional(readOnly = true)
    public Page<SeriesSummaryDto> list(boolean includeUnpublished, Pageable pageable) {
        Page<Series> page = includeUnpublished
            ? seriesRepository.findAll(pageable)
            : seriesRepository.findByStatus(SeriesStatus.PUBLISHED, pageable);
        return page.map(this::toSummaryDto);
    }

    /**
     * totalListenings is computed on-demand across every chapter's listens
     * (all users), never denormalized — same criterio as Album's avg rating.
     * Chapter status (DONE/CURRENT/LOCKED) is computed per request for the
     * given user, never persisted.
     */
    @Transactional(readOnly = true)
    public SeriesDetailDto getSeriesDetail(UUID seriesId, UUID userId, boolean isAdmin) {
        Series series = getSeriesOrThrow(seriesId);
        if (!series.isPublished() && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found: " + seriesId);
        }

        List<SeriesChapter> chapters = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);
        Map<UUID, ChapterStatus> statuses = computeStatuses(chapters, listenedChapterIds(userId, chapters));

        List<SeriesChapterDetailDto> chapterDtos = chapters.stream()
            .map(chapter -> toChapterDetailDto(chapter, statuses.get(chapter.getId())))
            .toList();

        long totalListenings = chapters.isEmpty()
            ? 0
            : listenRepository.countByEntityTypeAndEntityIdIn(
                ListenableEntityType.SERIES_CHAPTER, chapters.stream().map(SeriesChapter::getId).toList());

        boolean liked = userId != null && likeService.hasUserLiked(userId, LikeableEntityType.SERIES, seriesId);

        return new SeriesDetailDto(
            series.getId(), series.getTitle(), series.getDek(), series.getDescription(), series.getCoverImageUrl(),
            series.getStatus(), series.getLikeCount(), liked, totalListenings, chapterDtos,
            series.getCreatedAt(), series.getUpdatedAt()
        );
    }

    private SeriesChapterDetailDto toChapterDto(UUID seriesId, UUID userId, UUID chapterId) {
        List<SeriesChapter> chapters = seriesChapterRepository.findBySeriesIdOrderByPosition(seriesId);
        Map<UUID, ChapterStatus> statuses = computeStatuses(chapters, listenedChapterIds(userId, chapters));
        SeriesChapter chapter = chapters.stream()
            .filter(c -> c.getId().equals(chapterId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Chapter vanished mid-request: " + chapterId));
        return toChapterDetailDto(chapter, statuses.get(chapterId));
    }

    /**
     * DONE always wins (a listen is a fact regardless of position). Otherwise
     * the first not-done chapter with every earlier chapter done is CURRENT;
     * everything after that is LOCKED. Position 0 is vacuously "every earlier
     * chapter done" (there are none), so it's always DONE or CURRENT.
     */
    private Map<UUID, ChapterStatus> computeStatuses(List<SeriesChapter> orderedChapters, Set<UUID> listenedChapterIds) {
        Map<UUID, ChapterStatus> statuses = new LinkedHashMap<>();
        boolean previousDone = true;
        for (SeriesChapter chapter : orderedChapters) {
            boolean done = listenedChapterIds.contains(chapter.getId());
            ChapterStatus status = done ? ChapterStatus.DONE : (previousDone ? ChapterStatus.CURRENT : ChapterStatus.LOCKED);
            statuses.put(chapter.getId(), status);
            previousDone = previousDone && done;
        }
        return statuses;
    }

    private Set<UUID> listenedChapterIds(UUID userId, List<SeriesChapter> chapters) {
        if (userId == null || chapters.isEmpty()) {
            return Set.of();
        }
        List<UUID> chapterIds = chapters.stream().map(SeriesChapter::getId).toList();
        return new HashSet<>(listenRepository.findListenedEntityIds(userId, ListenableEntityType.SERIES_CHAPTER, chapterIds));
    }

    /** Ahead of the DB's CHECK constraint on purpose — same rule, a clear 400 beats a raw constraint-violation error. */
    private Track resolveTrackForType(ChapterType type, UUID trackId) {
        if (type == ChapterType.TRACK) {
            if (trackId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trackId is required for TRACK chapters");
            }
            return trackRepository.findById(trackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
        }
        if (trackId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trackId must be null for " + type + " chapters");
        }
        return null;
    }

    private SeriesChapterDetailDto toChapterDetailDto(SeriesChapter chapter, ChapterStatus status) {
        Track track = chapter.getTrack();
        return new SeriesChapterDetailDto(
            chapter.getId(), chapter.getPosition(), chapter.getType(),
            track == null ? null : track.getId(), track == null ? null : track.getName(),
            chapter.getTitle(), chapter.getNote(),
            chapter.getAudioObjectKey(), chapter.getAudioDurationMs(), chapter.getAudioContentType(), chapter.getAudioFileSizeBytes(),
            status
        );
    }

    private SeriesSummaryDto toSummaryDto(Series series) {
        return new SeriesSummaryDto(
            series.getId(), series.getTitle(), series.getDek(), series.getCoverImageUrl(),
            series.getStatus(), series.getLikeCount(), series.getCreatedAt()
        );
    }

    private Series getSeriesOrThrow(UUID id) {
        return seriesRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found: " + id));
    }

    private SeriesChapter getChapterOrThrow(UUID seriesId, UUID chapterId) {
        SeriesChapter chapter = seriesChapterRepository.findById(chapterId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found: " + chapterId));
        if (!chapter.getSeries().getId().equals(seriesId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found: " + chapterId);
        }
        return chapter;
    }
}
