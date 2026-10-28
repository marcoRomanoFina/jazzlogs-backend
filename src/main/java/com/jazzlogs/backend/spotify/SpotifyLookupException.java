package com.jazzlogs.backend.spotify;

/**
 * Spotify catalog lookup failed. {@code notFound} distinguishes "the id doesn't
 * exist on Spotify" (the caller's fault — bad spotifyAlbumId) from any other
 * failure (network error, rate limit exhausted, auth failure — Spotify's fault).
 */
public class SpotifyLookupException extends RuntimeException {

    private final boolean notFound;

    public SpotifyLookupException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public SpotifyLookupException(String message, Throwable cause) {
        super(message, cause);
        this.notFound = false;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
