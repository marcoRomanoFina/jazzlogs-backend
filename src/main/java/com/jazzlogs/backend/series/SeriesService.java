package com.jazzlogs.backend.series;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;

import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenRepository;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.listen.ListenableEntityType;
import com.jazzlogs.backend.series.dto.ChapterStatus;
import com.jazzlogs.backend.series.dto.FeaturedSeriesChapterDto;
import com.jazzlogs.backend.series.dto.FeaturedSeriesDto;
import com.jazzlogs.backend.series.dto.SeriesChapterDetailDto;
import com.jazzlogs.backend.series.dto.SeriesChapterInput;
import com.jazzlogs.backend.series.dto.SeriesDetailDto;
import com.jazzlogs.backend.series.dto.SeriesSummaryDto;
import com.jazzlogs.backend.series.dto.SeriesUpsertRequest;
import com.jazzlogs.backend.storage.AudioStorageService;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyCodes;

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
    private final ImageStorageService imageStorageService;
    private final AudioStorageService audioStorageService;
    private final GraphService graphService;
    private final EntityManager entityManager;

    /**
     * Metadata only, always starts as a draft with no cover — the chapter
     * list starts empty too, see addChapter/publish/setCoverImage.
     *
     * @throws ResponseStatusException 409 if another series already has this title
     */
    @Transactional
    public SeriesDetailDto create(SeriesUpsertRequest request) {
        assertTitleAvailable(request.title(), null);
        Series series = new Series(request.title(), request.dek(), request.description(), request.voice());
        Series saved = seriesRepository.save(series);
        flushOrThrowOnTitleConflict(request.title());
        graphService.syncSeriesNode(saved.getId(), saved.getTitle());
        replaceTags(saved, request.styleCodes(), request.moodCodes(), request.contextCodes(), request.instrumentCodes());
        return getSeriesDetail(saved.getId(), null, true);
    }

    /**
     * Metadata only — never touches series_chapters or status, see
     * addChapter/removeChapter/updateChapter/reorderChapters/publish/unpublish.
     *
     * @throws ResponseStatusException 404 if the series doesn't exist, 409
     *                                  if another series already has this title
     */
    @Transactional
    public SeriesDetailDto update(UUID id, SeriesUpsertRequest request) {
        Series series = getSeriesOrThrow(id);
        assertTitleAvailable(request.title(), id);
        series.update(request.title(), request.dek(), request.description(), request.voice());
        flushOrThrowOnTitleConflict(request.title());
        graphService.syncSeriesNode(series.getId(), series.getTitle());
        replaceTags(series, request.styleCodes(), request.moodCodes(), request.contextCodes(), request.instrumentCodes());
        return getSeriesDetail(id, null, true);
    }

    /**
     * Neo4j-only, same pattern as PlaylistService.replaceTags: validated
     * against the vocabulary enum before the graph call (400 on the first
     * invalid code), then a single synchronous write — if Neo4j is down this
     * throws GraphWriteException (502).
     */
    private void replaceTags(
        Series series, List<String> styleCodes, List<String> moodCodes, List<String> contextCodes, List<String> instrumentCodes
    ) {
        List<String> styles = styleCodes == null ? List.of() : styleCodes;
        List<String> moods = moodCodes == null ? List.of() : moodCodes;
        List<String> contexts = contextCodes == null ? List.of() : contextCodes;
        List<String> instruments = instrumentCodes == null ? List.of() : instrumentCodes;

        styles.forEach(code -> VocabularyCodes.validate(StyleVocabulary.class, code, "style"));
        moods.forEach(code -> VocabularyCodes.validate(MoodVocabulary.class, code, "mood"));
        contexts.forEach(code -> VocabularyCodes.validate(ContextVocabulary.class, code, "context"));
        instruments.forEach(code -> VocabularyCodes.validate(InstrumentVocabulary.class, code, "instrument"));

        // Skip the round trip entirely when there's nothing to set — same
        // known gap as PlaylistService.replaceTags: an update sending
        // all-empty tag lists on an already-tagged series won't clear the
        // stale Neo4j edges.
        if (styles.isEmpty() && moods.isEmpty() && contexts.isEmpty() && instruments.isEmpty()) {
            return;
        }

        graphService.setSeriesTags(series.getId(), styles, moods, contexts, instruments);
    }

    /**
     * Up-front check — the common-path way create/update reject a duplicate
     * title, with a clean message. {@code excludingSeriesId} lets update
     * re-save a series under its own unchanged title without tripping over
     * itself; pass {@code null} from create, where nothing should be excluded.
     *
     * @throws ResponseStatusException 409 if a different series already has this title
     */
    private void assertTitleAvailable(String title, UUID excludingSeriesId) {
        seriesRepository.findByTitle(title)
            .filter(existing -> !existing.getId().equals(excludingSeriesId))
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A series titled \"" + title + "\" already exists");
            });
    }

    /**
     * {@code flush()} forces the pending INSERT/UPDATE to run right here
     * instead of at commit time — same reasoning as PlaylistService's own
     * version: without it, a title collision from a concurrent request
     * (that snuck in between {@link #assertTitleAvailable} and this point)
     * would blow up much later, at commit, well outside this try/catch,
     * instead of surfacing here as a clean 409. uq_series_title (V39) is
     * what actually guarantees no two series share a title under concurrent
     * writes; this is just what turns that into a 409 instead of a 500.
     */
    private void flushOrThrowOnTitleConflict(String title) {
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException | ConstraintViolationException concurrentTitle) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "A series titled \"" + title + "\" already exists", concurrentTitle
            );
        }
    }

    /**
     * Uploads a new cover image for this series — see {@link
     * ImageStorageService#upload}. One object per series ({@code
     * series/{id}/cover.<ext>}), so re-uploading overwrites the old cover
     * instead of leaving it orphaned in storage.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setCoverImage(UUID id, MultipartFile file) {
        Series series = getSeriesOrThrow(id);
        String url = imageStorageService.upload("series/" + id + "/cover", file);
        series.updateCoverImageUrl(url);
    }

    /**
     * Uploads the principal/hero image for this series' detail page — a
     * fourth, distinct image from cover/banner/footer. One object per
     * series ({@code series/{id}/principal.<ext>}), so re-uploading
     * overwrites the old one instead of leaving it orphaned in storage.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setPrincipalImage(UUID id, MultipartFile file) {
        Series series = getSeriesOrThrow(id);
        String url = imageStorageService.upload("series/" + id + "/principal", file);
        series.updatePrincipalImageUrl(url);
    }

    /**
     * Uploads the banner image for this series' detail page — see {@link
     * #setPrincipalImage}. One object per series ({@code series/{id}/banner.<ext>}).
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setBannerImage(UUID id, MultipartFile file) {
        Series series = getSeriesOrThrow(id);
        String url = imageStorageService.upload("series/" + id + "/banner", file);
        series.updateBannerImageUrl(url);
    }

    /**
     * Uploads the footer image for this series' detail page — see {@link
     * #setPrincipalImage}. One object per series ({@code series/{id}/footer.<ext>}).
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setFooterImage(UUID id, MultipartFile file) {
        Series series = getSeriesOrThrow(id);
        String url = imageStorageService.upload("series/" + id + "/footer", file);
        series.updateFooterImageUrl(url);
    }

    /**
     * Uploads a cover image for one chapter — see {@link
     * ImageStorageService#upload}. One object per chapter ({@code
     * series/{seriesId}/chapters/{chapterId}/cover.<ext>}), so re-uploading
     * overwrites the old one instead of leaving it orphaned in storage.
     *
     * @param seriesId  the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setChapterCoverImage(UUID seriesId, UUID chapterId, MultipartFile file) {
        getSeriesOrThrow(seriesId);
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        String url = imageStorageService.upload("series/" + seriesId + "/chapters/" + chapterId + "/cover", file);
        chapter.updateImageUrl(url);
    }

    /**
     * Uploads the landscape/hero image for one chapter — a second, distinct
     * image from {@link #setChapterCoverImage}'s, not a size variant of it.
     * One object per chapter ({@code series/{seriesId}/chapters/{chapterId}/landscape-cover.<ext>}),
     * so re-uploading overwrites the old one instead of leaving it orphaned in storage.
     *
     * @param seriesId  the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the image file (jpeg/png/webp only)
     */
    @Transactional
    public void setChapterLandscapeImage(UUID seriesId, UUID chapterId, MultipartFile file) {
        getSeriesOrThrow(seriesId);
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        String url = imageStorageService.upload("series/" + seriesId + "/chapters/" + chapterId + "/landscape-cover", file);
        chapter.updateLandscapeImageUrl(url);
    }

    /**
     * Uploads the audio for one chapter — see {@link AudioStorageService#upload}.
     * One object per chapter ({@code series/{seriesId}/chapters/{chapterId}/audio.<ext>}),
     * so re-uploading overwrites the old one instead of leaving it orphaned
     * in storage. audioObjectKey/audioContentType/audioFileSizeBytes all
     * come from the upload itself, never from client-supplied strings —
     * audioDurationSeconds is the one piece still set separately, via
     * addChapter/updateChapter, since nothing here decodes audio to derive it.
     *
     * @param seriesId  the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the audio file (mp3/m4a/wav only)
     */
    @Transactional
    public void setChapterAudio(UUID seriesId, UUID chapterId, MultipartFile file) {
        getSeriesOrThrow(seriesId);
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        AudioStorageService.UploadedAudio uploaded = audioStorageService.upload(
            "series/" + seriesId + "/chapters/" + chapterId + "/audio", file
        );
        chapter.updateAudio(uploaded.objectKey(), uploaded.contentType(), uploaded.fileSizeBytes());
    }

    /**
     * A short-lived URL to actually play this chapter's audio — the
     * bucket is private, so {@code audioObjectKey} alone isn't fetchable by
     * a client; this is the only way to turn it into something playable.
     * Generated fresh on every call, not cached or stored — see {@link
     * AudioStorageService#presignPlaybackUrl}. Same visibility rule as the
     * rest of the series (draft series/chapters are admin-only); this is
     * also the choke point a future subscription check would go through,
     * since a signed URL is the last gate before the audio bytes themselves
     * leave storage.
     *
     * @param seriesId      the series
     * @param chapterId     the chapter, must belong to this series
     * @param currentUserId unused today, kept for a future entitlement check
     * @param isAdmin       admins can play draft chapters too
     * @return a presigned URL valid for a limited time
     * @throws ResponseStatusException 404 if the series/chapter doesn't
     *                                  exist (or isn't visible to this
     *                                  caller), or if this chapter has no
     *                                  audio uploaded yet
     */
    @Transactional(readOnly = true)
    public String getChapterAudioUrl(UUID seriesId, UUID chapterId, UUID currentUserId, boolean isAdmin) {
        Series series = getSeriesOrThrow(seriesId);
        if (!series.isPublished() && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found: " + seriesId);
        }
        SeriesChapter chapter = getChapterOrThrow(seriesId, chapterId);
        if (chapter.getAudioObjectKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter has no audio yet: " + chapterId);
        }
        return audioStorageService.presignPlaybackUrl(chapter.getAudioObjectKey());
    }

    /** Publishes this series, making it visible to non-admins. */
    @Transactional
    public void publish(UUID seriesId) {
        getSeriesOrThrow(seriesId).publish();
    }

    /**
     * Reverts this series to draft — a no-op if it was already a draft.
     * Also clears the featured flag if this was THE featured series:
     * setFeatured requires published (see {@link #setFeatured}), so
     * unpublishing keeps that invariant true going the other way too,
     * instead of leaving a featured-but-unpublished row that only
     * {@link #getFeatured}'s admin check papers over.
     */
    @Transactional
    public void unpublish(UUID seriesId) {
        getSeriesOrThrow(seriesId).unpublish();
        seriesRepository.unmarkFeatured(seriesId);
    }

    /**
     * Marks this series as THE featured one, unfeaturing whichever one (if
     * any) held that spot before. {@code idx_series_only_one_featured}
     * (see V38) is what actually guarantees at most one stays featured
     * under concurrent calls.
     *
     * @param seriesId the series
     * @throws ResponseStatusException 404 if the series doesn't exist, 409
     *                                  if it isn't published yet (a featured
     *                                  series non-admins can't see would be
     *                                  a broken link) or if a concurrent call
     *                                  already featured a different series
     */
    @Transactional
    public void setFeatured(UUID seriesId) {
        Series series = getSeriesOrThrow(seriesId);
        if (!series.isPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Series isn't published yet, can't be featured");
        }
        seriesRepository.clearFeatured();
        try {
            seriesRepository.markFeatured(seriesId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Another series was just featured concurrently — try again", e
            );
        }
    }

    /** Removes this series from being THE featured one — a no-op if it wasn't. */
    @Transactional
    public void unsetFeatured(UUID seriesId) {
        getSeriesOrThrow(seriesId);
        seriesRepository.unmarkFeatured(seriesId);
    }

    /**
     * The singleton featured series — same "at most one" pattern as {@code
     * Playlist#featured}/{@code Album#featured}. Unlike the rest of the
     * playlist/series "featured" endpoints, this one does include a
     * chapter list — just title/note per chapter, not the full {@link
     * SeriesChapterDetailDto} fan-out.
     *
     * @return the featured series, empty if none is featured, or if the
     *         featured one isn't published and the caller isn't an admin
     */
    @Transactional(readOnly = true)
    public Optional<FeaturedSeriesDto> getFeatured(boolean isAdmin) {
        return seriesRepository.findByFeaturedTrue()
            .filter(series -> series.isPublished() || isAdmin)
            .map(this::toFeaturedDto);
    }

    /** The fixed, curated title {@code getOnboardingSeries} looks up — not a slug/id, since this series is created and named by hand, once. */
    static final String ONBOARDING_SERIES_TITLE = "Let Me Show You Around";

    /**
     * The app's onboarding/tour series — looked up by its fixed title rather
     * than an id, since the frontend has no other stable way to reference
     * "whichever series is the onboarding one". No chapter list, same as
     * {@code SeriesSummaryDto} everywhere else.
     *
     * @return the onboarding series, empty if it doesn't exist yet, or if
     *         it isn't published and the caller isn't an admin
     */
    @Transactional(readOnly = true)
    public Optional<SeriesSummaryDto> getOnboardingSeries(boolean isAdmin) {
        return seriesRepository.findByTitle(ONBOARDING_SERIES_TITLE)
            .filter(series -> series.isPublished() || isAdmin)
            .map(series -> toSummaryDtos(List.of(series)).get(0));
    }

    private FeaturedSeriesDto toFeaturedDto(Series series) {
        List<FeaturedSeriesChapterDto> chapters = seriesChapterRepository.findBySeriesIdOrderByPosition(series.getId()).stream()
            .map(chapter -> new FeaturedSeriesChapterDto(chapter.getTitle(), chapter.getNote()))
            .toList();
        List<VocabularyTag> styleTags = graphService.getSeriesStyles(series.getId());
        List<VocabularyTag> moodTags = graphService.getSeriesMoods(series.getId());
        List<VocabularyTag> contextTags = graphService.getSeriesContexts(series.getId());
        List<VocabularyTag> featuredInstruments = graphService.getSeriesFeaturedInstruments(series.getId());
        return new FeaturedSeriesDto(
            series.getId(), series.getTitle(), series.getDek(), series.getCoverImageUrl(),
            series.getStatus(), series.getVoice(), series.getLikeCount(), chapters,
            styleTags, moodTags, contextTags, featuredInstruments, series.getCreatedAt()
        );
    }

    /** Appends a chapter at the end (position = current chapter count). No Neo4j — Series stays out of the graph. */
    @Transactional
    public SeriesChapterDetailDto addChapter(UUID seriesId, UUID userId, SeriesChapterInput input) {
        Series series = getSeriesOrThrow(seriesId);
        Track track = resolveTrackForType(input.type(), input.trackId());

        int position = (int) seriesChapterRepository.countBySeriesId(seriesId);
        SeriesChapter chapter = seriesChapterRepository.save(new SeriesChapter(
            series, position, input.type(), track, input.title(), input.note(), input.audioDurationSeconds()
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

        chapter.updateDetails(input.type(), track, input.title(), input.note(), input.audioDurationSeconds());
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

    /**
     * No gating — LOCKED is display-only (see {@link #computeStatuses}), a
     * user can complete chapters in any order. Idempotent if already done.
     */
    @Transactional
    public SeriesChapterDetailDto completeChapter(UUID seriesId, UUID chapterId, UUID userId) {
        getSeriesOrThrow(seriesId);
        getChapterOrThrow(seriesId, chapterId);

        listenService.markSeriesChapterListened(userId, chapterId);

        return toChapterDto(seriesId, userId, chapterId);
    }

    @Transactional(readOnly = true)
    public Page<SeriesSummaryDto> list(boolean includeUnpublished, Pageable pageable) {
        Page<Series> page = includeUnpublished
            ? seriesRepository.findAll(pageable)
            : seriesRepository.findByStatus(SeriesStatus.PUBLISHED, pageable);
        return toSummaryPage(page);
    }

    /**
     * The series "Catalogue" — {@code GET /series/catalogue} — one page,
     * newest first, optionally narrowed to a single {@code voice}. One
     * endpoint handles both the filtered and unfiltered case (unlike
     * Playlist's separate {@code /journeys}/{@code /standard}/{@code
     * /catalogue}) since series only has this one filter dimension so far.
     *
     * @param voice              narrow to this voice only, or {@code null} for every voice
     * @param includeUnpublished true for admins (drafts included), false otherwise
     * @param pageable           page/size/sort — callers default to createdAt desc
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<SeriesSummaryDto> getCatalogue(SeriesVoice voice, boolean includeUnpublished, Pageable pageable) {
        Page<Series> page;
        if (voice == null) {
            page = includeUnpublished ? seriesRepository.findAll(pageable) : seriesRepository.findByStatus(SeriesStatus.PUBLISHED, pageable);
        } else {
            page = includeUnpublished
                ? seriesRepository.findByVoice(voice, pageable)
                : seriesRepository.findByVoiceAndStatus(voice, SeriesStatus.PUBLISHED, pageable);
        }
        return toSummaryPage(page);
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

        List<VocabularyTag> styleTags = graphService.getSeriesStyles(seriesId);
        List<VocabularyTag> moodTags = graphService.getSeriesMoods(seriesId);
        List<VocabularyTag> contextTags = graphService.getSeriesContexts(seriesId);
        List<VocabularyTag> featuredInstruments = graphService.getSeriesFeaturedInstruments(seriesId);

        return new SeriesDetailDto(
            series.getId(), series.getTitle(), series.getDek(), series.getDescription(), series.getCoverImageUrl(),
            series.getPrincipalImageUrl(), series.getBannerImageUrl(), series.getFooterImageUrl(),
            series.getStatus(), series.getVoice(), series.getLikeCount(), liked, totalListenings, chapterDtos,
            styleTags, moodTags, contextTags, featuredInstruments, series.getCreatedAt(), series.getUpdatedAt()
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

    /** trackId is required for INTRO/TRACK chapters, forbidden for OUTRO ones. */
    private Track resolveTrackForType(ChapterType type, UUID trackId) {
        if (type == ChapterType.OUTRO) {
            if (trackId != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trackId must be null for OUTRO chapters");
            }
            return null;
        }
        if (trackId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trackId is required for " + type + " chapters");
        }
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }

    private SeriesChapterDetailDto toChapterDetailDto(SeriesChapter chapter, ChapterStatus status) {
        Track track = chapter.getTrack();
        return new SeriesChapterDetailDto(
            chapter.getId(), chapter.getPosition(), chapter.getType(),
            track == null ? null : track.getId(), track == null ? null : track.getName(),
            chapter.getTitle(), chapter.getNote(),
            chapter.getAudioObjectKey(), chapter.getAudioDurationSeconds(), chapter.getAudioContentType(), chapter.getAudioFileSizeBytes(),
            chapter.getImageUrl(), chapter.getLandscapeImageUrl(), status
        );
    }

    private Page<SeriesSummaryDto> toSummaryPage(Page<Series> page) {
        return new PageImpl<>(toSummaryDtos(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    /** Shared by list/getCatalogue — one batch query per tag type for the whole page, not one per series. */
    private List<SeriesSummaryDto> toSummaryDtos(List<Series> seriesList) {
        List<UUID> seriesIds = seriesList.stream().map(Series::getId).toList();
        Map<UUID, List<VocabularyTag>> styleTagsBySeries = graphService.getSeriesStylesBatch(seriesIds);
        Map<UUID, List<VocabularyTag>> moodTagsBySeries = graphService.getSeriesMoodsBatch(seriesIds);
        Map<UUID, List<VocabularyTag>> contextTagsBySeries = graphService.getSeriesContextsBatch(seriesIds);
        Map<UUID, List<VocabularyTag>> instrumentsBySeries = graphService.getSeriesFeaturedInstrumentsBatch(seriesIds);

        return seriesList.stream()
            .map(series -> new SeriesSummaryDto(
                series.getId(), series.getTitle(), series.getDek(), series.getCoverImageUrl(),
                series.getStatus(), series.getVoice(), series.getLikeCount(),
                styleTagsBySeries.getOrDefault(series.getId(), List.of()),
                moodTagsBySeries.getOrDefault(series.getId(), List.of()),
                contextTagsBySeries.getOrDefault(series.getId(), List.of()),
                instrumentsBySeries.getOrDefault(series.getId(), List.of()),
                series.getCreatedAt()
            ))
            .toList();
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
