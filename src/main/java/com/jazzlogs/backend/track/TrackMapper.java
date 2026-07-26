package com.jazzlogs.backend.track;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.jazzlogs.backend.track.dto.UpdateTrackRequest;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TrackMapper {

    void applyPatch(UpdateTrackRequest request, @MappingTarget Track track);
}
