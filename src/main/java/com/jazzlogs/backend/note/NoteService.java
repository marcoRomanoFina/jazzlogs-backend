package com.jazzlogs.backend.note;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class NoteService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final LikeService likeService;

    @Transactional
    public NoteDto createNote(UUID userId, UUID trackId, String title, String text, Integer timestampSeconds) {
        User user = getUserOrThrow(userId);
        Track track = getTrackOrThrow(trackId);

        Note saved = noteRepository.save(new Note(user, track, title, text, timestampSeconds));
        return toDto(saved, false, user.getResolvedDisplayName()); // brand new, can't have any likes yet
    }

    @Transactional
    public void deleteNote(UUID noteId, UUID requestingUserId) {
        Note note = getNoteOrThrow(noteId);
        assertAuthor(note, requestingUserId);
        noteRepository.delete(note);
    }

    @Transactional(readOnly = true)
    public Page<NoteDto> getTrackNotes(UUID trackId, UUID currentUserId, Pageable pageable) {
        Page<Note> notes = noteRepository.findByTrackIdOrderByMineFirst(trackId, currentUserId, pageable);
        Set<UUID> liked = likedIds(notes.getContent(), currentUserId);
        Map<UUID, String> names = namesByUserId(notes.getContent());
        return notes.map(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())));
    }

    @Transactional(readOnly = true)
    public List<NoteDto> getMyTrackNotes(UUID trackId, UUID userId) {
        return toDtos(noteRepository.findByTrackIdAndUserIdOrderByCreatedAtAsc(trackId, userId), userId);
    }

    /**
     * For AlbumService.getAlbumDetail — one query for every note the current
     * user left across the whole album, grouped by trackId, so each TrackDto
     * can be built without a per-track query.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<NoteDto>> getMyNotesForAlbum(UUID albumId, UUID currentUserId) {
        List<Note> notes = noteRepository.findByUserAndAlbum(currentUserId, albumId);
        Set<UUID> liked = likedIds(notes, currentUserId);
        Map<UUID, String> names = namesByUserId(notes);

        return notes.stream()
            .collect(Collectors.groupingBy(
                note -> note.getTrack().getId(),
                Collectors.mapping(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())), Collectors.toList())
            ));
    }

    /**
     * For ReviewService — every note any of `authorUserIds` left on this
     * album's tracks, grouped by author, as full NoteDtos (not a lean
     * summary) so the frontend can render and open them exactly like the
     * per-track note feed. likedByCurrentUser is computed against the
     * viewer (currentUserId) — deliberately NOT the note's author, since
     * whoever is looking at these reviews is the one whose like state
     * matters, same as every other likedByCurrentUser in this service.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<NoteDto>> getNotesByAuthorsForAlbum(UUID albumId, List<UUID> authorUserIds, UUID currentUserId) {
        if (authorUserIds.isEmpty()) {
            return Map.of();
        }
        List<Note> notes = noteRepository.findByAlbumIdAndUserIdIn(albumId, authorUserIds);
        Set<UUID> liked = likedIds(notes, currentUserId);
        Map<UUID, String> names = namesByUserId(notes);

        return notes.stream()
            .collect(Collectors.groupingBy(
                Note::getUserId,
                Collectors.mapping(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())), Collectors.toList())
            ));
    }

    /** Batch — one hasUserLikedBatch call and one user lookup for the whole list, not one per note. */
    private List<NoteDto> toDtos(List<Note> notes, UUID currentUserId) {
        Set<UUID> liked = likedIds(notes, currentUserId);
        Map<UUID, String> names = namesByUserId(notes);
        return notes.stream().map(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId()))).toList();
    }

    private Set<UUID> likedIds(List<Note> notes, UUID currentUserId) {
        List<UUID> noteIds = notes.stream().map(Note::getId).toList();
        return likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.NOTE, noteIds);
    }

    private Map<UUID, String> namesByUserId(List<Note> notes) {
        List<UUID> userIds = notes.stream().map(Note::getUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getResolvedDisplayName));
    }

    private NoteDto toDto(Note note, boolean likedByCurrentUser, String userName) {
        return new NoteDto(
            note.getId(),
            note.getTrack().getId(),
            note.getUserId(),
            userName,
            note.getTitle(),
            note.getText(),
            note.getTimestampSeconds(),
            note.getLikeCount(),
            likedByCurrentUser,
            note.getCreatedAt()
        );
    }

    private void assertAuthor(Note note, UUID requestingUserId) {
        if (!note.getUserId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can modify this note");
        }
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }

    private Note getNoteOrThrow(UUID noteId) {
        return noteRepository.findById(noteId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found: " + noteId));
    }
}
