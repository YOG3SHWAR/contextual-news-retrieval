package com.inshorts.news.repository;

import com.inshorts.news.domain.Article;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA base repository for {@link Article}.
 *
 * <p>Retrieval queries (category / score / source / search / nearby) are keyset-
 * paginated native queries and live in the provider seams
 * ({@code PostgresSearchProvider}, {@code PostgisGeoProvider}) and
 * {@code ArticleQueryRepository}, so services depend on interfaces rather than
 * on Postgres directly. This interface supplies CRUD + the populated-count used
 * by the idempotent loader.
 */
@Repository
public interface ArticleRepository extends JpaRepository<Article, UUID> {
}
