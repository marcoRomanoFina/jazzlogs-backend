package com.jazzlogs.backend.agent.tools;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlock;
import com.jazzlogs.backend.editorial.EditorialBlockType;
import com.jazzlogs.backend.editorial.TrackEditorial;
import com.jazzlogs.backend.editorial.TrackEditorialRepository;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPerformerEntry;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.graph.VocabularyTag;
import com.jazzlogs.backend.track.CompositionType;
import com.jazzlogs.backend.track.TempoFeel;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/**
 * Everything JazzLogs knows about one track, for {@link EditorialContentTool}
 * — what the track detail page shows, minus what only matters to a screen or
 * to one user (images, links, ratings, likes, saved/listened state). Its own
 * class rather than part of the tool because it needs a transaction to walk
 * the lazy album/artists/blocks, and a {@link JazzTool} can't be proxied (its
 * {@code final} accessors would read the proxy's empty fields).
 */
@Service
@AllArgsConstructor
public class TrackLogReader {

    private final TrackRepository trackRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final GraphService graphService;

    /**
     * @return the track with its log, or empty if {@code trackId} is not a track at all
     * @throws IllegalStateException if the track exists but has no log — every track is
     *                               published with one, so this is broken data, not bad input
     */
    @Transactional(readOnly = true)
    public Optional<TrackLog> read(UUID trackId) {
        return trackRepository.findById(trackId).map(track -> {
            TrackEditorial editorial = trackEditorialRepository.findByTrackId(trackId)
                .orElseThrow(() -> new IllegalStateException("Track " + trackId + " has no log"));
            return new TrackLog(toTrackInfo(track), toLog(editorial));
        });
    }

    private TrackInfo toTrackInfo(Track track) {
        UUID trackId = track.getId();
        Album album = track.getAlbum();
        TrackPlacement placement = graphService.getTrackPlacement(trackId);
        return new TrackInfo(
            trackId,
            track.getName(),
            album.getArtists().stream().map(Artist::getName).toList(),
            album.getId(),
            album.getName(),
            album.getReleaseYear(),
            placement == null ? null : placement.trackNumber(),
            formatDuration(track.getDurationMs()),
            track.getVocalProfile(),
            track.getEnergy(),
            track.getAccessibility(),
            track.getMoodIntensity(),
            track.getTempoFeel(),
            track.getCompositionType(),
            codesOf(graphService.getTrackStyles(trackId)),
            codesOf(graphService.getTrackMoods(trackId)),
            codesOf(graphService.getTrackContexts(trackId)),
            codesOf(graphService.getTrackRhythms(trackId)),
            codesOf(graphService.getTrackFeaturedInstruments(trackId)),
            graphService.getTrackPerformers(trackId)
        );
    }

    private Log toLog(TrackEditorial editorial) {
        return new Log(
            editorial.getTitle(),
            editorial.getLogNumber(),
            editorial.getDek(),
            editorial.getByline(),
            editorial.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            editorial.getBlocks().stream().map(TrackLogReader::toBlock).toList()
        );
    }

    private static Block toBlock(EditorialBlock block) {
        return new Block(block.getType(), block.getContentCategory(), block.getSubhead(), block.getText());
    }

    /** Codes, not labels — the same vocabulary the model already filters with in GRAPH_FILTER. */
    private static List<String> codesOf(List<VocabularyTag> tags) {
        return tags.stream().map(VocabularyTag::code).toList();
    }

    /** {@code m:ss}, which reads as a length; raw milliseconds don't. */
    private static String formatDuration(Integer durationMs) {
        if (durationMs == null) {
            return null;
        }
        long totalSeconds = durationMs / 1000;
        return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    /** A track and its log, as {@link EditorialContentTool} hands them to the model. */
    public record TrackLog(TrackInfo track, Log log) {
    }

    /**
     * The track itself. {@code entityId}/{@code entityName} are named like every
     * other tool's; {@code albumId} and each performer's {@code artistId} are there
     * so the model can scope a later GRAPH_FILTER without resolving them by name.
     */
    public record TrackInfo(
        UUID entityId,
        String entityName,
        List<String> artists,
        UUID albumId,
        String album,
        Integer albumReleaseYear,
        Integer trackNumber,
        String duration,
        VocalProfile vocalProfile,
        Level energy,
        Level accessibility,
        Level moodIntensity,
        TempoFeel tempoFeel,
        CompositionType compositionType,
        List<String> styles,
        List<String> moods,
        List<String> contexts,
        List<String> rhythms,
        List<String> featuredInstruments,
        List<TrackPerformerEntry> performers
    ) {
    }

    /** The log's header and body; {@code blocks} are in reading order. */
    public record Log(
        String title, String logNumber, String dek, JazzlogsCharacter writtenBy, String publishedOn, List<Block> blocks
    ) {
    }

    /** One paragraph, lead, or quote of the log. */
    public record Block(EditorialBlockType type, BlockContentCategory contentCategory, String subhead, String text) {
    }
}
