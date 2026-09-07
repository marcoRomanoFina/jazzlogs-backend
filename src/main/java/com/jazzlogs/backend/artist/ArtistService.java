package com.jazzlogs.backend.artist;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.dto.ArtistTagsDto;
import com.jazzlogs.backend.artist.dto.ArtistHeaderDto;
import com.jazzlogs.backend.artist.dto.CreateArtistRequest;
import com.jazzlogs.backend.artist.dto.EssentialListeningAlbumDto;
import com.jazzlogs.backend.artist.dto.SimilarArtistRequest;
import com.jazzlogs.backend.editorial.AlbumEditorialRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.review.ReviewRepository;
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
    private final AlbumRepository albumRepository;
    private final ReviewRepository reviewRepository;
    private final AlbumEditorialRepository albumEditorialRepository;

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

    /**
     * The artist editorial page's header — the artist's own fields plus its
     * editorial. Nothing from Neo4j (instruments/styles/contexts/similar
     * artists/appearances) — those live on a separate, more expensive endpoint.
     *
     * @param artistId      the artist to load
     * @param currentUserId whose like state to include for the artist's own editorial
     * @return the artist header
     * @throws ResponseStatusException 404 if the artist doesn't exist
     */
    @Transactional(readOnly = true)
    public ArtistHeaderDto getArtistHeader(UUID artistId, UUID currentUserId) {
        Artist artist = getArtistOrThrow(artistId);

        return new ArtistHeaderDto(
            artist.getId(),
            artist.getName(),
            artist.getSpotifyArtistId(),
            artist.getSpotifyUrl(),
            artist.getImageUrl(),
            editorialService.getArtistEditorialDto(artistId, currentUserId)
        );
    }

    /**
     * The artist page's "Essential Listening" section — albums curated as a
     * good entry point into this artist, paginated. The candidate album ids
     * come from one unpaged Neo4j read ({@link GraphService#getEntryPointAlbumIds}) —
     * the real pagination (and {@code Page}'s total count) happens here in
     * Postgres, not in Cypher, since that candidate set is small and curated.
     *
     * @param artistId the artist
     * @param pageable page request
     * @return the matching page, empty if this artist has no entry-point albums
     * @throws ResponseStatusException 404 if the artist doesn't exist
     */
    @Transactional(readOnly = true)
    public Page<EssentialListeningAlbumDto> getEssentialListening(UUID artistId, Pageable pageable) {
        getArtistOrThrow(artistId);

        List<UUID> entryPointAlbumIds = graphService.getEntryPointAlbumIds(artistId);
        if (entryPointAlbumIds.isEmpty()) {
            return Page.empty(pageable);
        }

        Page<Album> page = albumRepository.findByIdInOrderByReleaseYearAsc(entryPointAlbumIds, pageable);
        List<UUID> pageAlbumIds = page.getContent().stream().map(Album::getId).toList();

        Map<UUID, BigDecimal> avgRatingsByAlbumId = reviewRepository.findAvgRatingsByAlbumIds(pageAlbumIds).stream()
            .collect(Collectors.toMap(ReviewRepository.AlbumRatingRow::getAlbumId, ReviewRepository.AlbumRatingRow::getAvgRating));
        Map<UUID, String> deksByAlbumId = albumEditorialRepository.findDeksByAlbumIds(pageAlbumIds).stream()
            .collect(Collectors.toMap(AlbumEditorialRepository.AlbumEditorialDekRow::getAlbumId, AlbumEditorialRepository.AlbumEditorialDekRow::getDek));

        return page.map(album -> new EssentialListeningAlbumDto(
            album.getId(),
            album.getName(),
            album.getImageUrl(),
            album.getReleaseYear(),
            album.getLabel(),
            avgRatingsByAlbumId.get(album.getId()),
            deksByAlbumId.get(album.getId()),
            album.getArtist().getId(),
            album.getArtist().getName()
        ));
    }

    /**
     * The artist's Neo4j-derived tags. Similar artists and appearances live
     * on their own separate endpoints, not here.
     *
     * @param artistId the artist
     * @return the artist's instrument/style/context tags
     * @throws ResponseStatusException 404 if the artist doesn't exist
     */
    @Transactional(readOnly = true)
    public ArtistTagsDto getArtistTags(UUID artistId) {
        getArtistOrThrow(artistId);

        return new ArtistTagsDto(
            graphService.getArtistInstruments(artistId),
            graphService.getArtistStyles(artistId),
            graphService.getArtistContexts(artistId)
        );
    }

    private Artist getArtistOrThrow(UUID artistId) {
        return artistRepository.findById(artistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found: " + artistId));
    }
}
