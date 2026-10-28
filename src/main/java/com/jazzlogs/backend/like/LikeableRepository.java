package com.jazzlogs.backend.like;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Everything {@link LikeService} needs from a likeable entity's repository:
 * existence checks (existsById, from JpaRepository) and atomic counter
 * updates (from {@link LikeCountable}). Every repository backing a
 * {@link LikeableEntityType} implements this, so LikeService can dispatch
 * existence-checking AND counting through the same
 * {@code Map<LikeableEntityType, LikeableRepository<?>>} lookup.
 *
 * <p>{@code @NoRepositoryBean}: this is a base interface to extend, not a
 * concrete repository — without it, Spring Data tries to instantiate a proxy
 * for {@code LikeableRepository<T>} itself with T unresolved and fails at boot.
 */
@NoRepositoryBean
public interface LikeableRepository<T> extends JpaRepository<T, UUID>, LikeCountable {
}
