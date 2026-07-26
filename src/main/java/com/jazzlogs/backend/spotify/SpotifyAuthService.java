package com.jazzlogs.backend.spotify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;

/**
 * Client-credentials (app-only) auth against Spotify — no user login involved.
 * The token is cached in memory and only refreshed once it's close to expiry.
 */
@Slf4j
@Service
public class SpotifyAuthService {

    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    private final RestClient restClient;

    @Value("${spotify.client-id:}")
    private String clientId;

    @Value("${spotify.client-secret:}")
    private String clientSecret;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SpotifyAuthService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl("https://accounts.spotify.com").build();
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET are not configured");
        }

        String credentials = Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        TokenResponse response = restClient.post()
            .uri("/api/token")
            .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials")
            .retrieve()
            .body(TokenResponse.class);

        if (response == null) {
            throw new IllegalStateException("Spotify token endpoint returned an empty response");
        }

        cachedToken = response.accessToken();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(0, response.expiresIn() - EXPIRY_SAFETY_MARGIN_SECONDS));

        log.info("Fetched a new Spotify access token, valid for {}s", response.expiresIn());

        return cachedToken;
    }

    private record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") int expiresIn
    ) {
    }
}
