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

    /** See {@link NoteRepository#findTopNPerTrackByTrackIdInAndUserId} for why this is capped per track, not just an overall limit. */
    public static final int MAX_MY_NOTES_PER_TRACK_IN_PLAYLIST_DETAIL = 5;

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final TrackRepository trackRepository;
    private final LikeService likeService;

    /**
     * Creates a note on a track. A user can leave several notes on the same
     * track (different moments) — no uniqueness check here on purpose.
     *
     * @param userId           the author
     * @param trackId          the track being noted
     * @param title            required
     * @param text             required
     * @param timestampSeconds optional — a specific moment in the track this note is about
     * @return the created note
     */
    @Transactional
    public NoteDto createNote(UUID userId, UUID trackId, String title, String text, Integer timestampSeconds) {
        User user = getUserOrThrow(userId);
        Track track = getTrackOrThrow(trackId);

        Note saved = noteRepository.save(new Note(user, track, title, text, timestampSeconds));
        return toDto(saved, false, user.getResolvedDisplayName()); // brand new, can't have any likes yet
    }

    /**
     * Deletes a note — only its own author may.
     *
     * @param noteId           the note to delete
     * @param requestingUserId the caller
     */
    @Transactional
    public void deleteNote(UUID noteId, UUID requestingUserId) {
        Note note = getNoteOrThrow(noteId);
        assertAuthor(note, requestingUserId);
        noteRepository.delete(note);
    }

    /**
     * A track's whole note feed, paginated — the caller's own notes first,
     * then everyone else's.
     *
     * @param trackId       the track
     * @param currentUserId the viewer — "mine first" and each note's likedByCurrentUser
     * @param pageable      page request
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<NoteDto> getTrackNotes(UUID trackId, UUID currentUserId, Pageable pageable) {
        Page<Note> notes = noteRepository.findByTrackIdOrderByMineFirst(trackId, currentUserId, pageable);
        Set<UUID> liked = likedIds(notes.getContent(), currentUserId);
        Map<UUID, String> names = namesByUserId(notes.getContent());
        return notes.map(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())));
    }

    /**
     * The caller's own notes on this track, oldest first, paginated.
     *
     * @param trackId  the track
     * @param userId   the caller — also whose notes these are
     * @param pageable page request
     * @return that user's notes on that track
     */
    @Transactional(readOnly = true)
    public Page<NoteDto> getMyTrackNotes(UUID trackId, UUID userId, Pageable pageable) {
        Page<Note> notes = noteRepository.findByTrackIdAndUserIdOrderByCreatedAtAsc(trackId, userId, pageable);
        Set<UUID> liked = likedIds(notes.getContent(), userId);
        Map<UUID, String> names = namesByUserId(notes.getContent());
        return notes.map(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())));
    }

    /**
     * One user's own notes across a whole set of tracks, grouped by track —
     * for PlaylistService.getPlaylistDetail, so each playlist track can show
     * "your note on this track" without a query per track.
     *
     * @param trackIds the tracks (e.g. a playlist's whole tracklist)
     * @param userId   whose notes to fetch — also the viewer, for likedByCurrentUser
     * @return that user's notes, grouped by track id (missing key = no notes on that track)
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<NoteDto>> getMyNotesForTracks(List<UUID> trackIds, UUID userId) {
        if (trackIds.isEmpty()) {
            return Map.of();
        }
        List<Note> notes = noteRepository.findTopNPerTrackByTrackIdInAndUserId(trackIds, userId, MAX_MY_NOTES_PER_TRACK_IN_PLAYLIST_DETAIL);
        Set<UUID> liked = likedIds(notes, userId);
        Map<UUID, String> names = namesByUserId(notes);

        return notes.stream()
            .collect(Collectors.groupingBy(
                note -> note.getTrack().getId(),
                Collectors.mapping(note -> toDto(note, liked.contains(note.getId()), names.get(note.getUserId())), Collectors.toList())
            ));
    }

    /**
     * Which of these notes the viewer has liked, batched into one call.
     *
     * @param notes         the notes to check
     * @param currentUserId the viewer
     * @return ids of the notes the viewer has liked
     */
    private Set<UUID> likedIds(List<Note> notes, UUID currentUserId) {
        List<UUID> noteIds = notes.stream().map(Note::getId).toList();
        return likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.NOTE, noteIds);
    }

    /**
     * Display names for these notes' authors, batched into one lookup.
     *
     * @param notes the notes whose authors need a name
     * @return display name by author user id
     */
    private Map<UUID, String> namesByUserId(List<Note> notes) {
        List<UUID> userIds = notes.stream().map(Note::getUserId).distinct().toList();
        return userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getResolvedDisplayName));
    }

    /**
     * Maps one note to its DTO.
     *
     * @param note               the note
     * @param likedByCurrentUser whether the viewer has liked it
     * @param userName           the author's display name
     * @return the mapped DTO
     */
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

    /**
     * @throws ResponseStatusException 403 if {@code requestingUserId} did not author this note
     */
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
