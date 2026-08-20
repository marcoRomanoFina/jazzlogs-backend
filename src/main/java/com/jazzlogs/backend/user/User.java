package com.jazzlogs.backend.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "supabase_user_id", nullable = false, unique = true)
    private UUID supabaseUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    private String displayName;

    private String firstName;

    private String lastName;

    private String email;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant lastLoginAt;

    public User(UUID supabaseUserId, String email) {
        this.supabaseUserId = supabaseUserId;
        this.email = email;
        this.role = UserRole.USER;
        this.status = UserStatus.ACTIVE;
    }

    public void recordLogin(String email) {
        this.lastLoginAt = Instant.now();
        if (email != null) {
            this.email = email;
        }
    }

    /**
     * displayName stays null until the user actually sets one — that null is
     * a real signal (UserResponse.from surfaces it as-is, so the frontend
     * can prompt onboarding), not a bug. Everywhere else a name gets shown
     * to someone other than the user themself (note/review authorship,
     * batched author-name lookups, chat context for the LLM), read this
     * instead of the raw field: falling back to the email's local part
     * keeps those call sites from ever handling a null, notably
     * Collectors.toMap(User::getId, ...), which throws on any null value.
     */
    public String getResolvedDisplayName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "Someone";
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
