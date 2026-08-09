package com.jazzlogs.backend.saveditem;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.saveditem.dto.SaveItemRequest;
import com.jazzlogs.backend.saveditem.dto.SavedItemSummary;
import com.jazzlogs.backend.saveditem.dto.SavedResponse;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

// No @PreAuthorize here — any authenticated role can save/unsave, gated only by
// the default anyRequest().authenticated() rule in SecurityConfig. DELETE takes
// query params, not a body, matching LikeController — DELETE-with-body is
// unreliable across HTTP clients/proxies.
@RestController
@RequestMapping("/saved-items")
@AllArgsConstructor
public class SavedItemController {

    private final SavedItemService savedItemService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Void> save(@Valid @RequestBody SaveItemRequest request, @AuthenticationPrincipal Jwt jwt) {
        boolean created = savedItemService.save(currentUserId(jwt), request.entityType(), request.entityId());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> remove(
        @RequestParam SaveableEntityType entityType,
        @RequestParam UUID entityId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        savedItemService.remove(currentUserId(jwt), entityType, entityId);
        return ResponseEntity.noContent().build();
    }

    // type is required — SavedItemService.list resolves display data in one
    // batch query for a single entity_type; no "all types" mode. No sort
    // default here either: SavedItemRepository's query already hardcodes
    // ORDER BY created_at DESC, a Pageable-driven Sort on top would get
    // appended after it redundantly.
    @GetMapping
    public Page<SavedItemSummary> list(
        @RequestParam(name = "type") SaveableEntityType type,
        @PageableDefault Pageable pageable,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return savedItemService.list(currentUserId(jwt), type, pageable);
    }

    @GetMapping("/me")
    public SavedResponse isSaved(
        @RequestParam SaveableEntityType entityType,
        @RequestParam UUID entityId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return new SavedResponse(savedItemService.isSaved(currentUserId(jwt), entityType, entityId));
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
