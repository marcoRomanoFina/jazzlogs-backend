package com.jazzlogs.backend.spotify;

import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only access to Spotify's catalog (GET /v1/albums/{id}, GET /v1/artists/{id},
 * GET /v1/tracks/{id} — the batch endpoints were removed in Spotify's Feb 2026
 * changelog, so these only ever fetch one resource at a time).
 *
 * All three fetch methods fail hard: any failure (not found, rate-limited twice,
 * network error) surfaces as a SpotifyLookupException — a bad/unreachable
 * spotifyAlbumId/spotifyTrackId/spotifyArtistId must fail the whole
 * album/track/artist creation (see AlbumService, TrackService, ArtistService)
 * rather than leave a half-populated row behind.
 */
@Slf4j
@Service
public class SpotifyCatalogService {

    private final RestClient restClient;
    private final SpotifyAuthService spotifyAuthService;

    public SpotifyCatalogService(RestClient.Builder restClientBuilder, SpotifyAuthService spotifyAuthService) {
        this.restClient = restClientBuilder.baseUrl("https://api.spotify.com").build();
        this.spotifyAuthService = spotifyAuthService;
    }

    public SpotifyAlbumData fetchAlbum(String spotifyAlbumId) {
        try {
            return toAlbumData(callSpotifyAlbum(spotifyAlbumId));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new SpotifyLookupException("Spotify album not found: " + spotifyAlbumId, true);
        } catch (Exception ex) {
            log.error("Failed to fetch Spotify album {}", spotifyAlbumId, ex);
            throw new SpotifyLookupException(
                "Failed to fetch Spotify album " + spotifyAlbumId + ": " + ex.getMessage(), ex);
        }
    }

    public SpotifyTrackData fetchTrack(String spotifyTrackId) {
        try {
            return toTrackData(callSpotifyTrack(spotifyTrackId));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new SpotifyLookupException("Spotify track not found: " + spotifyTrackId, true);
        } catch (Exception ex) {
            log.error("Failed to fetch Spotify track {}", spotifyTrackId, ex);
            throw new SpotifyLookupException(
                "Failed to fetch Spotify track " + spotifyTrackId + ": " + ex.getMessage(), ex);
        }
    }

    public SpotifyArtistData fetchArtist(String spotifyArtistId) {
        try {
            return toArtistData(callSpotifyArtist(spotifyArtistId));
        } catch (HttpClientErrorException.NotFound ex) {
            throw new SpotifyLookupException("Spotify artist not found: " + spotifyArtistId, true);
        } catch (Exception ex) {
            log.error("Failed to fetch Spotify artist {}", spotifyArtistId, ex);
            throw new SpotifyLookupException(
                "Failed to fetch Spotify artist " + spotifyArtistId + ": " + ex.getMessage(), ex);
        }
    }

    private SpotifyAlbumResponse callSpotifyAlbum(String spotifyAlbumId) {
        try {
            return doGetAlbum(spotifyAlbumId);
        } catch (HttpClientErrorException.TooManyRequests ex) {
            long retryAfterSeconds = parseRetryAfter(ex);
            log.warn("Spotify rate-limited us, retrying album {} after {}s", spotifyAlbumId, retryAfterSeconds);
            sleep(retryAfterSeconds);
            return doGetAlbum(spotifyAlbumId);
        }
    }

    private SpotifyTrackItem callSpotifyTrack(String spotifyTrackId) {
        try {
            return doGetTrack(spotifyTrackId);
        } catch (HttpClientErrorException.TooManyRequests ex) {
            long retryAfterSeconds = parseRetryAfter(ex);
            log.warn("Spotify rate-limited us, retrying track {} after {}s", spotifyTrackId, retryAfterSeconds);
            sleep(retryAfterSeconds);
            return doGetTrack(spotifyTrackId);
        }
    }

    private SpotifyArtistResponse callSpotifyArtist(String spotifyArtistId) {
        try {
            return doGetArtist(spotifyArtistId);
        } catch (HttpClientErrorException.TooManyRequests ex) {
            long retryAfterSeconds = parseRetryAfter(ex);
            log.warn("Spotify rate-limited us, retrying artist {} after {}s", spotifyArtistId, retryAfterSeconds);
            sleep(retryAfterSeconds);
            return doGetArtist(spotifyArtistId);
        }
    }

    private SpotifyAlbumResponse doGetAlbum(String spotifyAlbumId) {
        return restClient.get()
            .uri("/v1/albums/{id}", spotifyAlbumId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + spotifyAuthService.getAccessToken())
            .retrieve()
            .body(SpotifyAlbumResponse.class);
    }

    private SpotifyTrackItem doGetTrack(String spotifyTrackId) {
        return restClient.get()
            .uri("/v1/tracks/{id}", spotifyTrackId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + spotifyAuthService.getAccessToken())
            .retrieve()
            .body(SpotifyTrackItem.class);
    }

    private SpotifyArtistResponse doGetArtist(String spotifyArtistId) {
        return restClient.get()
            .uri("/v1/artists/{id}", spotifyArtistId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + spotifyAuthService.getAccessToken())
            .retrieve()
            .body(SpotifyArtistResponse.class);
    }

    private SpotifyAlbumData toAlbumData(SpotifyAlbumResponse response) {
        String imageUrl = (response.images() == null || response.images().isEmpty())
            ? null
            : response.images().get(0).url();

        String spotifyUrl = response.externalUrls() == null ? null : response.externalUrls().spotify();

        return new SpotifyAlbumData(
            response.name(),
            imageUrl,
            spotifyUrl,
            response.totalTracks(),
            parseReleaseYear(response.releaseDate())
        );
    }

    private SpotifyTrackData toTrackData(SpotifyTrackItem item) {
        String imageUrl = (item.album() == null || item.album().images() == null || item.album().images().isEmpty())
            ? null
            : item.album().images().get(0).url();

        return new SpotifyTrackData(
            item.id(),
            item.name(),
            item.durationMs(),
            item.externalUrls() == null ? null : item.externalUrls().spotify(),
            item.trackNumber(),
            imageUrl
        );
    }

    private SpotifyArtistData toArtistData(SpotifyArtistResponse response) {
        String spotifyUrl = response.externalUrls() == null ? null : response.externalUrls().spotify();
        String imageUrl = (response.images() == null || response.images().isEmpty())
            ? null
            : response.images().get(0).url();
        return new SpotifyArtistData(response.id(), response.name(), spotifyUrl, imageUrl);
    }

    private Integer parseReleaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        // release_date_precision can be "year" ("1967"), "month" ("1967-03") or
        // "day" ("1967-03-15") -- the year is always the first 4 characters.
        return Integer.parseInt(releaseDate.substring(0, 4));
    }

    private long parseRetryAfter(HttpClientErrorException.TooManyRequests ex) {
        HttpHeaders headers = ex.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return 1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException nfe) {
            return 1;
        }
    }

    private void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record SpotifyAlbumResponse(
        String name,
        @JsonProperty("total_tracks") Integer totalTracks,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("external_urls") ExternalUrls externalUrls,
        List<Image> images
    ) {
    }

    // Also used as the response shape for GET /v1/tracks/{id} — the fields we
    // care about are identical whether the track comes from an album's embedded
    // list or a direct track lookup. "album" is only populated on the direct
    // lookup — a track has no cover art of its own, so we borrow its album's.
    private record SpotifyTrackItem(
        String id,
        String name,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("track_number") Integer trackNumber,
        @JsonProperty("external_urls") ExternalUrls externalUrls,
        SpotifyTrackAlbum album
    ) {
    }

    private record SpotifyTrackAlbum(List<Image> images) {
    }

    // "genres" (also present on this response) is deliberately not parsed or stored:
    // Spotify's genre taxonomy is a broad, crowd-sourced tagging system, unrelated to
    // our own curated StyleVocabulary. Mixing the two would blur an intentionally
    // editorial classification with one we don't control. If genre data is ever
    // wanted, an editor should map it to StyleVocabulary codes by hand, not auto-import it.
    private record SpotifyArtistResponse(
        String id,
        String name,
        @JsonProperty("external_urls") ExternalUrls externalUrls,
        List<Image> images
    ) {
    }

    private record ExternalUrls(String spotify) {
    }

    private record Image(String url) {
    }
}
