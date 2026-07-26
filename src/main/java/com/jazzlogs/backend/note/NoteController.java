package com.jazzlogs.backend.note;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

// Note-scoped operations (by the note's own id) live here. Track-scoped ones
// (create, list) live on TrackController, nested under /tracks/{id}/notes.
// No update — notes are delete + recreate, not edited in place.
@RestController
@RequestMapping("/notes")
@AllArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final UserService userService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNote(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        noteService.deleteNote(id, currentUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
