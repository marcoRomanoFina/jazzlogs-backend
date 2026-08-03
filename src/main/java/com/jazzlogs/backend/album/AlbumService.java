package com.jazzlogs.backend.album;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.AlbumDetailDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CreateAlbumRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.PersonnelRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.review.ReviewService;
import com.jazzlogs.backend.review.dto.AlbumRatingStats;
import com.jazzlogs.backend.spotify.SpotifyAlbumData;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.track.TrackService;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyCodes;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final GraphService graphService;
    private final SpotifyCatalogService spotifyCatalogService;
    private final TrackService trackService;
    private final EditorialService editorialService;
    private final NoteService noteService;
    private final ReviewService reviewService;

    // Upsert on spotifyAlbumId: re-posting an album that's already in the
    // catalog updates it in place (fresh Spotify data + the editable fields
    // below) instead of creating a duplicate. postedAt is only stamped on the
    // create path — an update never resets when the album was first posted.
    @Transactional
    public Album createOrUpdateAlbum(CreateAlbumRequest request) {
        Artist artist = getArtistOrThrow(request.artistId());
        SpotifyAlbumData data = spotifyCatalogService.fetchAlbum(request.spotifyAlbumId());

        Album album = albumRepository.findBySpotifyAlbumId(request.spotifyAlbumId())
            .map(existing -> applyToExisting(existing, data, request))
            .orElseGet(() -> new Album(
                artist,
                data.name(),
                request.spotifyAlbumId(),
                data.spotifyUrl(),
                data.imageUrl(),
                data.releaseYear(),
                data.totalTracks(),
                request.logNumber(),
                request.vocalProfile(),
                request.energy(),
                request.moodIntensity(),
                request.accessibility(),
                Instant.now(),
                request.instagramPermalink()
            ));

        Album saved = albumRepository.save(album);
        graphService.syncAlbumNode(saved.getId(), saved.getName());

        // Tracks are created independently via POST /albums/{id}/tracks, not here —
        // each one looks itself up on Spotify (see TrackService.addTrack) instead of
        // being derived from this album fetch.
        return saved;
    }

    private Album applyToExisting(Album album, SpotifyAlbumData data, CreateAlbumRequest request) {
        album.setName(data.name());
        album.setSpotifyUrl(data.spotifyUrl());
        album.setImageUrl(data.imageUrl());
        album.setReleaseYear(data.releaseYear());
        album.setTotalTracks(data.totalTracks());
        album.setLogNumber(request.logNumber());
        album.setVocalProfile(request.vocalProfile());
        album.setEnergy(request.energy());
        album.setMoodIntensity(request.moodIntensity());
        album.setAccessibility(request.accessibility());
        album.setInstagramPermalink(request.instagramPermalink());
        return album;
    }

    public void addPersonnel(UUID albumId, PersonnelRequest request) {
        getAlbumOrThrow(albumId);
        getArtistOrThrow(request.artistId());

        if (request.role() == PersonnelRole.LEADER) {
            graphService.setAlbumLeader(request.artistId(), albumId);
        } else {
            List<String> instruments = request.instruments() == null ? List.of() : request.instruments();
            graphService.addSideman(request.artistId(), albumId, instruments);
        }
    }

    public void markEntryPoint(UUID albumId, UUID artistId) {
        getAlbumOrThrow(albumId);
        getArtistOrThrow(artistId);
        graphService.markAsEntryPoint(albumId, artistId);
    }

    public void addStyle(UUID albumId, StyleTagRequest request) {
        getAlbumOrThrow(albumId);
        VocabularyCodes.validate(StyleVocabulary.class, request.styleCode(), "style");
        graphService.addStyle(albumId, request.styleCode());
    }

    public void addMood(UUID albumId, MoodTagRequest request) {
        getAlbumOrThrow(albumId);
        VocabularyCodes.validate(MoodVocabulary.class, request.moodCode(), "mood");
        graphService.addMood(albumId, request.moodCode());
    }

    public void addContext(UUID albumId, ContextTagRequest request) {
        getAlbumOrThrow(albumId);
        VocabularyCodes.validate(ContextVocabulary.class, request.contextCode(), "context");
        graphService.addContext(albumId, request.contextCode());
    }

    @Transactional(readOnly = true)
    public AlbumDetailDto getAlbumDetail(UUID albumId, UUID currentUserId) {
        Album album = getAlbumOrThrow(albumId);

        AlbumEditorialDto editorialDto = editorialService.getAlbumEditorialDto(albumId, currentUserId);

        // One query for every track's placement, instead of one per track.
        Map<UUID, TrackPlacement> placements = graphService.getTrackPlacements(albumId).stream()
            .collect(Collectors.toMap(TrackPlacement::trackId, placement -> placement));

        // Same idea: one query for every note the current user left anywhere on
        // this album, instead of one per track.
        Map<UUID, List<NoteDto>> notesByTrack = noteService.getMyNotesForAlbum(albumId, currentUserId);

        AlbumRatingStats ratingStats = reviewService.getAlbumRatingStats(albumId);

        List<TrackDto> trackDtos = album.getTracks().stream()
            .map(track -> trackService.toDto(
                track,
                placements.get(track.getId()),
                notesByTrack.getOrDefault(track.getId(), List.of())
            ))
            .sorted(Comparator.comparing(TrackDto::trackNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        return new AlbumDetailDto(
            album.getId(),
            album.getArtist().getId(),
            album.getArtist().getName(),
            album.getName(),
            album.getSpotifyAlbumId(),
            album.getSpotifyUrl(),
            album.getImageUrl(),
            album.getReleaseYear(),
            album.getTotalTracks(),
            album.getLogNumber(),
            album.getVocalProfile(),
            album.getEnergy(),
            album.getMoodIntensity(),
            album.getAccessibility(),
            album.getPostedAt(),
            album.getInstagramPermalink(),
            editorialDto,
            trackDtos,
            graphService.getStyles(albumId),
            graphService.getMoods(albumId),
            graphService.getContexts(albumId),
            graphService.getPersonnel(albumId),
            ratingStats.avgRating(),
            ratingStats.count()
        );
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
    }

    private Artist getArtistOrThrow(UUID artistId) {
        return artistRepository.findById(artistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found: " + artistId));
    }
}
