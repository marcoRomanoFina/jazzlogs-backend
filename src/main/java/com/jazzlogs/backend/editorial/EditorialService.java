package com.jazzlogs.backend.editorial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.ai.document.Document;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialDto;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.editorial.dto.BlockRequest;
import com.jazzlogs.backend.editorial.dto.CatalogueEditorialDto;
import com.jazzlogs.backend.editorial.dto.EditorialBlockDto;
import com.jazzlogs.backend.editorial.dto.EditorialSummaryDto;
import com.jazzlogs.backend.editorial.dto.LastLogDto;
import com.jazzlogs.backend.editorial.dto.LastLogTrackDto;
import com.jazzlogs.backend.editorial.dto.RecentAlbumEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.graph.TrackPlacement;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/**
 * Owns editorial content (dek/byline/blocks) for AlbumEditorial, TrackEditorial
 * and ArtistEditorial alike — the class-table-inheritance base (Editorial) is
 * what makes a single upsertBlocks() work for any of the three owners.
 */
@Service
@AllArgsConstructor
public class EditorialService {

    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final ArtistRepository artistRepository;
    private final AlbumEditorialRepository albumEditorialRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final ArtistEditorialRepository artistEditorialRepository;
    private final EditorialRepository editorialRepository;
    private final EditorialSummaryRepository editorialSummaryRepository;
    private final EmbeddingService embeddingService;
    private final LikeService likeService;
    private final GraphService graphService;
    private final EntityManager entityManager;

    /**
     * Marks {@code editorialId} as THE featurated one, unfeaturating
     * whichever one (if any) held that spot before. {@code
     * idx_editorials_only_one_featured} (see V18) is what actually
     * guarantees at most one stays featured under concurrent calls —
     * clearFeaturated()+markFeaturated() alone can't: two overlapping calls
     * can each see nothing featured, clear nothing, then both mark a
     * different row true. The unique index turns that into a thrown
     * exception here instead of silently leaving two rows featured.
     *
     * @param editorialId must already exist — a base {@link Editorial} id,
     *                    valid regardless of which concrete subtype it is
     * @throws ResponseStatusException 409 if a concurrent call already
     *                                  featured a different editorial
     */
    @Transactional
    public void setFeaturated(UUID editorialId) {
        if (!editorialRepository.existsById(editorialId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Editorial not found: " + editorialId);
        }
        editorialRepository.clearFeaturated();
        try {
            editorialRepository.markFeaturated(editorialId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Another editorial was just featured concurrently — try again", e
            );
        }
    }

    /**
     * The archive's free-form search/filter/paginate listing, across every
     * owner type — backs {@link EditorialController#list}.
     *
     * @param type          restricts to one owner type, or {@code null} for every type
     * @param q             free-text search, matched case-insensitively as
     *                      a substring against title/owner name; {@code
     *                      null}/blank means no filter
     * @param currentUserId used only to compute each result's {@code likedByCurrentUser}
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<CatalogueEditorialDto> listEditorials(
        EditorialOwnerType type, String q, Pageable pageable, UUID currentUserId
    ) {
        String pattern = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        Page<CatalogueEditorialRow> page = editorialSummaryRepository.searchLean(type, pattern, pageable);

        List<UUID> ids = page.getContent().stream().map(CatalogueEditorialRow::id).toList();
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, ids);

        return page.map(row -> toCatalogueEditorialDto(row, liked.contains(row.id())));
    }

    private CatalogueEditorialDto toCatalogueEditorialDto(CatalogueEditorialRow row, boolean likedByCurrentUser) {
        return new CatalogueEditorialDto(
            row.id(),
            row.type(),
            row.ownerId(),
            row.ownerName(),
            row.ownerImageUrl(),
            row.contextName(),
            row.contextId(),
            row.title(),
            row.dek(),
            row.byline(),
            row.createdAt(),
            row.logNumber(),
            row.likeCount(),
            likedByCurrentUser
        );
    }

    /** Not exposed to any client — the fixed size of the "Recently filed" strip. */
    static final int RECENT_ALBUMS_LIMIT = 10;

    /**
     * "Recently filed" — the {@link #RECENT_ALBUMS_LIMIT} most recently
     * created album editorials, newest first.
     *
     * @param currentUserId used only to compute each result's {@code likedByCurrentUser}
     * @return up to {@link #RECENT_ALBUMS_LIMIT} album editorials
     */
    @Transactional(readOnly = true)
    public List<RecentAlbumEditorialDto> getRecentAlbumEditorials(UUID currentUserId) {
        List<RecentAlbumEditorialRow> rows =
            editorialSummaryRepository.findRecentAlbums(PageRequest.of(0, RECENT_ALBUMS_LIMIT));

        List<UUID> ids = rows.stream().map(RecentAlbumEditorialRow::id).toList();
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, ids);

        return rows.stream().map(row -> toRecentAlbumEditorialDto(row, liked.contains(row.id()))).toList();
    }

    private RecentAlbumEditorialDto toRecentAlbumEditorialDto(RecentAlbumEditorialRow row, boolean likedByCurrentUser) {
        return new RecentAlbumEditorialDto(
            row.id(),
            row.albumId(),
            row.artistId(),
            row.imageUrl(),
            row.title(),
            row.albumName(),
            row.artistName(),
            row.dek(),
            row.byline(),
            row.logNumber(),
            row.likeCount(),
            likedByCurrentUser
        );
    }

    /**
     * "The Last Log" — the archive's other hero slot: the single most
     * recently published album editorial, together with every track
     * editorial on that album. {@code trackNumber} comes from Neo4j, not
     * Postgres — see {@link GraphService#getTrackPlacements} — so a Neo4j
     * outage fails this call entirely rather than degrading to unordered
     * tracks; known limitation, not yet worth the added complexity to fix.
     *
     * @param currentUserId used to compute {@code likedByCurrentUser} on
     *                       both the album editorial and each track editorial
     * @return empty if no album editorial exists yet
     */
    @Transactional(readOnly = true)
    public Optional<LastLogDto> getLastLog(UUID currentUserId) {
        return editorialSummaryRepository.findLatestAlbum(PageRequest.of(0, 1)).stream()
            .findFirst()
            .map(row -> toLastLogDto(row, currentUserId));
    }

    private LastLogDto toLastLogDto(LastLogEditorialRow row, UUID currentUserId) {
        List<TrackEditorialRepository.TrackTeaserRow> trackRows =
            trackEditorialRepository.findTeasersByAlbumId(row.albumId());

        Map<UUID, TrackPlacement> placements = graphService.getTrackPlacements(row.albumId()).stream()
            .collect(Collectors.toMap(TrackPlacement::trackId, placement -> placement));

        List<UUID> editorialIds = new ArrayList<>(
            trackRows.stream().map(TrackEditorialRepository.TrackTeaserRow::getEditorialId).toList()
        );
        editorialIds.add(row.id());
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, editorialIds);

        List<LastLogTrackDto> tracks = trackRows.stream()
            .map(t -> {
                TrackPlacement placement = placements.get(t.getTrackId());
                return new LastLogTrackDto(
                    t.getEditorialId(),
                    placement == null ? null : placement.trackNumber(),
                    t.getTitle(),
                    t.getDek(),
                    t.getLikeCount(),
                    liked.contains(t.getEditorialId())
                );
            })
            .sorted(Comparator.comparing(LastLogTrackDto::trackNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        return new LastLogDto(
            row.id(), row.title(), row.artistName(), row.dek(), row.byline(),
            row.releaseYear(), row.postedAt(), row.imageUrl(), row.likeCount(),
            liked.contains(row.id()), tracks
        );
    }

    /** {@code COUNT(*)} only — no content, no joins, no like-status lookup. See {@link #listEditorials} for the filtered/paginated version. */
    @Transactional(readOnly = true)
    public long countEditorials() {
        return editorialSummaryRepository.count();
    }

    /**
     * @param currentUserId used only to compute {@code likedByCurrentUser} on the result
     * @return the featurated editorial, empty if none is set — {@link
     *         EditorialController#featured} turns that into the 404
     */
    @Transactional(readOnly = true)
    public Optional<EditorialSummaryDto> getFeatured(UUID currentUserId) {
        return editorialSummaryRepository.findFirstByFeaturatedTrue()
            .map(summary -> toEditorialSummaryDto(
                summary, likeService.hasUserLiked(currentUserId, LikeableEntityType.EDITORIAL, summary.getId())
            ));
    }

    private EditorialSummaryDto toEditorialSummaryDto(EditorialSummary summary, boolean likedByCurrentUser) {
        return new EditorialSummaryDto(
            summary.getId(),
            summary.getOwnerType(),
            summary.getOwnerId(),
            summary.getOwnerName(),
            summary.getOwnerImageUrl(),
            summary.getTitle(),
            summary.getDek(),
            summary.getByline(),
            summary.getCreatedAt(),
            summary.getLikeCount(),
            likedByCurrentUser,
            summary.isFeaturated(),
            summary.getContextName(),
            summary.getReleaseYear(),
            summary.getPreviewText(),
            summary.getContextId()
        );
    }

    @Transactional
    public AlbumEditorial upsertAlbumEditorial(UUID albumId, AlbumEditorialRequest request) {
        Album album = getAlbumOrThrow(albumId);

        AlbumEditorial editorial = albumEditorialRepository.findByAlbumId(albumId)
            .orElseGet(() -> new AlbumEditorial(album));

        editorial.update(request.title(), request.dek(), request.byline());
        AlbumEditorial saved = saveWithUniqueTitle(() -> albumEditorialRepository.save(editorial));

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    @Transactional
    public TrackEditorial upsertTrackEditorial(UUID trackId, TrackEditorialRequest request) {
        Track track = getTrackOrThrow(trackId);

        TrackEditorial editorial = trackEditorialRepository.findByTrackId(trackId)
            .orElseGet(() -> new TrackEditorial(track));
        editorial.update(request.title(), request.dek(), request.byline());
        TrackEditorial saved = saveWithUniqueTitle(() -> trackEditorialRepository.save(editorial));

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    @Transactional
    public ArtistEditorial upsertArtistEditorial(UUID artistId, ArtistEditorialRequest request) {
        Artist artist = getArtistOrThrow(artistId);

        ArtistEditorial editorial = artistEditorialRepository.findByArtistId(artistId)
            .orElseGet(() -> new ArtistEditorial(artist));

        editorial.update(request.title(), request.dek(), request.byline());
        ArtistEditorial saved = saveWithUniqueTitle(() -> artistEditorialRepository.save(editorial));

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    /**
     * Flushes right after {@code save}, not left to commit time — {@code
     * uk_editorials_title} (see V20) is what actually rejects a reused
     * title, but {@code JpaRepository.save} on a new entity only schedules
     * the INSERT for flush time by default; without an explicit flush here,
     * the constraint violation would surface outside this method (at
     * commit, or worse, only after the caller already spent an OpenAI
     * embedding call in {@link #upsertBlocks}) instead of being caught
     * below. That flush also means the violation reaches us as the raw
     * {@code ConstraintViolationException} Hibernate throws, not Spring's
     * {@code DataIntegrityViolationException} — Spring's exception
     * translation only wraps repository method calls, not a direct {@code
     * EntityManager.flush()}, so both are caught here.
     */
    private <T extends Editorial> T saveWithUniqueTitle(Supplier<T> save) {
        try {
            T saved = save.get();
            entityManager.flush();
            return saved;
        } catch (DataIntegrityViolationException | ConstraintViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another editorial already uses this title", e);
        }
    }

    @Transactional
    public List<EditorialBlock> upsertBlocks(Editorial editorial, List<BlockRequest> blockRequests) {
        List<BlockRequest> requests = blockRequests == null ? List.of() : blockRequests;

        Map<String, Object> baseMetadata = buildBaseMetadata(editorial);

        List<Document> documents = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            BlockRequest request = requests.get(i);

            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put("blockType", request.type().name());
            metadata.put("contentCategory", request.contentCategory().name());
            metadata.put("position", i);

            documents.add(new Document(request.text(), metadata));
        }

        // Generate every embedding FIRST, in one batch call — if OpenAI fails, we
        // bail out here and nothing about this editorial's blocks has been touched.
        // Index i lines up with both requests and documents below.
        List<float[]> embeddings = embeddingService.embedBatch(documents.stream().map(Document::getText).toList());

        editorial.getBlocks().clear();
        for (int i = 0; i < requests.size(); i++) {
            BlockRequest request = requests.get(i);

            editorial.getBlocks().add(new EditorialBlock(
                editorial,
                i,
                request.type(),
                request.subhead(),
                request.text(),
                request.contentCategory(),
                embeddings.get(i),
                documents.get(i).getMetadata()
            ));
        }

        return editorial.getBlocks();
    }

    public AlbumEditorialDto getAlbumEditorialDto(UUID albumId, UUID currentUserId) {
        return albumEditorialRepository.findByAlbumId(albumId)
            .map(editorial -> toDto(editorial, currentUserId))
            .orElse(null);
    }

    public TrackEditorialDto getTrackEditorialDto(UUID trackId) {
        return trackEditorialRepository.findByTrackId(trackId)
            .map(this::toDto)
            .orElse(null);
    }

    public Map<UUID, TrackEditorialDto> getTrackEditorialDtosByAlbumId(UUID albumId) {
        return trackEditorialRepository.findByTrackAlbumId(albumId).stream()
            .collect(Collectors.toMap(te -> te.getTrack().getId(), this::toDto));
    }

    public ArtistEditorialDto getArtistEditorialDto(UUID artistId) {
        return artistEditorialRepository.findByArtistId(artistId)
            .map(this::toDto)
            .orElse(null);
    }

    public AlbumEditorialDto toDto(AlbumEditorial editorial, UUID currentUserId) {
        UUID editorialId = editorial.getId();
        return new AlbumEditorialDto(
            editorialId,
            editorial.getTitle(),
            editorial.getDek(),
            editorial.getByline(),
            blocksOf(editorial),
            editorial.getLikeCount(),
            likeService.hasUserLiked(currentUserId, LikeableEntityType.EDITORIAL, editorialId)
        );
    }

    public TrackEditorialDto toDto(TrackEditorial editorial) {
        return new TrackEditorialDto(
            editorial.getTitle(), editorial.getDek(), editorial.getByline(), blocksOf(editorial)
        );
    }

    public ArtistEditorialDto toDto(ArtistEditorial editorial) {
        return new ArtistEditorialDto(
            editorial.getTitle(), editorial.getDek(), editorial.getByline(), blocksOf(editorial)
        );
    }

    private List<EditorialBlockDto> blocksOf(Editorial editorial) {
        return editorial.getBlocks().stream()
            .map(block -> new EditorialBlockDto(
                block.getPosition(),
                block.getType(),
                block.getSubhead(),
                block.getText(),
                block.getContentCategory()
            ))
            .toList();
    }

    private Map<String, Object> buildBaseMetadata(Editorial editorial) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("editorialType", editorial.getClass().getSimpleName());
        metadata.put("editorialId", editorial.getId().toString());

        if (editorial instanceof AlbumEditorial albumEditorial) {
            Album album = albumEditorial.getAlbum();
            metadata.put("albumName", album.getName());
            metadata.put("artistName", album.getArtist().getName());
        } else if (editorial instanceof ArtistEditorial artistEditorial) {
            metadata.put("artistName", artistEditorial.getArtist().getName());
        }

        return metadata;
    }

    private Album getAlbumOrThrow(UUID albumId) {
        return albumRepository.findById(albumId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found: " + albumId));
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }

    private Artist getArtistOrThrow(UUID artistId) {
        return artistRepository.findById(artistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found: " + artistId));
    }
}
