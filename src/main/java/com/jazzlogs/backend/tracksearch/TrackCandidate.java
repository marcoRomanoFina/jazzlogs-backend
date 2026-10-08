package com.jazzlogs.backend.tracksearch;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jazzlogs.backend.character.JazzlogsCharacter;
import com.jazzlogs.backend.editorial.BlockContentCategory;
import com.jazzlogs.backend.graph.VocabularyDimension;

/**
 * One track a search came back with — enough to decide between candidates
 * without opening any log: what it is, who wrote about it, and why it
 * matched.
 *
 * @param entityId        the track's catalog id
 * @param entityName      the track's name
 * @param artistFullName  the album's credited artists, joined in credited order
 * @param album           the album it is on
 * @param writtenBy       the narrator who wrote its log
 * @param matchedTags     the requested vocabulary codes it carries, per dimension;
 *                        empty if the search asked for none
 * @param closestPassage  the part of its log closest to the search phrase;
 *                        {@code null} if the search had no phrase
 * @param alreadyListened whether the user this search is for has listened to it
 */
public record TrackCandidate(
    UUID entityId,
    String entityName,
    String artistFullName,
    String album,
    JazzlogsCharacter writtenBy,
    Map<VocabularyDimension, List<String>> matchedTags,
    Passage closestPassage,
    boolean alreadyListened
) {

    /**
     * A block of a log and how close it is to the search phrase.
     *
     * @param similarity cosine similarity rounded to two decimals, higher is closer
     */
    public record Passage(BlockContentCategory category, String text, double similarity) {
    }
}
