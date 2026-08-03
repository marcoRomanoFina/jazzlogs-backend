package com.jazzlogs.backend.track.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.note.dto.NoteDto;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;

public record TrackDto(
    UUID id,
    Integer trackNumber,
    String trackRole,
    String spotifyTrackId,
    String name,
    Integer durationMs,
    String spotifyUrl,
    String imageUrl,
    boolean standout,
    VocalProfile vocalProfile,
    Level energy,
    Level accessibility,
    Level moodIntensity,
    TempoFeel tempoFeel,
    CompositionType compositionType,
    TrackEditorialDto editorial,
    List<TrackPerformerEntry> performers,
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<VocabularyTag> rhythms,
    List<VocabularyTag> featuredInstruments,
    List<NoteDto> myNotes
) {
}
