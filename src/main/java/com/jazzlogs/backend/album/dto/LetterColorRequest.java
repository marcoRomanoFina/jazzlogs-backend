package com.jazzlogs.backend.album.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body to set an album's curated {@code letterColor}.
 *
 * @param letterColor hex, e.g. {@code #a86b32} — 6 digits, leading {@code #} required
 */
public record LetterColorRequest(
    @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a hex color like #a86b32") String letterColor
) {
}
