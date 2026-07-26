package com.jazzlogs.backend.album.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.graph.AlbumPersonnelEntry;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.track.dto.TrackDto;

public record AlbumDetailDto(
    UUID id,
    UUID artistId,
    String artistName,
    String name,
    String spotifyAlbumId,
    String spotifyUrl,
    String imageUrl,
    Integer releaseYear,
    Integer totalTracks,
    String logNumber,
    VocalProfile vocalProfile,
    Level energy,
    Level moodIntensity,
    Level accessibility,
    Instant postedAt,
    String instagramPermalink,
    AlbumEditorialDto editorial,
    List<TrackDto> tracks,
    List<VocabularyTag> styles,
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<AlbumPersonnelEntry> personnel
) {
}
