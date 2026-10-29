package com.akshatha.urlshortener.service;

/**
 * The result of a cache read. Optional<String> can only say "here is a URL" or
 * "nothing" — it cannot distinguish "Redis told me this code definitely does
 * not exist" from "Redis had no opinion". Collapsing those two is what lets a
 * flood of junk codes hammer Postgres, so they get separate cases here.
 */
public sealed interface CacheLookup {

    /** Cached URL, serve it. */
    record Hit(String longUrl) implements CacheLookup {
    }

    /** Negative cache entry: we looked this up recently and there was no row. */
    record KnownAbsent() implements CacheLookup {
    }

    /** No entry, or the cache is unavailable. Go to the database. */
    record Miss() implements CacheLookup {
    }

    CacheLookup MISS = new Miss();
    CacheLookup KNOWN_ABSENT = new KnownAbsent();
}
