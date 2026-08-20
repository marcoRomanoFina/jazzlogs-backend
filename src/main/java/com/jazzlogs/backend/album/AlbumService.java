package com.jazzlogs.backend.album;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.dto.AlbumDetailDto;
import com.jazzlogs.backend.album.dto.AlbumSpotlightDto;
import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.CreateAlbumRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.album.dto.PersonnelRequest;
import com.jazzlogs.backend.album.dto.SpotlightTrackDto;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.AlbumEditorialRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.review.ReviewService;
import com.jazzlogs.backend.review.dto.AlbumRatingStats;
import com.jazzlogs.backend.saveditem.SavedItemService;
import com.jazzlogs.backend.saveditem.SaveableEntityType;
import com.jazzlogs.backend.spotify.SpotifyAlbumData;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackBatchContext;
import com.jazzlogs.backend.track.TrackService;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.trackrating.TrackRating;
import com.jazzlogs.backend.trackrating.TrackRatingRepository;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
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
    private final AlbumEditorialRepository albumEditorialRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final LikeService likeService;
    private final ListenService listenService;
    private final SavedItemService savedItemService;
    private final TrackRatingRepository trackRatingRepository;

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
                request.label(),
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
        album.setLabel(request.label());
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

        List<String> instruments = request.instruments() == null ? List.of() : request.instruments();
        instruments.forEach(code -> VocabularyCodes.validate(InstrumentVocabulary.class, code, "instrument"));

        if (request.role() == PersonnelRole.LEADER) {
            graphService.setAlbumLeader(request.artistId(), albumId, instruments);
        } else {
            graphService.addSideman(request.artistId(), albumId, instruments);
        }
    }

    public void markEntryPoint(UUID albumId, UUID artistId) {
        getAlbumOrThrow(albumId);
        getArtistOrThrow(artistId);
        graphService.markAsEntryPoint(albumId, artistId);
    }

    public void replaceStyles(UUID albumId, StyleTagRequest request) {
        getAlbumOrThrow(albumId);
        request.styleCodes().forEach(code -> VocabularyCodes.validate(StyleVocabulary.class, code, "style"));
        graphService.replaceStyles(albumId, request.styleCodes());
    }

    public void replaceMoods(UUID albumId, MoodTagRequest request) {
        getAlbumOrThrow(albumId);
        request.moodCodes().forEach(code -> VocabularyCodes.validate(MoodVocabulary.class, code, "mood"));
        graphService.replaceMoods(albumId, request.moodCodes());
    }

    public void replaceContexts(UUID albumId, ContextTagRequest request) {
        getAlbumOrThrow(albumId);
        request.contextCodes().forEach(code -> VocabularyCodes.validate(ContextVocabulary.class, code, "context"));
        graphService.replaceContexts(albumId, request.contextCodes());
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

        // And again for every track's own editorial, instead of one per track.
        Map<UUID, TrackEditorialDto> editorialsByTrack = editorialService.getTrackEditorialDtosByAlbumId(albumId);

        // Same idea again, this time for the five Neo4j lookups toDto used to
        // run once per track (performers, moods, contexts, rhythms, featured
        // instruments) — this was the real N+1: 5 graph round-trips per
        // track, not just the one editorial query above.
        Map<UUID, List<TrackPerformerEntry>> performersByTrack = graphService.getTrackPerformersForAlbum(albumId);
        Map<UUID, List<VocabularyTag>> moodsByTrack = graphService.getTrackMoodsForAlbum(albumId);
        Map<UUID, List<VocabularyTag>> contextsByTrack = graphService.getTrackContextsForAlbum(albumId);
        Map<UUID, List<VocabularyTag>> rhythmsByTrack = graphService.getTrackRhythmsForAlbum(albumId);
        Map<UUID, List<VocabularyTag>> instrumentsByTrack = graphService.getTrackFeaturedInstrumentsForAlbum(albumId);

        AlbumRatingStats ratingStats = reviewService.getAlbumRatingStats(albumId);

        List<UUID> trackIds = album.getTracks().stream().map(Track::getId).toList();

        // One query for every track's avg/count, instead of one per track —
        // same batching principle as everything else in this method.
        Map<UUID, TrackRatingRepository.TrackRatingStats> ratingStatsByTrack = trackRatingRepository
            .getRatingStatsForTracks(trackIds).stream()
            .collect(Collectors.toMap(TrackRatingRepository.TrackRatingStats::getTrackId, s -> s));

        // And the current user's own rating on each of those tracks, batched
        // the same way.
        Map<UUID, BigDecimal> myRatingByTrack = trackRatingRepository
            .findByUserIdAndTrackIdIn(currentUserId, trackIds).stream()
            .collect(Collectors.toMap(tr -> tr.getTrack().getId(), TrackRating::getRating));

        Set<UUID> listenedTrackIds = listenService.getListenedTrackIds(currentUserId, trackIds);
        Set<UUID> savedTrackIds = savedItemService.getSavedEntityIds(currentUserId, SaveableEntityType.TRACK, trackIds);

        List<TrackDto> trackDtos = album.getTracks().stream()
            .map(track -> {
                UUID trackId = track.getId();
                TrackRatingRepository.TrackRatingStats stats = ratingStatsByTrack.get(trackId);
                return trackService.toDto(track, new TrackBatchContext(
                    placements.get(trackId),
                    notesByTrack.getOrDefault(trackId, List.of()),
                    editorialsByTrack.get(trackId),
                    performersByTrack.getOrDefault(trackId, List.of()),
                    moodsByTrack.getOrDefault(trackId, List.of()),
                    contextsByTrack.getOrDefault(trackId, List.of()),
                    rhythmsByTrack.getOrDefault(trackId, List.of()),
                    instrumentsByTrack.getOrDefault(trackId, List.of()),
                    stats == null ? null : stats.getAvgRating(),
                    stats == null ? 0 : stats.getCount(),
                    myRatingByTrack.get(trackId),
                    listenedTrackIds.contains(trackId),
                    savedTrackIds.contains(trackId)
                ));
            })
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
            album.getLabel(),
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
            ratingStats.count(),
            // Derived live from the same listenedTrackIds used for the track
            // rows above, not a separately-set flag — see AlbumDetailDto.
            !trackIds.isEmpty() && listenedTrackIds.size() == trackIds.size(),
            listenedTrackIds.size(),
            listenService.countAlbumListens(albumId),
            savedItemService.isSaved(currentUserId, SaveableEntityType.ALBUM, albumId)
        );
    }

    // Lean cousin of getAlbumDetail, purpose-built for the archive page's
    // spotlight: no personnel/tags/ratings/notes, and the one Neo4j call it
    // does make (track placements, for ordering) is a single query rather
    // than the five per-track ones getAlbumDetail needs for its fuller view.
    @Transactional(readOnly = true)
    public AlbumSpotlightDto getAlbumSpotlight(UUID albumId, UUID currentUserId) {
        Album album = getAlbumOrThrow(albumId);

        AlbumEditorialRepository.EditorialTeaserRow editorialTeaser =
            albumEditorialRepository.findTeaserByAlbumId(albumId).orElse(null);

        Map<UUID, TrackPlacement> placements = graphService.getTrackPlacements(albumId).stream()
            .collect(Collectors.toMap(TrackPlacement::trackId, placement -> placement));

        List<TrackEditorialRepository.TrackTeaserRow> trackTeaserRows =
            trackEditorialRepository.findTeasersByAlbumId(albumId);
        Map<UUID, TrackEditorialRepository.TrackTeaserRow> trackTeasers = trackTeaserRows.stream()
            .collect(Collectors.toMap(TrackEditorialRepository.TrackTeaserRow::getTrackId, row -> row));

        // One query for whether the current user liked ANY of these
        // editorials (the album's plus every track's), instead of one per
        // editorial.
        List<UUID> editorialIds = new ArrayList<>(trackTeaserRows.stream()
            .map(TrackEditorialRepository.TrackTeaserRow::getEditorialId)
            .toList());
        if (editorialTeaser != null) {
            editorialIds.add(editorialTeaser.getId());
        }
        Set<UUID> likedEditorialIds = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, editorialIds);

        List<SpotlightTrackDto> tracks = album.getTracks().stream()
            .filter(track -> trackTeasers.containsKey(track.getId()))
            .map(track -> {
                TrackEditorialRepository.TrackTeaserRow teaser = trackTeasers.get(track.getId());
                TrackPlacement placement = placements.get(track.getId());
                return new SpotlightTrackDto(
                    track.getId(),
                    placement == null ? null : placement.trackNumber(),
                    track.getName(),
                    teaser.getTitle(),
                    teaser.getDek(),
                    teaser.getLikeCount(),
                    likedEditorialIds.contains(teaser.getEditorialId())
                );
            })
            .sorted(Comparator.comparing(SpotlightTrackDto::trackNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        return new AlbumSpotlightDto(
            album.getId(),
            album.getArtist().getName(),
            album.getName(),
            album.getImageUrl(),
            album.getReleaseYear(),
            album.getPostedAt(),
            editorialTeaser == null ? null : editorialTeaser.getTitle(),
            editorialTeaser == null ? null : editorialTeaser.getDek(),
            editorialTeaser == null ? null : editorialTeaser.getByline(),
            editorialTeaser == null ? 0 : editorialTeaser.getLikeCount(),
            editorialTeaser != null && likedEditorialIds.contains(editorialTeaser.getId()),
            tracks
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
