package com.jazzlogs.backend.artist;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jazzlogs.backend.album.dto.ContextTagRequest;
import com.jazzlogs.backend.album.dto.StyleTagRequest;
import com.jazzlogs.backend.artist.dto.ArtistHeaderDto;
import com.jazzlogs.backend.artist.dto.CreateArtistRequest;
import com.jazzlogs.backend.artist.dto.SimilarArtistRequest;
import com.jazzlogs.backend.editorial.ArtistEditorial;
import com.jazzlogs.backend.editorial.EditorialService;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialDto;
import com.jazzlogs.backend.editorial.dto.ArtistEditorialRequest;
import com.jazzlogs.backend.track.dto.InstrumentTagRequest;
import com.jazzlogs.backend.user.UserService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/artists")
@AllArgsConstructor
public class ArtistController {

    private final ArtistService artistService;
    private final EditorialService editorialService;
    private final UserService userService;

    /**
     * The artist editorial page's header — see {@link ArtistService#getArtistHeader}.
     *
     * @param id  the artist to load
     * @param jwt the caller, resolved to a user id only for the editorial's like state
     * @return the artist header
     */
    @GetMapping("/{id}")
    public ArtistHeaderDto getArtist(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return artistService.getArtistHeader(id, currentUserId(jwt));
    }

    // Upserts by spotifyArtistId when given — posting the same artist again
    // updates it in place instead of creating a duplicate. Without one, name
    // is required instead: manual entry for artists with no Spotify presence
    // (mostly older sidemen). See ArtistService.createOrUpdateArtist.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ArtistHeaderDto> createOrUpdateArtist(@Valid @RequestBody CreateArtistRequest request, @AuthenticationPrincipal Jwt jwt) {
        Artist artist = artistService.createOrUpdateArtist(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(artistService.getArtistHeader(artist.getId(), currentUserId(jwt)));
    }

    @PostMapping("/{id}/instrument")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setPrimaryInstrument(@PathVariable UUID id, @RequestBody InstrumentTagRequest request) {
        artistService.setPrimaryInstrument(id, request);
        return ResponseEntity.noContent().build();
    }

    // Full replace, not add-one — see StyleTagRequest's comment.
    @PutMapping("/{id}/styles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceStyles(@PathVariable UUID id, @RequestBody StyleTagRequest request) {
        artistService.replaceStyles(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/contexts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replaceContexts(@PathVariable UUID id, @RequestBody ContextTagRequest request) {
        artistService.replaceContexts(id, request);
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
    public ArtistEditorialDto upsertEditorial(@PathVariable UUID id, @Valid @RequestBody ArtistEditorialRequest request, @AuthenticationPrincipal Jwt jwt) {
        ArtistEditorial editorial = editorialService.upsertArtistEditorial(id, request);
        return editorialService.toArtistEditorialDto(editorial, currentUserId(jwt));
    }

    private UUID currentUserId(Jwt jwt) {
        return userService.resolveFromJwt(jwt).getId();
    }
}
