package com.jazzlogs.backend.editorial;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "editorial_blocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EditorialBlock {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "editorial_id", nullable = false)
    private Editorial editorial;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EditorialBlockType type;

    // Optional — a block (of any type) can carry a subhead above its text
    // instead of SUBHEAD being its own EditorialBlockType.
    private String subhead;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_category", nullable = false)
    private BlockContentCategory contentCategory;

    @Type(PgVectorType.class)
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedding_metadata")
    private Map<String, Object> embeddingMetadata;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public EditorialBlock(
        Editorial editorial,
        int position,
        EditorialBlockType type,
        String subhead,
        String text,
        BlockContentCategory contentCategory,
        float[] embedding,
        Map<String, Object> embeddingMetadata
    ) {
        this.editorial = editorial;
        this.position = position;
        this.type = type;
        this.subhead = subhead;
        this.text = text;
        this.contentCategory = contentCategory;
        this.embedding = embedding;
        this.embeddingMetadata = embeddingMetadata;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
