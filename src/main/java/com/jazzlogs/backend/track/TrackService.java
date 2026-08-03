package com.jazzlogs.backend.track;

import java.util.List;
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
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.spotify.SpotifyCatalogService;
import com.jazzlogs.backend.spotify.SpotifyTrackData;
import com.jazzlogs.backend.track.dto.CreateTrackRequest;
import com.jazzlogs.backend.track.dto.InstrumentTagRequest;
import com.jazzlogs.backend.track.dto.PerformerRequest;
import com.jazzlogs.backend.track.dto.RhythmTagRequest;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.vocabulary.ContextVocabulary;
import com.jazzlogs.backend.vocabulary.InstrumentVocabulary;
import com.jazzlogs.backend.vocabulary.MoodVocabulary;
import com.jazzlogs.backend.vocabulary.RhythmVocabulary;
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

    // Upsert on spotifyTrackId: re-posting a track that's already in the
    // catalog updates it in place (fresh Spotify data + the editable fields
    // below) instead of creating a duplicate. The track's album is never
    // reassigned on update — see AlbumService.applyToExisting for the same
    // reasoning on albums/artists — so the Neo4j CONTAINS edge uses the
    // track's actual album, not necessarily the one in the URL.
    @Transactional
    public Track createOrUpdateTrack(UUID albumId, CreateTrackRequest request) {
        Album album = getAlbumOrThrow(albumId);
        SpotifyTrackData data = spotifyCatalogService.fetchTrack(request.spotifyTrackId());

        Track track = trackRepository.findBySpotifyTrackId(request.spotifyTrackId())
            .map(existing -> applyToExisting(existing, data, request))
            .orElseGet(() -> new Track(
                album,
                data.spotifyTrackId(),
                data.name(),
                data.durationMs(),
                data.spotifyUrl(),
                data.imageUrl(),
                request.standout(),
                request.vocalProfile(),
                request.energy(),
                request.accessibility(),
                request.moodIntensity(),
                request.tempoFeel(),
                request.compositionType()
            ));
        Track saved = trackRepository.save(track);

        graphService.syncTrackNode(saved.getId(), saved.getName());
        String trackRole = request.trackRole() == null ? "MAIN" : request.trackRole();
        graphService.addTrackToAlbum(saved.getAlbum().getId(), saved.getId(), data.trackNumber(), trackRole);

        return saved;
    }

    private Track applyToExisting(Track track, SpotifyTrackData data, CreateTrackRequest request) {
        track.setName(data.name());
        track.setDurationMs(data.durationMs());
        track.setSpotifyUrl(data.spotifyUrl());
        track.setImageUrl(data.imageUrl());
        track.setStandout(request.standout());
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

    public void addMood(UUID trackId, MoodTagRequest request) {
        getTrackOrThrow(trackId);
        VocabularyCodes.validate(MoodVocabulary.class, request.moodCode(), "mood");
        graphService.addTrackMood(trackId, request.moodCode());
    }

    public void addContext(UUID trackId, ContextTagRequest request) {
        getTrackOrThrow(trackId);
        VocabularyCodes.validate(ContextVocabulary.class, request.contextCode(), "context");
        graphService.addTrackContext(trackId, request.contextCode());
    }

    public void addRhythm(UUID trackId, RhythmTagRequest request) {
        getTrackOrThrow(trackId);
        VocabularyCodes.validate(RhythmVocabulary.class, request.rhythmCode(), "rhythm");
        graphService.addRhythm(trackId, request.rhythmCode());
    }

    public void addInstrument(UUID trackId, InstrumentTagRequest request) {
        getTrackOrThrow(trackId);
        VocabularyCodes.validate(InstrumentVocabulary.class, request.instrumentCode(), "instrument");
        graphService.addFeaturedInstrument(trackId, request.instrumentCode());
    }

    public void markEntryPoint(UUID trackId, UUID artistId) {
        getTrackOrThrow(trackId);
        getArtistOrThrow(artistId);
        graphService.markTrackAsEntryPoint(trackId, artistId);
    }

    public TrackDto toDto(Track track) {
        return toDto(track, graphService.getTrackPlacement(track.getId()));
    }

    public TrackDto toDto(Track track, TrackPlacement placement) {
        return toDto(track, placement, List.of());
    }

    /** myNotes comes pre-fetched (see AlbumService.getAlbumDetail) — no query in here. */
    public TrackDto toDto(Track track, TrackPlacement placement, List<NoteDto> myNotes) {
        UUID trackId = track.getId();
        TrackEditorialDto editorialDto = editorialService.getTrackEditorialDto(trackId);

        return new TrackDto(
            trackId,
            placement == null ? null : placement.trackNumber(),
            placement == null ? null : placement.trackRole(),
            track.getSpotifyTrackId(),
            track.getName(),
            track.getDurationMs(),
            track.getSpotifyUrl(),
            track.getImageUrl(),
            track.isStandout(),
            track.getVocalProfile(),
            track.getEnergy(),
            track.getAccessibility(),
            track.getMoodIntensity(),
            track.getTempoFeel(),
            track.getCompositionType(),
            editorialDto,
            graphService.getTrackPerformers(trackId),
            graphService.getTrackMoods(trackId),
            graphService.getTrackContexts(trackId),
            graphService.getTrackRhythms(trackId),
            graphService.getTrackFeaturedInstruments(trackId),
            myNotes
        );
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
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
