package com.jazzlogs.backend.artist;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.dto.ArtistDetailDto;
import com.jazzlogs.backend.artist.dto.CreateArtistRequest;
import com.jazzlogs.backend.artist.dto.SimilarArtistRequest;
import com.jazzlogs.backend.artist.dto.UpdateArtistRequest;
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
    private final ArtistMapper artistMapper;

    @Transactional
    public Artist createArtist(CreateArtistRequest request) {
        SpotifyArtistData data = spotifyCatalogService.fetchArtist(request.spotifyArtistId());

        Artist artist = new Artist(data.name(), request.spotifyArtistId(), data.spotifyUrl());
        Artist saved = artistRepository.save(artist);
        graphService.syncArtistNode(saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public Artist updateArtist(UUID artistId, UpdateArtistRequest request) {
        Artist artist = getArtistOrThrow(artistId);
        artistMapper.applyPatch(request, artist);

        Artist saved = artistRepository.save(artist);
        if (request.name() != null) {
            graphService.syncArtistNode(saved.getId(), saved.getName());
        }
        return saved;
    }

    public void setPrimaryInstrument(UUID artistId, InstrumentTagRequest request) {
        getArtistOrThrow(artistId);
        VocabularyCodes.validate(InstrumentVocabulary.class, request.instrumentCode(), "instrument");
        graphService.setPrimaryInstrument(artistId, request.instrumentCode());
    }

    public void addStyle(UUID artistId, StyleTagRequest request) {
        getArtistOrThrow(artistId);
        VocabularyCodes.validate(StyleVocabulary.class, request.styleCode(), "style");
        graphService.addArtistStyle(artistId, request.styleCode());
    }

    public void addContext(UUID artistId, ContextTagRequest request) {
        getArtistOrThrow(artistId);
        VocabularyCodes.validate(ContextVocabulary.class, request.contextCode(), "context");
        graphService.addArtistContext(artistId, request.contextCode());
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
