package com.jazzlogs.backend.user.dto;

import java.util.UUID;

import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRole;

/**
 * The current user, as returned by {@code GET /me}.
 *
 * @param id          the user's own id (not the Supabase auth id)
 * @param email       from the JWT, kept in sync on every login
 * @param displayName {@code null} until the user sets one — a real signal, not a bug (see {@link User#getDisplayName})
 * @param role        determines admin-only access
 */
public record UserResponse(
    UUID id,
    String email,
    String displayName,
    UserRole role
) {

    /** @return {@code user} projected into the response shape */
    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getRole()
        );
    }
}
