package com.akshatha.urlshortener.repository;

import com.akshatha.urlshortener.domain.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * Index-backed by the UNIQUE constraint on short_code — see V1 migration.
     */
    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Highest id already persisted, or 0 for an empty table.
     *
     * Read once per JVM by RedisBlockIdAllocator to seed the Redis counter.
     * Cheap: it is a backwards index scan on the primary key.
     */
    @Query("select coalesce(max(m.id), 0) from UrlMapping m")
    long maxAssignedId();

    /**
     * The M2 id source. Kept isolated so M5 can swap the allocator without
     * touching anything else; it stays as the Redis-down fallback.
     */
    @Query(value = "SELECT nextval('url_mapping_id_seq')", nativeQuery = true)
    long nextSequenceValue();

    /**
     * Bulk click-count flush (M5): one statement for many codes, rather than
     * N updates. Takes a code -> delta map flattened into a VALUES list so the
     * whole flush is a single round-trip.
     *
     * clearAutomatically/flushAutomatically keep the persistence context honest
     * — this bypasses it, so stale managed entities would otherwise linger.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE url_mapping m
               SET click_count = m.click_count + v.delta
              FROM (SELECT unnest(CAST(:codes AS varchar[]))  AS short_code,
                           unnest(CAST(:deltas AS bigint[]))  AS delta) v
             WHERE m.short_code = v.short_code
            """, nativeQuery = true)
    int incrementClickCounts(@Param("codes") String[] codes, @Param("deltas") Long[] deltas);
}
