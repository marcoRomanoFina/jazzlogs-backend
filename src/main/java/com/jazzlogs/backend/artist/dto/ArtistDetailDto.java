package com.jazzlogs.backend.artist.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.editorial.dto.ArtistEditorialDto;
import com.jazzlogs.backend.graph.ArtistAlbumAppearance;
import com.jazzlogs.backend.graph.ArtistTrackAppearance;
import com.jazzlogs.backend.graph.SimilarArtistEntry;
import com.jazzlogs.backend.graph.VocabularyTag;

public record ArtistDetailDto(
    UUID id,
    String name,
    String spotifyArtistId,
    String spotifyUrl,
    String imageUrl,
    ArtistEditorialDto editorial,
    List<VocabularyTag> instruments,
    List<VocabularyTag> styles,
    List<VocabularyTag> contexts,
    List<SimilarArtistEntry> similarArtists,
    List<ArtistAlbumAppearance> albumAppearances,
    List<ArtistTrackAppearance> trackAppearances
) {
}
