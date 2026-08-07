package com.jazzlogs.backend.editorial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
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
import com.jazzlogs.backend.editorial.dto.EditorialBlockDto;
import com.jazzlogs.backend.editorial.dto.EditorialSummaryDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialDto;
import com.jazzlogs.backend.editorial.dto.TrackEditorialRequest;
import com.jazzlogs.backend.embedding.EmbeddingService;
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

    @Transactional
    public void setFeaturated(UUID editorialId) {
        if (!editorialRepository.existsById(editorialId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Editorial not found: " + editorialId);
        }
        editorialRepository.clearFeaturated();
        editorialRepository.markFeaturated(editorialId);
    }

    @Transactional(readOnly = true)
    public Page<EditorialSummaryDto> listEditorials(
        EditorialOwnerType type, String q, Pageable pageable, UUID currentUserId
    ) {
        String pattern = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        Page<EditorialSummary> page = editorialSummaryRepository.search(type, pattern, pageable);

        List<UUID> ids = page.getContent().stream().map(EditorialSummary::getId).toList();
        Set<UUID> liked = likeService.hasUserLikedBatch(currentUserId, LikeableEntityType.EDITORIAL, ids);

        return page.map(summary -> toSummaryDto(summary, liked.contains(summary.getId())));
    }

    @Transactional(readOnly = true)
    public EditorialSummaryDto getFeatured(UUID currentUserId) {
        return editorialSummaryRepository.findFirstByFeaturatedTrue()
            .map(summary -> toSummaryDto(
                summary, likeService.hasUserLiked(currentUserId, LikeableEntityType.EDITORIAL, summary.getId())
            ))
            .orElse(null);
    }

    private EditorialSummaryDto toSummaryDto(EditorialSummary summary, boolean likedByCurrentUser) {
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
            summary.getPreviewText()
        );
    }

    @Transactional
    public AlbumEditorial upsertAlbumEditorial(UUID albumId, AlbumEditorialRequest request) {
        Album album = getAlbumOrThrow(albumId);

        AlbumEditorial editorial = albumEditorialRepository.findByAlbumId(albumId)
            .orElseGet(() -> new AlbumEditorial(album));

        editorial.update(request.title(), request.dek(), request.byline());
        AlbumEditorial saved = albumEditorialRepository.save(editorial);

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    @Transactional
    public TrackEditorial upsertTrackEditorial(UUID trackId, TrackEditorialRequest request) {
        Track track = getTrackOrThrow(trackId);

        TrackEditorial editorial = trackEditorialRepository.findByTrackId(trackId)
            .orElseGet(() -> new TrackEditorial(track));
        editorial.update(request.title(), request.dek(), request.byline());
        TrackEditorial saved = trackEditorialRepository.save(editorial);

        upsertBlocks(saved, request.blocks());

        return saved;
    }

    @Transactional
    public ArtistEditorial upsertArtistEditorial(UUID artistId, ArtistEditorialRequest request) {
        Artist artist = getArtistOrThrow(artistId);

        ArtistEditorial editorial = artistEditorialRepository.findByArtistId(artistId)
            .orElseGet(() -> new ArtistEditorial(artist));

        editorial.update(request.title(), request.dek(), request.byline());
        ArtistEditorial saved = artistEditorialRepository.save(editorial);

        upsertBlocks(saved, request.blocks());

        return saved;
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
