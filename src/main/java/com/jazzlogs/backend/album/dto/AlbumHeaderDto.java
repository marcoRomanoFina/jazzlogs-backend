package com.jazzlogs.backend.album.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.graph.AlbumPersonnelEntry;
import com.jazzlogs.backend.graph.VocabularyTag;

// Split out of the old AlbumDetailDto — everything about the album except
// its track list, which is now its own endpoint (see AlbumService.getAlbumTracks).
public record AlbumHeaderDto(
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
    String label,
    VocalProfile vocalProfile,
    Level energy,
    Level moodIntensity,
    Level accessibility,
    Instant postedAt,
    String instagramPermalink,
    AlbumEditorialDto editorial,
    List<VocabularyTag> styles,
    List<VocabularyTag> moods,
    List<VocabularyTag> contexts,
    List<AlbumPersonnelEntry> personnel,
    BigDecimal avgRating,
    long reviewCount,
    boolean hasListened,
    int listenedTrackCount,
    long listenCount,
    boolean isSaved
) {
}
