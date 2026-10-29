package com.akshatha.urlshortener.service;

/**
 * Source of unique, monotonic ids. Base62-encoding one of these gives a short code.
 *
 * Two implementations live behind this interface:
 *   M2 — SequenceIdAllocator, one DB round-trip per call. Correct, slow, simple.
 *   M5 — RedisBlockIdAllocator, one Redis round-trip per N calls.
 *
 * The interface exists so M5 is a swap, not a rewrite — and so you can
 * benchmark the two against each other, which is worth doing.
 */
public interface IdAllocator {

    long nextId();
}
