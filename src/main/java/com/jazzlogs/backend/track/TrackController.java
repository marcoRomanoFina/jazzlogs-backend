package com.jazzlogs.backend.track;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.MoodTagRequest;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.TrackEditorial;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.note.NoteService;
import com.jazzlogs.backend.note.dto.CreateNoteRequest;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.track.dto.InstrumentTagRequest;
import com.jazzlogs.backend.track.dto.PerformerRequest;
import com.jazzlogs.backend.track.dto.RhythmTagRequest;
import com.jazzlogs.backend.track.dto.TrackDto;
import com.jazzlogs.backend.track.dto.UpdateTrackRequest;
import com.jazzlogs.backend.trackrating.TrackRatingService;
import com.jazzlogs.backend.trackrating.dto.CreateTrackRatingRequest;
import com.jazzlogs.backend.trackrating.dto.TrackRatingDto;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/tracks")
@AllArgsConstructor
public class TrackController {

    private final TrackService trackService;
    private final EditorialService editorialService;
    private final ListenService listenService;
    private final NoteService noteService;
    private final TrackRatingService trackRatingService;
    private final UserService userService;

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TrackDto updateTrack(@PathVariable UUID id, @RequestBody UpdateTrackRequest request) {
        Track track = trackService.updateTrack(id, request);
        return trackService.toDto(track);
    }

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public TrackEditorialDto upsertEditorial(@PathVariable UUID id, @RequestBody TrackEditorialRequest request) {
        TrackEditorial editorial = editorialService.upsertTrackEditorial(id, request);
        return editorialService.toDto(editorial);
    }

    @PostMapping("/{id}/performers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addPerformer(@PathVariable UUID id, @RequestBody PerformerRequest request) {
        trackService.addPerformer(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/mood")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addMood(@PathVariable UUID id, @RequestBody MoodTagRequest request) {
        trackService.addMood(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/context")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addContext(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        trackService.addContext(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/rhythm")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addRhythm(@PathVariable UUID id, @RequestBody RhythmTagRequest request) {
        trackService.addRhythm(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/tags/instrument")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addInstrument(@PathVariable UUID id, @RequestBody InstrumentTagRequest request) {
        trackService.addInstrument(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/entry-point/{artistId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> markEntryPoint(@PathVariable UUID id, @PathVariable UUID artistId) {
        trackService.markEntryPoint(id, artistId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/listen")
    public ResponseEntity<Void> markListened(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        listenService.markTrackListened(currentUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteDto> createNote(@PathVariable UUID id, @Valid @RequestBody CreateNoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        NoteDto note = noteService.createNote(currentUserId(jwt), id, request.text(), request.timestampSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    @GetMapping("/{id}/notes")
    public List<NoteDto> getTrackNotes(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.getTrackNotes(id, currentUserId(jwt));
    }

    @GetMapping("/{id}/notes/me")
    public List<NoteDto> getMyTrackNotes(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return noteService.getMyTrackNotes(id, currentUserId(jwt));
    }

    @PostMapping("/{id}/ratings")
    public TrackRatingDto upsertRating(@PathVariable UUID id, @Valid @RequestBody CreateTrackRatingRequest request, @AuthenticationPrincipal Jwt jwt) {
        return trackRatingService.upsertRating(currentUserId(jwt), id, request.rating());
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
