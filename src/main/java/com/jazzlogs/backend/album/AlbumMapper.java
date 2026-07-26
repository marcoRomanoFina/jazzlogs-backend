package com.jazzlogs.backend.album;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.jazzlogs.backend.album.dto.UpdateAlbumRequest;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AlbumMapper {

    // "tracks" has no setter but is picked up as a mutable-collection target by
    // MapStruct anyway; UpdateAlbumRequest never carries track data, so ignore it.
    @Mapping(target = "tracks", ignore = true)
    void applyPatch(UpdateAlbumRequest request, @MappingTarget Album album);
}
