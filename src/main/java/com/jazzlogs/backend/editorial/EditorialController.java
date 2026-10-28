package com.jazzlogs.backend.editorial;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.editorial.dto.EditorialCountResponse;
import com.jazzlogs.backend.editorial.dto.EditorialTrackSummaryDto;
import com.jazzlogs.backend.editorial.dto.LatestEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/editorials")
@AllArgsConstructor
public class EditorialController {

    private static final int CATALOGUE_PAGE_SIZE = 12;

    private final EditorialService editorialService;
    private final UserService userService;

    /** Count-only — see {@link EditorialService#countEditorials}. No auth-dependent data, doesn't need the JWT principal. */
    @GetMapping("/count")
    public EditorialCountResponse count() {
        return new EditorialCountResponse(editorialService.countEditorials());
    }

    /**
     * The archive's free-form search/browse listing — see {@link EditorialService#listEditorials}.
     *
     * @param q        case-insensitive substring match against the editorial's title or its track's name; omitted means no filter
     * @param page     zero-based page number; every page always contains up to 12 editorials
     * @param jwt      the caller, resolved to a user id only to compute each result's {@code likedByCurrentUser}
     * @return the matching page
     */
    @GetMapping
    public Page<TrackEditorialCatalogueDto> list(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return editorialService.listEditorials(
            q,
            PageRequest.of(page, CATALOGUE_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")),
            userService.resolveFromJwt(jwt).getId()
        );
    }

    /** The latest editorials, newest first. */
    @GetMapping("/recent")
    public List<EditorialTrackSummaryDto> recent(
        @RequestParam(defaultValue = "15") int n,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return editorialService.getRecentEditorials(n, userService.resolveFromJwt(jwt).getId());
    }

    /** The most recently created editorial, including its preview hook. */
    @GetMapping("/latest")
    public LatestEditorialDto latest(@AuthenticationPrincipal Jwt jwt) {
        return editorialService.getLatestEditorial(userService.resolveFromJwt(jwt).getId());
    }
}
