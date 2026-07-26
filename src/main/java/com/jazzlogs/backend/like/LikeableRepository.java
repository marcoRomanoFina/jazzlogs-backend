package com.jazzlogs.backend.like;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

// Everything LikeService needs from a likeable entity's repository: existence
// checks (existsById, from JpaRepository) and atomic counter updates (from
// LikeCountable). Every repository backing a LikeableEntityType implements this,
// so LikeService can dispatch existence-checking AND counting through the same
// Map<LikeableEntityType, LikeableRepository<?>> lookup.
//
// @NoRepositoryBean: this is a base interface to extend, not a concrete
// repository — without it, Spring Data tries to instantiate a proxy for
// LikeableRepository<T> itself with T unresolved and fails at boot.
@NoRepositoryBean
public interface LikeableRepository<T> extends JpaRepository<T, UUID>, LikeCountable {
}
