package com.jazzlogs.backend.editorial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.ai.document.Document;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.editorial.dto.BlockRequest;
import com.jazzlogs.backend.editorial.dto.EditorialBlockDto;
import com.jazzlogs.backend.editorial.dto.FeaturedTrackDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialCatalogueDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
import com.jazzlogs.backend.like.LikeService;
import com.jazzlogs.backend.like.LikeableEntityType;
import com.jazzlogs.backend.storage.ImageStorageService;
import com.jazzlogs.backend.track.Track;
import com.jazzlogs.backend.track.TrackRepository;

import lombok.AllArgsConstructor;

/** Owns a track's editorial content (title/dek/byline/blocks/image) — the only kind of editorial JazzLogs carries. */
@Service
@AllArgsConstructor
public class EditorialService {

    private final TrackRepository trackRepository;
    private final TrackEditorialRepository trackEditorialRepository;
    private final EmbeddingService embeddingService;
    private final LikeService likeService;
    private final ImageStorageService imageStorageService;
    private final EntityManager entityManager;

    /** Unsigned pieces default to the outlet itself rather than requiring an explicit choice. */
    private EditorialByline bylineOrDefault(EditorialByline byline) {
        return byline == null ? EditorialByline.JAZZLOGS : byline;
    }

    @Transactional
    public TrackEditorial upsertTrackEditorial(UUID trackId, TrackEditorialRequest request) {
        Track track = getTrackOrThrow(trackId);

        TrackEditorial editorial = trackEditorialRepository.findByTrackId(trackId)
            .orElseGet(() -> new TrackEditorial(track));
        editorial.update(request.title(), request.dek(), bylineOrDefault(request.byline()), request.logNumber());
        TrackEditorial saved = saveWithUniqueTitle(() -> trackEditorialRepository.save(editorial));

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    /**
     * Uploads this track editorial's cover image — one object per editorial
     * ({@code track-editorials/{trackId}/cover.<ext>}), so re-uploading
     * overwrites the old one instead of leaving it orphaned in storage.
     *
     * @param trackId the track whose editorial to update
     * @param file    the image file (jpeg/png/webp only)
     * @throws ResponseStatusException 404 if the track has no editorial written yet
     */
    @Transactional
    public void setTrackEditorialCoverImage(UUID trackId, MultipartFile file) {
        TrackEditorial editorial = trackEditorialOrThrow(trackId);
        String url = imageStorageService.upload("track-editorials/" + trackId + "/cover", file);
        editorial.updateCoverImageUrl(url);
    }

    /**
     * Uploads this track editorial's principal image — see {@link
     * #setTrackEditorialCoverImage}. One object per editorial ({@code
     * track-editorials/{trackId}/principal.<ext>}).
     *
     * @param trackId the track whose editorial to update
     * @param file    the image file (jpeg/png/webp only)
     * @throws ResponseStatusException 404 if the track has no editorial written yet
     */
    @Transactional
    public void setTrackEditorialPrincipalImage(UUID trackId, MultipartFile file) {
        TrackEditorial editorial = trackEditorialOrThrow(trackId);
        String url = imageStorageService.upload("track-editorials/" + trackId + "/principal", file);
        editorial.updatePrincipalImageUrl(url);
    }

    /**
     * Uploads this track editorial's secondary image — see {@link
     * #setTrackEditorialCoverImage}. One object per editorial ({@code
     * track-editorials/{trackId}/secondary.<ext>}).
     *
     * @param trackId the track whose editorial to update
     * @param file    the image file (jpeg/png/webp only)
     * @throws ResponseStatusException 404 if the track has no editorial written yet
     */
    @Transactional
    public void setTrackEditorialSecondaryImage(UUID trackId, MultipartFile file) {
        TrackEditorial editorial = trackEditorialOrThrow(trackId);
        String url = imageStorageService.upload("track-editorials/" + trackId + "/secondary", file);
        editorial.updateSecondaryImageUrl(url);
    }

    /**
     * Uploads this track editorial's banner image — see {@link
     * #setTrackEditorialCoverImage}. One object per editorial ({@code
     * track-editorials/{trackId}/banner.<ext>}).
     *
     * @param trackId the track whose editorial to update
     * @param file    the image file (jpeg/png/webp only)
     * @throws ResponseStatusException 404 if the track has no editorial written yet
     */
    @Transactional
    public void setTrackEditorialBannerImage(UUID trackId, MultipartFile file) {
        TrackEditorial editorial = trackEditorialOrThrow(trackId);
        String url = imageStorageService.upload("track-editorials/" + trackId + "/banner", file);
        editorial.updateBannerImageUrl(url);
    }

    /**
     * Uploads this track editorial's footer image — see {@link
     * #setTrackEditorialCoverImage}. One object per editorial ({@code
     * track-editorials/{trackId}/footer.<ext>}).
     *
     * @param trackId the track whose editorial to update
     * @param file    the image file (jpeg/png/webp only)
     * @throws ResponseStatusException 404 if the track has no editorial written yet
     */
    @Transactional
    public void setTrackEditorialFooterImage(UUID trackId, MultipartFile file) {
        TrackEditorial editorial = trackEditorialOrThrow(trackId);
        String url = imageStorageService.upload("track-editorials/" + trackId + "/footer", file);
        editorial.updateFooterImageUrl(url);
    }

    private TrackEditorial trackEditorialOrThrow(UUID trackId) {
        return trackEditorialRepository.findByTrackId(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No editorial for track: " + trackId));
    }

    /**
     * Flushes right after {@code save}, not left to commit time — {@code
     * uk_track_editorials_title} is what actually rejects a reused title,
     * but {@code JpaRepository.save} on a new entity only schedules the
     * INSERT for flush time by default; without an explicit flush here, the
     * constraint violation would surface outside this method (at commit, or
     * worse, only after the caller already spent an OpenAI embedding call
     * in {@link #upsertBlocks}) instead of being caught below. That flush
     * also means the violation reaches us as the raw {@code
     * ConstraintViolationException} Hibernate throws, not Spring's {@code
     * DataIntegrityViolationException} — Spring's exception translation
     * only wraps repository method calls, not a direct {@code
     * EntityManager.flush()}, so both are caught here.
     */
    private TrackEditorial saveWithUniqueTitle(Supplier<TrackEditorial> save) {
        try {
            TrackEditorial saved = save.get();
            entityManager.flush();
            return saved;
        } catch (DataIntegrityViolationException | ConstraintViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another editorial already uses this title", e);
        }
    }

    @Transactional
    public List<EditorialBlock> upsertBlocks(TrackEditorial editorial, List<BlockRequest> blockRequests) {
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

    /**
     * The archive's free-form search/browse listing.
     *
     * @param q             free-text search, matched case-insensitively as a
     *                      substring against the editorial's title or its
     *                      track's name; {@code null}/blank means no filter
     * @param currentUserId used only to compute each result's {@code likedByCurrentUser}
     * @return the matching page
     */
    @Transactional(readOnly = true)
    public Page<TrackEditorialCatalogueDto> listEditorials(String q, Pageable pageable, UUID currentUserId) {
        String pattern = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        Page<TrackEditorialCatalogueRow> page = trackEditorialRepository.searchCatalogue(pattern, pageable);

        List<UUID> ids = page.getContent().stream().map(TrackEditorialCatalogueRow::id).toList();
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, ids);

        return page.map(row -> toTrackEditorialCatalogueDto(row, liked.contains(row.id())));
    }

    private TrackEditorialCatalogueDto toTrackEditorialCatalogueDto(TrackEditorialCatalogueRow row, boolean likedByCurrentUser) {
        return new TrackEditorialCatalogueDto(
            row.id(), row.trackId(), row.trackName(), row.trackImageUrl(), row.albumName(), row.albumId(),
            row.title(), row.dek(), row.byline(), row.createdAt(), row.likeCount(), likedByCurrentUser
        );
    }

    /** {@code COUNT(*)} only — no content, no joins, no like-status lookup. See {@link #listEditorials} for the filtered/paginated version. */
    @Transactional(readOnly = true)
    public long countEditorials() {
        return trackEditorialRepository.count();
    }

    public TrackEditorialDto getTrackEditorialDto(UUID trackId) {
        return trackEditorialRepository.findByTrackId(trackId)
            .map(this::toTrackEditorialDto)
            .orElse(null);
    }

    /**
     * Same as {@link #getTrackEditorialDto(UUID)}, but resolves {@code
     * likedByCurrentUser} against a real user instead of defaulting it to
     * {@code false} — see {@code TrackService#getTrackDetail}, the only
     * caller with a real user in scope.
     */
    public TrackEditorialDto getTrackEditorialDto(UUID trackId, UUID currentUserId) {
        return trackEditorialRepository.findByTrackId(trackId)
            .map(editorial -> toTrackEditorialDto(editorial, currentUserId))
            .orElse(null);
    }

    /** For {@code TrackService.setFeatured} — a track needs a {@link TrackEditorial} before it can be featured. */
    public boolean hasTrackEditorial(UUID trackId) {
        return trackEditorialRepository.existsByTrackId(trackId);
    }

    /**
     * "Featured Tracks" — up to {@code TrackService.MAX_FEATURED_TRACKS}
     * admin-curated tracks, any album/artist, newest editorial first.
     *
     * @param currentUserId used only to compute each result's {@code likedByCurrentUser}
     */
    @Transactional(readOnly = true)
    public List<FeaturedTrackDto> getFeaturedTracks(UUID currentUserId) {
        List<FeaturedTrackRow> rows = trackEditorialRepository.findFeatured();

        List<UUID> ids = rows.stream().map(FeaturedTrackRow::id).toList();
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, ids);

        return rows.stream().map(row -> toFeaturedTrackDto(row, liked.contains(row.id()))).toList();
    }

    private FeaturedTrackDto toFeaturedTrackDto(FeaturedTrackRow row, boolean likedByCurrentUser) {
        return new FeaturedTrackDto(
            row.id(), row.title(), row.dek(), row.byline(),
            row.trackName(), row.imageUrl(), row.albumName(), row.albumId(), row.createdAt(), row.likeCount(),
            likedByCurrentUser
        );
    }

    public TrackEditorialDto toTrackEditorialDto(TrackEditorial editorial) {
        return toTrackEditorialDto(editorial, null);
    }

    /** currentUserId is optional — {@code null} skips the like check entirely instead of querying for a user that was never given. */
    private TrackEditorialDto toTrackEditorialDto(TrackEditorial editorial, UUID currentUserId) {
        boolean likedByCurrentUser = currentUserId != null
            && likeService.hasUserLiked(currentUserId, LikeableEntityType.EDITORIAL, editorial.getId());

        return new TrackEditorialDto(
            editorial.getTitle(), editorial.getLogNumber(), editorial.getDek(), editorial.getByline(),
            editorial.getCoverImageUrl(), editorial.getPrincipalImageUrl(), editorial.getSecondaryImageUrl(),
            editorial.getBannerImageUrl(), editorial.getFooterImageUrl(),
            editorial.getLikeCount(), likedByCurrentUser, editorial.getCreatedAt(),
            blocksOf(editorial)
        );
    }

    private List<EditorialBlockDto> blocksOf(TrackEditorial editorial) {
        return editorial.getBlocks().stream()
            .map(this::toEditorialBlockDto)
            .toList();
    }

    private EditorialBlockDto toEditorialBlockDto(EditorialBlock block) {
        return new EditorialBlockDto(
            block.getPosition(), block.getType(), block.getSubhead(), block.getText(), block.getContentCategory()
        );
    }

    private Map<String, Object> buildBaseMetadata(TrackEditorial editorial) {
        Track track = editorial.getTrack();
        Album album = track.getAlbum();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("editorialType", "TrackEditorial");
        metadata.put("editorialId", editorial.getId().toString());
        metadata.put("trackName", track.getName());
        metadata.put("albumName", album.getName());
        metadata.put("artistName", album.getArtist().getName());
        return metadata;
    }

    private Track getTrackOrThrow(UUID trackId) {
        return trackRepository.findById(trackId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found: " + trackId));
    }
}
