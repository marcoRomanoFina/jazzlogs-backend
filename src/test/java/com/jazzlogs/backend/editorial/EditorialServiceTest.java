package com.jazzlogs.backend.editorial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.artist.Artist;
import com.jazzlogs.backend.artist.ArtistRepository;
import com.jazzlogs.backend.album.Album;
import com.jazzlogs.backend.album.AlbumRepository;
import com.jazzlogs.backend.album.Level;
import com.jazzlogs.backend.album.VocalProfile;
import com.jazzlogs.backend.editorial.dto.AlbumEditorialRequest;

@SpringBootTest
@Transactional
class EditorialServiceTest {

    @Autowired
    private EditorialService editorialService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Test
    void upsertAlbumEditorial_persistsAcrossJoinedInheritanceTables() {
        Artist artist = artistRepository.save(new Artist("Test Artist", null, null, null));
        Album album = albumRepository.save(new Album(
            artist, "Test Album", null, null, null, 2024, 1, "LOG-1", "LABEL-1",
            VocalProfile.INSTRUMENTAL, Level.MEDIUM, Level.MEDIUM, Level.MEDIUM, null, null
        ));

        AlbumEditorialRequest request = new AlbumEditorialRequest("A Title", "A dek", "A byline", 5, List.of());

        AlbumEditorial saved = editorialService.upsertAlbumEditorial(album.getId(), request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("A Title");
    }
}
