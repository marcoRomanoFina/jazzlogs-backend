package com.jazzlogs.backend.user.dto;

import java.util.UUID;

import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRole;

public record UserResponse(
    UUID id,
    String email,
    String displayName,
    UserRole role
) {

    public static UserResponse from(User user) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getRole()
        );
    }
}
