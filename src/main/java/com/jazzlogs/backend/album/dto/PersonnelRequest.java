package com.jazzlogs.backend.album.dto;

import java.util.List;
import java.util.UUID;

import com.jazzlogs.backend.album.PersonnelRole;

public record PersonnelRequest(UUID artistId, PersonnelRole role, List<String> instruments) {
}
