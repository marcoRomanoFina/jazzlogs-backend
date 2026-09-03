package com.jazzlogs.backend.editorial;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.editorial.dto.CatalogueEditorialDto;
import com.jazzlogs.backend.editorial.dto.EditorialCountResponse;
import com.jazzlogs.backend.editorial.dto.EditorialSummaryDto;
import com.jazzlogs.backend.editorial.dto.LastLogDto;
import com.jazzlogs.backend.editorial.dto.RecentAlbumEditorialDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/editorials")
@AllArgsConstructor
public class EditorialController {

    private final EditorialService editorialService;
    private final UserService userService;

    /**
     * Curated single "hero" slot for the archive page — see {@link
     * EditorialRepository#clearFeaturated}/{@link
     * EditorialRepository#markFeaturated} (only ever one featurated
     * editorial at a time, across every owner type).
     *
     * @param jwt the caller, resolved to a user id only to compute {@code likedByCurrentUser}
     * @return the featurated editorial
     * @throws ResponseStatusException 404 if none is set
     */
    @GetMapping("/featured")
    public EditorialSummaryDto featured(@AuthenticationPrincipal Jwt jwt) {
        return editorialService.getFeatured(userService.resolveFromJwt(jwt).getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No featured editorial set"));
    }

    /** Count-only — see {@link EditorialService#countEditorials}. No auth-dependent data, doesn't need the JWT principal. */
    @GetMapping("/count")
    public EditorialCountResponse count() {
        return new EditorialCountResponse(editorialService.countEditorials());
    }

    /**
     * "Recently filed" — see {@link EditorialService#getRecentAlbumEditorials}.
     *
     * @param jwt the caller, resolved to a user id only to compute {@code likedByCurrentUser}
     * @return the most recently created album editorials, newest first
     */
    @GetMapping("/recent")
    public List<RecentAlbumEditorialDto> recent(@AuthenticationPrincipal Jwt jwt) {
        return editorialService.getRecentAlbumEditorials(userService.resolveFromJwt(jwt).getId());
    }

    /**
     * "The Last Log" — see {@link EditorialService#getLastLog}.
     *
     * @param jwt the caller, resolved to a user id only to compute {@code likedByCurrentUser}
     * @return the most recently published album editorial, with its track editorials
     * @throws ResponseStatusException 404 if no album editorial exists yet
     */
    @GetMapping("/last-log")
    public LastLogDto lastLog(@AuthenticationPrincipal Jwt jwt) {
        return editorialService.getLastLog(userService.resolveFromJwt(jwt).getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No album editorial exists yet"));
    }

    /**
     * The archive's free-form search/filter/paginate listing, across every
     * owner type — the one place {@code type}/{@code q}/sort genuinely vary
     * per call. 
     *
     * @param type     restricts to one owner type; omitted (or blank) means every type
     * @param q        case-insensitive substring match against title/owner name; omitted means no filter
     * @param pageable defaults to {@code size=6}, sorted by {@code createdAt} DESC
     * @param jwt      the caller, resolved to a user id only to compute {@code likedByCurrentUser}
     * @return the matching page
     */
    @GetMapping
    public Page<CatalogueEditorialDto> list(
        @RequestParam(required = false) EditorialOwnerType type,
        @RequestParam(required = false) String q,
        @PageableDefault(size = 6, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return editorialService.listEditorials(type, q, pageable, userService.resolveFromJwt(jwt).getId());
    }

    // Marks this editorial as THE featurated one, unfeaturating whichever one
    // (if any) held that spot before — see EditorialService.setFeaturated.
    @PostMapping("/{id}/featurated")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeaturated(@PathVariable UUID id) {
        editorialService.setFeaturated(id);
        return ResponseEntity.noContent().build();
    }
}
