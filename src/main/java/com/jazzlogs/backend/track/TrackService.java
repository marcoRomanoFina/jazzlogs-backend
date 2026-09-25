package com.jazzlogs.backend.track;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.saveditem.SavedItemService;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.spotify.SpotifyTrackAlbumData;
import com.jazzlogs.backend.spotify.SpotifyTrackArtistData;
import com.jazzlogs.backend.spotify.SpotifyTrackData;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;
import com.jazzlogs.backend.track.dto.FeaturedInstrumentsRequest;
import com.jazzlogs.backend.track.dto.PerformerRequest;
import com.jazzlogs.backend.track.dto.RhythmTagRequest;
import com.jazzlogs.backend.track.dto.TrackDetailDto;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.track.dto.TrackTagsDto;
import com.jazzlogs.backend.trackrating.TrackRating;
import com.jazzlogs.backend.trackrating.TrackRatingRepository;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
import com.jazzlogs.backend.vocabulary.StyleVocabulary;
import com.jazzlogs.backend.vocabulary.VocabularyCodes;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class TrackService {

    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final ArtistRepository artistRepository;
    private final GraphService graphService;
    private final EditorialService editorialService;
    private final SpotifyCatalogService spotifyCatalogService;
    private final TrackRatingRepository trackRatingRepository;
    private final ListenService listenService;
    private final SavedItemService savedItemService;

    // Upsert on spotifyTrackId: re-posting a track that's already in the
    // catalog updates it in place (fresh Spotify data + the editable fields
    // below) instead of creating a duplicate. Track-first: the track's own
    // Spotify lookup carries its embedded album/artist, resolved or created
    // as a side effect on the create path only (see resolveOrCreateArtist/
    // resolveOrCreateAlbum) — no separate album/artist admin step anymore.
    // The track's album is never reassigned on update, so the Neo4j CONTAINS
    // edge always uses the track's actual (original) album. A brand-new
    // track's position (CONTAINS.trackNumber) and the album's totalTracks
    // are both assigned by upload order here, not Spotify's own
    // track_number/total_tracks — those count bonus/alternate takes we
    // often deliberately skip cataloguing, which would leave gaps
    // otherwise. An update to an existing track never touches either.
    @Transactional
    public Track createOrUpdateTrack(CreateTrackRequest request) {
        SpotifyTrackData data = spotifyCatalogService.fetchTrack(request.spotifyTrackId());

        Optional<Track> existingTrack = trackRepository.findBySpotifyTrackId(request.spotifyTrackId());
        Track track = existingTrack
            .map(existing -> applyToExisting(existing, data, request))
            .orElseGet(() -> {
                Artist artist = resolveOrCreateArtist(data.artist());
                Album album = resolveOrCreateAlbum(data.album(), artist);
                return new Track(
                    album,
                    data.spotifyTrackId(),
                    data.name(),
                    data.durationMs(),
                    data.spotifyUrl(),
                    data.imageUrl(),
                    request.vocalProfile(),
                    request.energy(),
                    request.accessibility(),
                    request.moodIntensity(),
                    request.tempoFeel(),
                    request.compositionType()
                );
            });
        Track saved = trackRepository.save(track);

        graphService.syncTrackNode(saved.getId(), saved.getName());

        if (existingTrack.isEmpty()) {
            Album album = saved.getAlbum();
            int trackNumber = graphService.getTrackPlacements(album.getId()).size() + 1;
            graphService.addTrackToAlbum(album.getId(), saved.getId(), trackNumber);
            album.setTotalTracks(trackNumber);
        }

        return saved;
    }

    /**
     * Resolves the track's primary Spotify artist to an existing {@link
     * Artist} row, or creates a minimal one — upsert by spotifyArtistId. An
     * already-existing artist is returned as-is, never overwritten: this
     * runs on every new-track ingestion, so re-fetching wouldn't add
     * anything (no image comes from a track lookup anyway, see {@link
     * SpotifyTrackArtistData}) and could clobber curated data (e.g. an
     * admin-set image) added some other way later.
     */
    private Artist resolveOrCreateArtist(SpotifyTrackArtistData data) {
        return artistRepository.findBySpotifyArtistId(data.spotifyArtistId())
            .orElseGet(() -> {
                Artist artist = new Artist(data.name(), data.spotifyArtistId(), data.spotifyUrl(), null);
                Artist saved = artistRepository.save(artist);
                graphService.syncArtistNode(saved.getId(), saved.getName());
                return saved;
            });
    }

    /** Same "resolve, don't overwrite" contract as {@link #resolveOrCreateArtist} — upsert by spotifyAlbumId. */
    private Album resolveOrCreateAlbum(SpotifyTrackAlbumData data, Artist artist) {
        return albumRepository.findBySpotifyAlbumId(data.spotifyAlbumId())
            .orElseGet(() -> {
                Album album = new Album(
                    artist, data.name(), data.spotifyAlbumId(), data.spotifyUrl(), data.imageUrl(),
                    data.releaseYear(), 0
                );
                Album saved = albumRepository.save(album);
                graphService.syncAlbumNode(saved.getId(), saved.getName());
                return saved;
            });
    }

    private Track applyToExisting(Track track, SpotifyTrackData data, CreateTrackRequest request) {
        track.setName(data.name());
        track.setDurationMs(data.durationMs());
        track.setSpotifyUrl(data.spotifyUrl());
        track.setImageUrl(data.imageUrl());
        track.setVocalProfile(request.vocalProfile());
        track.setEnergy(request.energy());
        track.setAccessibility(request.accessibility());
        track.setMoodIntensity(request.moodIntensity());
        track.setTempoFeel(request.tempoFeel());
        track.setCompositionType(request.compositionType());
        return track;
    }

    public void addPerformer(UUID trackId, PerformerRequest request) {
        getTrackOrThrow(trackId);
        getArtistOrThrow(request.artistId());

        if (StringUtils.hasText(request.instrumentCode())) {
            VocabularyCodes.validate(InstrumentVocabulary.class, request.instrumentCode(), "instrument");
        }

        graphService.addPerformance(
            request.artistId(),
            trackId,
            request.role().name(),
            request.instrumentCode(),
            request.primaryCredit()
        );
    }

    public void replaceStyles(UUID trackId, StyleTagRequest request) {
        getTrackOrThrow(trackId);
        request.styleCodes().forEach(code -> VocabularyCodes.validate(StyleVocabulary.class, code, "style"));
        graphService.replaceTrackStyles(trackId, request.styleCodes());
    }

    public void replaceMoods(UUID trackId, MoodTagRequest request) {
        getTrackOrThrow(trackId);
        request.moodCodes().forEach(code -> VocabularyCodes.validate(MoodVocabulary.class, code, "mood"));
        graphService.replaceTrackMoods(trackId, request.moodCodes());
    }

    public void replaceContexts(UUID trackId, ContextTagRequest request) {
        getTrackOrThrow(trackId);
        request.contextCodes().forEach(code -> VocabularyCodes.validate(ContextVocabulary.class, code, "context"));
        graphService.replaceTrackContexts(trackId, request.contextCodes());
    }

    public void replaceRhythms(UUID trackId, RhythmTagRequest request) {
        getTrackOrThrow(trackId);
        request.rhythmCodes().forEach(code -> VocabularyCodes.validate(RhythmVocabulary.class, code, "rhythm"));
        graphService.replaceRhythms(trackId, request.rhythmCodes());
    }

    public void replaceFeaturedInstruments(UUID trackId, FeaturedInstrumentsRequest request) {
        getTrackOrThrow(trackId);
        request.instrumentCodes().forEach(code -> VocabularyCodes.validate(InstrumentVocabulary.class, code, "instrument"));
        graphService.replaceFeaturedInstruments(trackId, request.instrumentCodes());
    }

    @Transactional(readOnly = true)
    public TrackTagsDto getTrackTags(UUID trackId) {
        getTrackOrThrow(trackId);
        return new TrackTagsDto(
            graphService.getTrackStyles(trackId),
            graphService.getTrackMoods(trackId),
            graphService.getTrackContexts(trackId),
            graphService.getTrackRhythms(trackId),
            graphService.getTrackFeaturedInstruments(trackId)
        );
    }

    /** Up to this many tracks can be {@link Track#isFeatured} at once — see {@link #setFeatured}. */
    static final int MAX_FEATURED_TRACKS = 6;

    /**
     * Adds {@code trackId} to the archive's curated "Featured Tracks".
     * The cap is a {@code COUNT(*)} check here, not a DB constraint —
     * unlike {@code Editorial#featurated}'s "at most 1" (a partial unique
     * index), "at most 6" isn't expressible that way. Two admins racing
     * right at the cap could in theory both pass this check and land on 7;
     * accepted for a low-traffic admin action rather than adding a stored
     * procedure or advisory lock for it.
     *
     * @throws ResponseStatusException 404 if the track doesn't exist, 409 if
     *                                  it has no {@code TrackEditorial} yet
     *                                  (see {@code EditorialService#getFeaturedTracks} —
     *                                  a featured track with no editorial
     *                                  wouldn't show up there at all) or if
     *                                  already at {@link #MAX_FEATURED_TRACKS}
     */
    @Transactional
    public void setFeatured(UUID trackId) {
        Track track = getTrackOrThrow(trackId);
        if (!editorialService.hasTrackEditorial(trackId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Track has no editorial yet, can't be featured");
        }
        if (!track.isFeatured() && trackRepository.countFeatured() >= MAX_FEATURED_TRACKS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already " + MAX_FEATURED_TRACKS + " featured tracks");
        }
        trackRepository.markFeatured(trackId);
    }

    /** Removes {@code trackId} from the "Featured Tracks" strip — a no-op if it wasn't featured. */
    @Transactional
    public void unsetFeatured(UUID trackId) {
        getTrackOrThrow(trackId);
        trackRepository.unmarkFeatured(trackId);
    }

    /**
     * The track detail page's full payload — the track's own everything plus
     * its artist/album context, flattened so the frontend doesn't need a
     * separate lookup (same idea as {@code AlbumSummaryDto}).
     *
     * @param trackId       the track to load
     * @param currentUserId whose rating/listen/save state to include
     * @throws ResponseStatusException 404 if the track doesn't exist
     */
    @Transactional(readOnly = true)
    public TrackDetailDto getTrackDetail(UUID trackId, UUID currentUserId) {
        Track track = getTrackOrThrow(trackId);
        Album album = track.getAlbum();
        Artist artist = album.getArtist();

        TrackRatingRepository.TrackRatingStats stats = trackRatingRepository.getRatingStatsForTracks(List.of(trackId))
            .stream().findFirst().orElse(null);
        BigDecimal myRating = trackRatingRepository.findByUserIdAndTrackId(currentUserId, trackId)
            .map(TrackRating::getRating).orElse(null);
        boolean hasListened = listenService.getListenedTrackIds(currentUserId, List.of(trackId)).contains(trackId);
        boolean isSaved = savedItemService.getSavedEntityIds(currentUserId, SaveableEntityType.TRACK, List.of(trackId)).contains(trackId);

        TrackDto trackDto = toTrackDto(track, new TrackBatchContext(
            graphService.getTrackPlacement(trackId),
            editorialService.getTrackEditorialDto(trackId, currentUserId),
            graphService.getTrackPerformers(trackId),
            graphService.getTrackStyles(trackId),
            graphService.getTrackMoods(trackId),
            graphService.getTrackContexts(trackId),
            graphService.getTrackRhythms(trackId),
            graphService.getTrackFeaturedInstruments(trackId),
            stats == null ? null : stats.getAvgRating(),
            stats == null ? 0 : stats.getCount(),
            myRating,
            hasListened,
            isSaved
        ));

        return new TrackDetailDto(
            artist.getId(), artist.getName(), artist.getImageUrl(), artist.getSpotifyUrl(),
            album.getId(), album.getName(), album.getImageUrl(), album.getSpotifyUrl(), album.getReleaseYear(),
            trackDto
        );
    }

    public TrackDto toTrackDto(Track track) {
        return toTrackDto(track, graphService.getTrackPlacement(track.getId()));
    }

    public TrackDto toTrackDto(Track track, TrackPlacement placement) {
        return toTrackDto(track, placement, editorialService.getTrackEditorialDto(track.getId()));
    }

    /** editorialDto comes pre-fetched too — everything else is still one query per track. */
    public TrackDto toTrackDto(Track track, TrackPlacement placement, TrackEditorialDto editorialDto) {
        UUID trackId = track.getId();
        return toTrackDto(track, new TrackBatchContext(
            placement,
            editorialDto,
            graphService.getTrackPerformers(trackId),
            graphService.getTrackStyles(trackId),
            graphService.getTrackMoods(trackId),
            graphService.getTrackContexts(trackId),
            graphService.getTrackRhythms(trackId),
            graphService.getTrackFeaturedInstruments(trackId),
            null,
            0,
            null,
            false,
            false
        ));
    }

    /** Everything already pre-fetched by the caller — no queries of any kind in here, unlike the overloads above. */
    public TrackDto toTrackDto(Track track, TrackBatchContext ctx) {
        UUID trackId = track.getId();
        TrackPlacement placement = ctx.placement();

        return new TrackDto(
            trackId,
            placement == null ? null : placement.trackNumber(),
            track.getSpotifyTrackId(),
            track.getName(),
            track.getDurationMs(),
            track.getSpotifyUrl(),
            track.getImageUrl(),
            track.getVocalProfile(),
            track.getEnergy(),
            track.getAccessibility(),
            track.getMoodIntensity(),
            track.getTempoFeel(),
            track.getCompositionType(),
            ctx.editorial(),
            ctx.performers(),
            ctx.styles(),
            ctx.moods(),
            ctx.contexts(),
            ctx.rhythms(),
            ctx.featuredInstruments(),
            ctx.avgRating(),
            ctx.ratingCount(),
            ctx.myRating(),
            ctx.hasListened(),
            ctx.isSaved()
        );
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }

    private Artist getArtistOrThrow(UUID artistId) {
        return artistRepository.findById(artistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found: " + artistId));
    }
}
