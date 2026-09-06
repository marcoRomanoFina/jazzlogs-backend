package com.jazzlogs.backend.track.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;

/**
 * One track within the list {@code AlbumService#getAlbumTracks} returns —
 * the album page's per-track card, current user's own state included
 * (rating/listened/saved). Notes are NOT here — see {@code
 * TrackController#getTrackNotes}, its own paginated per-track endpoint.
 *
 * @param id                  the track's own id
 * @param trackNumber         position on the album; {@code null} if Neo4j has no placement recorded for it
 * @param spotifyTrackId      Spotify's own id for this track
 * @param name                the track's name
 * @param durationMs          length in milliseconds
 * @param spotifyUrl          link to play it on Spotify
 * @param imageUrl            borrowed from the album's cover art — a track has no artwork of its own on Spotify
 * @param standout            admin-set at creation — "notable within its own album", distinct from {@code Track#featured}
 * @param vocalProfile        instrumental/vocal classification
 * @param energy              tag: how energetic the track is
 * @param accessibility       tag: how approachable/accessible the track is
 * @param moodIntensity       tag: how strong the mood is
 * @param tempoFeel           tag: the track's tempo feel
 * @param compositionType     tag: original/cover/standard, etc.
 * @param editorial           this track's own editorial, {@code null} if none written yet
 * @param performers          sidemen/personnel credited on this track, from Neo4j
 * @param moods               mood tags, from Neo4j
 * @param contexts            context tags, from Neo4j
 * @param rhythms             rhythm tags, from Neo4j
 * @param featuredInstruments instruments called out as featured, from Neo4j
 * @param avgRating           average rating across every user, {@code null} if unrated
 * @param ratingCount         how many users have rated it
 * @param myRating            the current user's own rating, {@code null} if they haven't rated it
 * @param hasListened         whether the current user has marked this track listened
 * @param isSaved             whether the current user has saved this track
 */
public record TrackDto(
    UUID id,
    Integer trackNumber,
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
    BigDecimal avgRating,
    long ratingCount,
    BigDecimal myRating,
    boolean hasListened,
    boolean isSaved
) {
}
