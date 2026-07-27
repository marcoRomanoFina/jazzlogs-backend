package com.jazzlogs.backend.series;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RestController;

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
