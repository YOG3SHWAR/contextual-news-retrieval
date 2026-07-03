package com.inshorts.news.repository;

import com.inshorts.news.domain.Article;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Native, index-served retrieval queries (design §6). All ranking pushes into
 * SQL — the corpus is never sorted in application memory — and every query
 * appends {@code id} as a deterministic tie-break in the same direction as the
 * rank key, so the ordering is stable and keyset-pagination-shaped (§10.2).
 *
 * <p>The public API exposes only the top-N page ({@code limit}); adding a
 * {@code cursor} predicate later is additive, not a rewrite.
 */
@Repository
public interface ArticleQueryRepository extends org.springframework.data.repository.Repository<Article, java.util.UUID> {

    /** R1: category contains value (case-insensitive via normalized array). */
    @Query(value = """
            SELECT * FROM articles
            WHERE categories_norm @> ARRAY[:category]::text[]
            ORDER BY publication_date DESC NULLS LAST, relevance_score DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> findByCategory(@Param("category") String categoryLower, @Param("limit") int limit);

    /** R2: relevance_score >= threshold, ranked by score desc. */
    @Query(value = """
            SELECT * FROM articles
            WHERE relevance_score >= :threshold
            ORDER BY relevance_score DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> findByScore(@Param("threshold") double threshold, @Param("limit") int limit);

    /** R4: source_name matches (case-insensitive), ranked by pubdate desc. */
    @Query(value = """
            SELECT * FROM articles
            WHERE lower(source_name) = lower(:source)
            ORDER BY publication_date DESC NULLS LAST, relevance_score DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> findBySource(@Param("source") String source, @Param("limit") int limit);

    /**
     * R3 primary: blended full-text + relevance ranking. {@code ts_rank(...,32)}
     * normalizes into {@code [0,1)} to share scale with relevance_score. Matches
     * only rows satisfying the tsquery; empty tsquery → 0 rows → ILIKE fallback.
     */
    @Query(value = """
            SELECT * FROM articles
            WHERE search_tsv @@ plainto_tsquery('english', :q)
            ORDER BY (:wText * ts_rank(search_tsv, plainto_tsquery('english', :q), 32)
                      + :wRel * coalesce(relevance_score, 0)) DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> searchBlended(@Param("q") String query,
                                @Param("wText") double weightText,
                                @Param("wRel") double weightRelevance,
                                @Param("limit") int limit);

    /** R3 fallback: substring match when the tsquery yields nothing. */
    @Query(value = """
            SELECT * FROM articles
            WHERE title ILIKE :pattern OR description ILIKE :pattern
            ORDER BY relevance_score DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> searchIlike(@Param("pattern") String pattern, @Param("limit") int limit);
}
