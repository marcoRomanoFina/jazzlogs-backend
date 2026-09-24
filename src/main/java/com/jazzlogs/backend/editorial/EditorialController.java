package com.jazzlogs.backend.editorial;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.editorial.dto.EditorialCountResponse;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/editorials")
@AllArgsConstructor
public class EditorialController {

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
     * @param pageable defaults to {@code size=6}, sorted by {@code createdAt} DESC
     * @param jwt      the caller, resolved to a user id only to compute each result's {@code likedByCurrentUser}
     * @return the matching page
     */
    @GetMapping
    public Page<TrackEditorialCatalogueDto> list(
        @RequestParam(required = false) String q,
        @PageableDefault(size = 6, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return editorialService.listEditorials(q, pageable, userService.resolveFromJwt(jwt).getId());
    }
}
