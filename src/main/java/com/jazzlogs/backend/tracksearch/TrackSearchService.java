package com.jazzlogs.backend.tracksearch;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;

import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.editorial.EditorialBlockRepository;
import com.jazzlogs.backend.editorial.EditorialBlockRepository.ClosestPassageRow;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TaggedTrack;
import com.jazzlogs.backend.graph.VocabularyDimension;
import com.jazzlogs.backend.listen.ListenService;
import com.jazzlogs.backend.track.TrackRepository;
import com.jazzlogs.backend.track.TrackRepository.TrackCardRow;
import com.jazzlogs.backend.tracksearch.TrackCandidate.Passage;

import lombok.AllArgsConstructor;

/**
 * Finds tracks worth recommending. One search, two ways of knowing a track,
 * each used for what it is good at:
 * <ul>
 *   <li>the <b>graph</b> knows how a track is tagged and who plays on it — it
 *       decides which tracks are <em>eligible</em>;</li>
 *   <li>the <b>logs</b> know what was actually written about it — a search
 *       phrase is compared against them to decide the <em>order</em>.</li>
 * </ul>
 * So tags and scope narrow the catalog down to a pool, and the phrase, when
 * there is one, ranks that pool by meaning. With only tags or scope, the pool
 * keeps the graph's own order (most tags matched first); with only a phrase,
 * the whole catalog is ranked by meaning.
 *
 * <p>Ranking by meaning is semantic search over the logs' blocks: the phrase
 * is embedded and each track is scored by its closest block. Within a pool
 * that is done exactly; across the whole catalog it goes through the vector
 * index, so neither path gets slower as the catalog grows.
 */
@Service
@AllArgsConstructor
public class TrackSearchService {

    /** How many tagged tracks are kept as the pool a phrase then ranks — wide enough that ranking by meaning has real choices. */
    static final int POOL_SIZE = 30;

    /** How many candidates a search returns: enough to choose between, few enough to actually weigh. */
    static final int MAX_CANDIDATES = 8;

    /**
     * How many of the nearest blocks the vector index shortlists when a phrase
     * is ranked against the whole catalog. A log has half a dozen blocks that
     * can all land in it, so this is well over {@link #MAX_CANDIDATES} times
     * that — see {@code EditorialBlockRepository#findClosestPassages}.
     */
    static final int INDEX_SHORTLIST = 100;

    private final GraphService graphService;
    private final EditorialBlockRepository editorialBlockRepository;
    private final TrackRepository trackRepository;
    private final EmbeddingService embeddingService;
    private final ListenService listenService;

    /**
     * @param userId who the search is for — only used to mark what they have already listened to
     * @return up to {@link #MAX_CANDIDATES} tracks, best match first; empty if nothing matched
     * @throws IllegalArgumentException if {@code criteria} has nothing to search by
     */
    @Transactional(readOnly = true)
    public List<TrackCandidate> search(TrackSearchCriteria criteria, UUID userId) {
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException("Nothing to search by: give tags, an album or artist, or a phrase to look for");
        }
        return describe(rank(criteria), userId);
    }

    // --- ranking: which tracks, in what order, and why ---

    private List<Ranked> rank(TrackSearchCriteria criteria) {
        if (!criteria.hasTags() && !criteria.hasScope()) {
            return closestPassages(criteria.lookingFor()).stream().map(Ranked::byMeaning).toList();
        }

        List<TaggedTrack> pool = graphService.findTracksByTags(criteria.tagCodes(), criteria.albumId(), criteria.artistId(), POOL_SIZE);
        if (pool.isEmpty() || !criteria.hasPhrase()) {
            return pool.stream().limit(MAX_CANDIDATES).map(Ranked::byTags).toList();
        }

        Map<UUID, TaggedTrack> pooled = pool.stream().collect(Collectors.toMap(TaggedTrack::trackId, Function.identity()));
        return closestPassagesAmong(criteria.lookingFor(), pooled.keySet()).stream()
            .map(row -> new Ranked(row.getTrackId(), pooled.get(row.getTrackId()).matchedTags(), toPassage(row)))
            .toList();
    }

    /** Whole catalog: approximate, through the vector index, so it stays fast however many logs there are. */
    private List<ClosestPassageRow> closestPassages(String phrase) {
        editorialBlockRepository.widenVectorIndexSearch(INDEX_SHORTLIST);
        return editorialBlockRepository.findClosestPassages(embed(phrase), INDEX_SHORTLIST, MAX_CANDIDATES);
    }

    /** A known set of tracks: exact, since the set is already small. */
    private List<ClosestPassageRow> closestPassagesAmong(String phrase, Collection<UUID> trackIds) {
        return editorialBlockRepository.findClosestPassagesAmong(embed(phrase), trackIds, MAX_CANDIDATES);
    }

    /** The phrase as the pgvector literal the passage queries compare against. */
    private String embed(String phrase) {
        return new PGvector(embeddingService.embed(phrase)).getValue();
    }

    private static Passage toPassage(ClosestPassageRow row) {
        double similarity = Math.round(row.getSimilarity() * 100) / 100.0;
        return new Passage(BlockContentCategory.valueOf(row.getCategory()), row.getText(), similarity);
    }

    // --- describing: what the caller needs to know about each ranked track ---

    /**
     * Two batched lookups for the whole result, whatever its size. A ranked
     * track with no row of its own (still in the graph, gone from the
     * catalog) is dropped rather than returned half-described.
     */
    private List<TrackCandidate> describe(List<Ranked> ranked, UUID userId) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<UUID> trackIds = ranked.stream().map(Ranked::trackId).toList();
        Map<UUID, TrackCardRow> cards = trackRepository.findCards(trackIds).stream()
            .collect(Collectors.toMap(TrackCardRow::getId, Function.identity()));
        Set<UUID> listened = listenService.getListenedTrackIds(userId, trackIds);

        return ranked.stream()
            .filter(track -> cards.containsKey(track.trackId()))
            .map(track -> toCandidate(track, cards.get(track.trackId()), listened.contains(track.trackId())))
            .toList();
    }

    private static TrackCandidate toCandidate(Ranked track, TrackCardRow card, boolean alreadyListened) {
        return new TrackCandidate(
            track.trackId(),
            card.getName(),
            card.getArtistFullName(),
            card.getAlbumName(),
            card.getWrittenBy() == null ? null : JazzlogsCharacter.valueOf(card.getWrittenBy()),
            track.matchedTags(),
            track.closestPassage(),
            alreadyListened
        );
    }

    /** A track's place in the result and the evidence for it, before it is looked up for display. */
    private record Ranked(UUID trackId, Map<VocabularyDimension, List<String>> matchedTags, Passage closestPassage) {

        Ranked {
            Objects.requireNonNull(trackId);
        }

        static Ranked byTags(TaggedTrack tagged) {
            return new Ranked(tagged.trackId(), tagged.matchedTags(), null);
        }

        static Ranked byMeaning(ClosestPassageRow row) {
            return new Ranked(row.getTrackId(), Map.of(), toPassage(row));
        }
    }
}
