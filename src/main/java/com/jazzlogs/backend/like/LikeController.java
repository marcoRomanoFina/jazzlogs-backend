package com.jazzlogs.backend.like;

import java.util.UUID;

import jakarta.validation.Valid;

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

import com.jazzlogs.backend.like.dto.LikeCountResponse;
import com.jazzlogs.backend.like.dto.LikeRequest;
import com.jazzlogs.backend.like.dto.LikedResponse;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

// No @PreAuthorize here — any authenticated role can like/unlike, gated only by
// the default anyRequest().authenticated() rule in SecurityConfig.
@RestController
@RequestMapping("/likes")
@AllArgsConstructor
public class LikeController {

    private final LikeService likeService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Void> addLike(@Valid @RequestBody LikeRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = userService.resolveFromJwt(jwt).getId();
        boolean created = likeService.addLike(userId, request.entityType(), request.entityId());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> removeLike(
        @RequestParam LikeableEntityType entityType,
        @RequestParam UUID entityId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = userService.resolveFromJwt(jwt).getId();
        likeService.removeLike(userId, entityType, entityId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public LikeCountResponse countLikes(@RequestParam LikeableEntityType entityType, @RequestParam UUID entityId) {
        return new LikeCountResponse(likeService.countLikes(entityType, entityId));
    }

    @GetMapping("/me")
    public LikedResponse hasLiked(
        @RequestParam LikeableEntityType entityType,
        @RequestParam UUID entityId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = userService.resolveFromJwt(jwt).getId();
        return new LikedResponse(likeService.hasUserLiked(userId, entityType, entityId));
    }
}
