package com.jazzlogs.backend.editorial;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Backs the archive page's endpoints — see {@link EditorialSummary} for what the underlying view is. */
public interface EditorialSummaryRepository extends JpaRepository<EditorialSummary, UUID> {

    /**
     * {@code findFirst}, not {@code find}: {@code featurated} isn't
     * unique-constrained in the schema — {@link
     * EditorialRepository#clearFeaturated} is what actually keeps it to at
     * most one.
     *
     * @return the featurated editorial, or empty if none is set
     */
    Optional<EditorialSummary> findFirstByFeaturatedTrue();

    // `pattern` is a pre-built "%...%" string (see EditorialService), rather
    // than CONCAT('%', :q, '%') inline — Hibernate 6 sometimes fails to infer
    // a CONCAT bind parameter's type against Postgres and sends it as bytea,
    // which LOWER() then rejects ("function lower(bytea) does not exist").
    @Query("""
        SELECT s FROM EditorialSummary s
        WHERE (:type IS NULL OR s.ownerType = :type)
          AND (:pattern IS NULL
               OR LOWER(s.title) LIKE :pattern
               OR LOWER(s.ownerName) LIKE :pattern)
        """)
    Page<EditorialSummary> search(
        @Param("type") EditorialOwnerType type, @Param("pattern") String pattern, Pageable pageable
    );
}
