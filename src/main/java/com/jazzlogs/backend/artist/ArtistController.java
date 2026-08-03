package com.jazzlogs.backend.artist;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.dto.ArtistDetailDto;
import com.jazzlogs.backend.artist.dto.CreateArtistRequest;
import com.jazzlogs.backend.artist.dto.SimilarArtistRequest;
import com.jazzlogs.backend.editorial.ArtistEditorial;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialDto;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.track.dto.InstrumentTagRequest;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/artists")
@AllArgsConstructor
public class ArtistController {

    private final ArtistService artistService;
    private final EditorialService editorialService;

    @GetMapping("/{id}")
    public ArtistDetailDto getArtist(@PathVariable UUID id) {
        return artistService.getArtistDetail(id);
    }

    // Upserts by spotifyArtistId — posting the same artist again updates it in
    // place instead of creating a duplicate. See ArtistService.createOrUpdateArtist.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ArtistDetailDto> createOrUpdateArtist(@Valid @RequestBody CreateArtistRequest request) {
        Artist artist = artistService.createOrUpdateArtist(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(artistService.getArtistDetail(artist.getId()));
    }

    @PostMapping("/{id}/instrument")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setPrimaryInstrument(@PathVariable UUID id, @RequestBody InstrumentTagRequest request) {
        artistService.setPrimaryInstrument(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/styles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addStyle(@PathVariable UUID id, @RequestBody StyleTagRequest request) {
        artistService.addStyle(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/contexts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addContext(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        artistService.addContext(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/similar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addSimilarArtist(@PathVariable UUID id, @RequestBody SimilarArtistRequest request) {
        artistService.addSimilarArtist(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/editorial")
    @PreAuthorize("hasRole('ADMIN')")
    public ArtistEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody ArtistEditorialRequest request) {
        ArtistEditorial editorial = editorialService.upsertArtistEditorial(id, request);
        return editorialService.toDto(editorial);
    }
}
