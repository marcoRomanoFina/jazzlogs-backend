package com.jazzlogs.backend.series;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.series.dto.ChapterAudioUrlDto;
import com.jazzlogs.backend.series.dto.FeaturedSeriesDto;
import com.jazzlogs.backend.series.dto.ReorderSeriesChaptersRequest;
import com.jazzlogs.backend.series.dto.SeriesChapterDetailDto;
import com.jazzlogs.backend.series.dto.SeriesChapterInput;
import com.jazzlogs.backend.series.dto.SeriesDetailDto;
import com.jazzlogs.backend.series.dto.SeriesSummaryDto;
import com.jazzlogs.backend.series.dto.SeriesUpsertRequest;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRole;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

// Like/unlike SERIES reuses the already-generic /likes endpoints (LikeService
// now has SeriesRepository wired into its map) — no like/unlike endpoints here.
@RestController
@RequestMapping("/series")
@AllArgsConstructor
public class SeriesController {

    private final SeriesService seriesService;
    private final UserService userService;

    // Non-admins only see published series; admins see everything.
    @GetMapping
    public Page<SeriesSummaryDto> list(@PageableDefault Pageable pageable, @AuthenticationPrincipal Jwt jwt) {
        return seriesService.list(isAdmin(jwt), pageable);
    }

    @GetMapping("/{id}")
    public SeriesDetailDto getDetail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return seriesService.getSeriesDetail(id, user.getId(), user.getRole() == UserRole.ADMIN);
    }

    /** The featured series — see {@link SeriesService#getFeatured}. */
    @GetMapping("/featured")
    public FeaturedSeriesDto getFeatured(@AuthenticationPrincipal Jwt jwt) {
        return seriesService.getFeatured(isAdmin(jwt))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No series is featured"));
    }

    /**
     * The series "Catalogue", newest first — see {@link SeriesService#getCatalogue}.
     * {@code voice} is optional; omit it for every voice mixed together.
     */
    @GetMapping("/catalogue")
    public Page<SeriesSummaryDto> getCatalogue(
        @RequestParam(required = false) SeriesVoice voice,
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return seriesService.getCatalogue(voice, isAdmin(jwt), pageable);
    }

    /** The app's onboarding/tour series — see {@link SeriesService#getOnboardingSeries}. */
    @GetMapping("/onboarding")
    public SeriesSummaryDto getOnboardingSeries(@AuthenticationPrincipal Jwt jwt) {
        return seriesService.getOnboardingSeries(isAdmin(jwt))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Onboarding series not found"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public SeriesDetailDto create(@Valid @RequestBody SeriesUpsertRequest request) {
        return seriesService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SeriesDetailDto update(@PathVariable UUID id, @Valid @RequestBody SeriesUpsertRequest request) {
        return seriesService.update(id, request);
    }

    /**
     * Uploads a new cover image for this series — see {@link SeriesService#setCoverImage}.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setCoverImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        seriesService.setCoverImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads the principal/hero image for this series' detail page — see {@link SeriesService#setPrincipalImage}.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/principal-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setPrincipalImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        seriesService.setPrincipalImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads the banner image for this series' detail page — see {@link SeriesService#setBannerImage}.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/banner-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setBannerImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        seriesService.setBannerImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads the footer image for this series' detail page — see {@link SeriesService#setFooterImage}.
     *
     * @param id   the series
     * @param file the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/footer-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFooterImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        seriesService.setFooterImage(id, file);
        return ResponseEntity.noContent().build();
    }

    /** Publishes this series — see {@link SeriesService#publish}. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        seriesService.publish(id);
        return ResponseEntity.noContent().build();
    }

    /** Reverts this series to draft — see {@link SeriesService#unpublish}. */
    @DeleteMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unpublish(@PathVariable UUID id) {
        seriesService.unpublish(id);
        return ResponseEntity.noContent().build();
    }

    /** Marks this series as THE featured one — see {@link SeriesService#setFeatured}. */
    @PostMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatured(@PathVariable UUID id) {
        seriesService.setFeatured(id);
        return ResponseEntity.noContent().build();
    }

    /** Removes this series from being featured — see {@link SeriesService#unsetFeatured}. */
    @DeleteMapping("/{id}/featured")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unsetFeatured(@PathVariable UUID id) {
        seriesService.unsetFeatured(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/chapters")
    @PreAuthorize("hasRole('ADMIN')")
    public SeriesChapterDetailDto addChapter(@PathVariable UUID id, @Valid @RequestBody SeriesChapterInput request, @AuthenticationPrincipal Jwt jwt) {
        return seriesService.addChapter(id, currentUserId(jwt), request);
    }

    @DeleteMapping("/{id}/chapters/{chapterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeChapter(@PathVariable UUID id, @PathVariable UUID chapterId) {
        seriesService.removeChapter(id, chapterId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads a cover image for one chapter — see {@link SeriesService#setChapterCoverImage}.
     *
     * @param id        the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/chapters/{chapterId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setChapterCoverImage(@PathVariable UUID id, @PathVariable UUID chapterId, @RequestParam("file") MultipartFile file) {
        seriesService.setChapterCoverImage(id, chapterId, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads the landscape/hero image for one chapter — see {@link SeriesService#setChapterLandscapeImage}.
     * A second, distinct image from {@code /cover}'s, not a size variant of it.
     *
     * @param id        the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the image file (jpeg/png/webp only, see {@code ImageStorageService})
     */
    @PutMapping(value = "/{id}/chapters/{chapterId}/landscape-cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setChapterLandscapeImage(@PathVariable UUID id, @PathVariable UUID chapterId, @RequestParam("file") MultipartFile file) {
        seriesService.setChapterLandscapeImage(id, chapterId, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * Uploads the audio for one chapter — see {@link SeriesService#setChapterAudio}.
     *
     * @param id        the series
     * @param chapterId the chapter, must belong to this series
     * @param file      the audio file (mp3/m4a/wav only, see {@code AudioStorageService})
     */
    @PutMapping(value = "/{id}/chapters/{chapterId}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setChapterAudio(@PathVariable UUID id, @PathVariable UUID chapterId, @RequestParam("file") MultipartFile file) {
        seriesService.setChapterAudio(id, chapterId, file);
        return ResponseEntity.noContent().build();
    }

    /**
     * A short-lived URL to play this chapter's audio — see {@link SeriesService#getChapterAudioUrl}.
     * Not admin-only: any authenticated user viewing a published series can call this.
     *
     * @param id        the series
     * @param chapterId the chapter, must belong to this series
     */
    @GetMapping("/{id}/chapters/{chapterId}/audio-url")
    public ChapterAudioUrlDto getChapterAudioUrl(@PathVariable UUID id, @PathVariable UUID chapterId, @AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveFromJwt(jwt);
        return new ChapterAudioUrlDto(seriesService.getChapterAudioUrl(id, chapterId, user.getId(), user.getRole() == UserRole.ADMIN));
    }

    @PatchMapping("/{id}/chapters/{chapterId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SeriesChapterDetailDto updateChapter(
        @PathVariable UUID id,
        @PathVariable UUID chapterId,
        @Valid @RequestBody SeriesChapterInput request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return seriesService.updateChapter(id, chapterId, currentUserId(jwt), request);
    }

    @PutMapping("/{id}/chapters/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reorderChapters(@PathVariable UUID id, @Valid @RequestBody ReorderSeriesChaptersRequest request) {
        seriesService.reorderChapters(id, request.chapterIds());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/chapters/{chapterId}/complete")
    public SeriesChapterDetailDto completeChapter(@PathVariable UUID id, @PathVariable UUID chapterId, @AuthenticationPrincipal Jwt jwt) {
        return seriesService.completeChapter(id, chapterId, currentUserId(jwt));
    }

    private boolean isAdmin(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getRole() == UserRole.ADMIN;
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
