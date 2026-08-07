package com.jazzlogs.backend.editorial;

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

import com.jazzlogs.backend.editorial.dto.EditorialSummaryDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/editorials")
@AllArgsConstructor
public class EditorialController {

    private final EditorialService editorialService;
    private final UserService userService;

    // Curated single "hero" slot for the archive page — see
    // EditorialRepository.clearFeaturated/markFeaturated (only ever one
    // featurated editorial at a time, across every owner type).
    @GetMapping("/featured")
    public EditorialSummaryDto featured(@AuthenticationPrincipal Jwt jwt) {
        EditorialSummaryDto dto = editorialService.getFeatured(userService.resolveFromJwt(jwt).getId());
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No featured editorial set");
        }
        return dto;
    }

    // Cross-type (album/track/artist) editorial listing, backing both the
    // "latest editorials" strip (?size=4, no filters) and the full archive
    // grid (?type=&q=&page=&size=) on the frontend — same resource, just
    // different query params.
    @GetMapping
    public Page<EditorialSummaryDto> list(
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
