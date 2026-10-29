package com.akshatha.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * The one table in this system.
 *
 * No @GeneratedValue on the id: ids come from an IdAllocator (the DB sequence
 * in M2, the Redis block allocator in M5) and are Base62-encoded into the
 * short code. Letting JPA generate them would take that control away.
 */
@Entity
@Table(name = "url_mapping")
public class UrlMapping {

    @Id
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 10)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    private String longUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    /** For JPA only. Nothing else should be able to build a half-populated entity. */
    protected UrlMapping() {
    }

    public UrlMapping(Long id, String shortCode, String longUrl, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
        this.longUrl = Objects.requireNonNull(longUrl, "longUrl");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.clickCount = 0;
    }

    /** True when an expiry was set and it is already in the past. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getClickCount() {
        return clickCount;
    }

    /**
     * Equality is on shortCode, the natural key: it is assigned at construction
     * and never changes, so an entity keeps the same identity before and after
     * a flush. Basing this on every field (the IDE default) breaks the moment
     * clickCount is updated.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof UrlMapping other && shortCode.equals(other.shortCode);
    }

    @Override
    public int hashCode() {
        return shortCode.hashCode();
    }

    @Override
    public String toString() {
        return "UrlMapping[shortCode=" + shortCode + ", id=" + id + "]";
    }
}
