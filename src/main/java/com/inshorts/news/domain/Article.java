package com.inshorts.news.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A news article. The source of truth is the {@code articles} table (design §3.1).
 *
 * <p>The {@code geog} (GEOGRAPHY) and {@code search_tsv} (generated TSVECTOR)
 * columns are intentionally <b>not</b> mapped here: they are written/queried only
 * through native SQL (geo distance, full-text ranking). Leaving them unmapped
 * means Hibernate never selects or writes them, avoiding type-mapping friction.
 */
@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
public class Article {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    private String url;

    @Column(name = "publication_date")
    private LocalDateTime publicationDate;

    @Column(name = "source_name")
    private String sourceName;

    /** Original category labels, preserved for API responses. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", columnDefinition = "text[]")
    private String[] categories;

    /** Lowercased copy used for case-insensitive {@code @>} containment filtering. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories_norm", columnDefinition = "text[]")
    private String[] categoriesNorm;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    private Double latitude;

    private Double longitude;

    /** Lazily persisted LLM summary; null until generated. */
    @Column(name = "llm_summary")
    private String llmSummary;
}
