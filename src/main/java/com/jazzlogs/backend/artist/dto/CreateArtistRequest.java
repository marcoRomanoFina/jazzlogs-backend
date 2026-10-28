package com.jazzlogs.backend.artist.dto;

// spotifyArtistId is the normal path — name/spotifyUrl/imageUrl all come from
// Spotify, and re-posting the same id upserts in place (see
// ArtistService.createOrUpdateArtist). It's optional, not @NotBlank: some
// personnel — mostly older sidemen — were never on Spotify at all, so
// spotifyArtistId absent + name present is the manual-entry fallback for
// those. Exactly one of the two must be given; validated in the service,
// not here, since "at least one of X or Y" isn't expressible with a single
// Bean Validation annotation.
public record CreateArtistRequest(String spotifyArtistId, String name) {
}
