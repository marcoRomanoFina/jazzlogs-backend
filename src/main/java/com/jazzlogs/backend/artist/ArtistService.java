package com.jazzlogs.backend.artist;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.dto.ArtistDetailDto;
import com.jazzlogs.backend.artist.dto.CreateArtistRequest;
import com.jazzlogs.backend.artist.dto.SimilarArtistRequest;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.spotify.SpotifyArtistData;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.track.dto.InstrumentTagRequest;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyCodes;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class ArtistService {

    private final ArtistRepository artistRepository;
    private final GraphService graphService;
    private final EditorialService editorialService;
    private final SpotifyCatalogService spotifyCatalogService;

    // Upsert on spotifyArtistId when given: re-posting an artist that's
    // already in the catalog updates it in place (fresh Spotify data)
    // instead of creating a duplicate. Without a spotifyArtistId, this is
    // the manual-entry fallback for artists with no Spotify presence at all
    // (mostly older sidemen) — see createManualArtist.
    @Transactional
    public Artist createOrUpdateArtist(CreateArtistRequest request) {
        if (StringUtils.hasText(request.spotifyArtistId())) {
            return createOrUpdateFromSpotify(request.spotifyArtistId());
        }
        return createManualArtist(request.name());
    }

    private Artist createOrUpdateFromSpotify(String spotifyArtistId) {
        SpotifyArtistData data = spotifyCatalogService.fetchArtist(spotifyArtistId);

        Artist artist = artistRepository.findBySpotifyArtistId(spotifyArtistId)
            .map(existing -> applyToExisting(existing, data))
            .orElseGet(() -> new Artist(data.name(), spotifyArtistId, data.spotifyUrl(), data.imageUrl()));

        Artist saved = artistRepository.save(artist);
        graphService.syncArtistNode(saved.getId(), saved.getName());
        return saved;
    }

    // No spotifyArtistId means no natural id to upsert on, so this always
    // creates a new artist — re-posting the same name doesn't match it back
    // to an existing manual artist. Editing one after creation isn't
    // supported yet (there's no PATCH/PUT for a bare name change).
    private Artist createManualArtist(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either spotifyArtistId or name is required");
        }

        Artist artist = new Artist(name, null, null, null);
        Artist saved = artistRepository.save(artist);
        graphService.syncArtistNode(saved.getId(), saved.getName());
        return saved;
    }

    private Artist applyToExisting(Artist artist, SpotifyArtistData data) {
        artist.setName(data.name());
        artist.setSpotifyUrl(data.spotifyUrl());
        artist.setImageUrl(data.imageUrl());
        return artist;
    }

    public void setPrimaryInstrument(UUID artistId, InstrumentTagRequest request) {
        getArtistOrThrow(artistId);
        VocabularyCodes.validate(InstrumentVocabulary.class, request.instrumentCode(), "instrument");
        graphService.setPrimaryInstrument(artistId, request.instrumentCode());
    }

    public void replaceStyles(UUID artistId, StyleTagRequest request) {
        getArtistOrThrow(artistId);
        request.styleCodes().forEach(code -> VocabularyCodes.validate(StyleVocabulary.class, code, "style"));
        graphService.replaceArtistStyles(artistId, request.styleCodes());
    }

    public void replaceContexts(UUID artistId, ContextTagRequest request) {
        getArtistOrThrow(artistId);
        request.contextCodes().forEach(code -> VocabularyCodes.validate(ContextVocabulary.class, code, "context"));
        graphService.replaceArtistContexts(artistId, request.contextCodes());
    }

    public void addSimilarArtist(UUID artistId, SimilarArtistRequest request) {
        getArtistOrThrow(artistId);
        getArtistOrThrow(request.similarArtistId());

        boolean bidirectional = Boolean.TRUE.equals(request.bidirectional());
        graphService.addSimilarArtist(artistId, request.similarArtistId(), request.reason(), bidirectional);
    }

    @Transactional(readOnly = true)
    public ArtistDetailDto getArtistDetail(UUID artistId) {
        Artist artist = getArtistOrThrow(artistId);

        return new ArtistDetailDto(
            artist.getId(),
            artist.getName(),
            artist.getSpotifyArtistId(),
            artist.getSpotifyUrl(),
            artist.getImageUrl(),
            editorialService.getArtistEditorialDto(artistId),
            graphService.getArtistInstruments(artistId),
            graphService.getArtistStyles(artistId),
            graphService.getArtistContexts(artistId),
            graphService.getSimilarArtists(artistId),
            graphService.getArtistAlbumAppearances(artistId),
            graphService.getArtistTrackAppearances(artistId)
        );
    }

    private Artist getArtistOrThrow(UUID artistId) {
        return artistRepository.findById(artistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found: " + artistId));
    }
}
