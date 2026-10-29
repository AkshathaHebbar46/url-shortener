package com.akshatha.urlshortener.service;

/**
 * The control group. Every get() misses, so every redirect goes to Postgres.
 * This is the configuration M4 measures.
 */
public class NoOpUrlCache implements UrlCache {

    @Override
    public CacheLookup get(String shortCode) {
        return CacheLookup.MISS;
    }

    @Override
    public void put(String shortCode, String longUrl) {
    }

    @Override
    public void put(String shortCode, String longUrl, java.time.Duration maxTtl) {
    }

    @Override
    public void putAbsent(String shortCode) {
    }

    @Override
    public void evict(String shortCode) {
    }

    @Override
    public boolean tryAcquireLoadLock(String shortCode) {
        return true;
    }

    @Override
    public void releaseLoadLock(String shortCode) {
    }
}
