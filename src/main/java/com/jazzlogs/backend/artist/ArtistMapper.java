package com.jazzlogs.backend.artist;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.jazzlogs.backend.artist.dto.UpdateArtistRequest;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ArtistMapper {

    void applyPatch(UpdateArtistRequest request, @MappingTarget Artist artist);
}
