package com.jazzlogs.backend.album;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.AlbumDetailDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CreateAlbumRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.PersonnelRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.album.dto.UpdateAlbumRequest;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
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
    private final AlbumMapper albumMapper;

    @Transactional
    public Album createAlbum(CreateAlbumRequest request) {
        Artist artist = getArtistOrThrow(request.artistId());
        SpotifyAlbumData data = spotifyCatalogService.fetchAlbum(request.spotifyAlbumId());

        Album album = new Album(
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
            request.postedAt(),
            request.instagramPermalink()
        );

        Album saved = albumRepository.save(album);
        graphService.syncAlbumNode(saved.getId(), saved.getName());

        // Tracks are created independently via POST /albums/{id}/tracks, not here —
        // each one looks itself up on Spotify (see TrackService.addTrack) instead of
        // being derived from this album fetch.
        return saved;
    }

    @Transactional
    public Album updateAlbum(UUID albumId, UpdateAlbumRequest request) {
        Album album = getAlbumOrThrow(albumId);

        String name = request.name();
        String imageUrl = request.imageUrl();
        String spotifyUrl = request.spotifyUrl();
        Integer totalTracks = request.totalTracks();
        Integer releaseYear = request.releaseYear();

        if (StringUtils.hasText(request.spotifyAlbumId())) {
            SpotifyAlbumData data = spotifyCatalogService.fetchAlbum(request.spotifyAlbumId());
            name = data.name();
            imageUrl = data.imageUrl();
            spotifyUrl = data.spotifyUrl();
            totalTracks = data.totalTracks();
            releaseYear = data.releaseYear();
        }

        UpdateAlbumRequest effectiveRequest = new UpdateAlbumRequest(
            name,
            request.spotifyAlbumId(),
            spotifyUrl,
            imageUrl,
            releaseYear,
            totalTracks,
            request.logNumber(),
            request.vocalProfile(),
            request.energy(),
            request.moodIntensity(),
            request.accessibility(),
            request.postedAt(),
            request.instagramPermalink()
        );
        albumMapper.applyPatch(effectiveRequest, album);

        Album saved = albumRepository.save(album);
        if (name != null) {
            graphService.syncAlbumNode(saved.getId(), saved.getName());
        }
        return saved;
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
    public AlbumDetailDto getAlbumDetail(UUID albumId) {
        Album album = getAlbumOrThrow(albumId);

        AlbumEditorialDto editorialDto = editorialService.getAlbumEditorialDto(albumId);

        // One query for every track's placement, instead of one per track.
        Map<UUID, TrackPlacement> placements = graphService.getTrackPlacements(albumId).stream()
            .collect(Collectors.toMap(TrackPlacement::trackId, placement -> placement));

        List<TrackDto> trackDtos = album.getTracks().stream()
            .map(track -> trackService.toDto(track, placements.get(track.getId())))
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
            graphService.getPersonnel(albumId)
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
