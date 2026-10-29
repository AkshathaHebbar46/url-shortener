package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.repository.UrlMappingRepository;

/**
 * The deliberately naive allocator: one DB round-trip per shorten().
 *
 * Not optimised on purpose. Its job is to be the thing M5 beats, and to give a
 * believable write-path baseline to compare against. Each shorten() with this
 * allocator costs two round-trips — nextval, then INSERT — against one INSERT
 * plus an amortised 1/blockSize Redis call under RedisBlockIdAllocator.
 */
public class SequenceIdAllocator implements IdAllocator {

    private final UrlMappingRepository repository;

    public SequenceIdAllocator(UrlMappingRepository repository) {
        this.repository = repository;
    }

    @Override
    public long nextId() {
        return repository.nextSequenceValue();
    }
}
