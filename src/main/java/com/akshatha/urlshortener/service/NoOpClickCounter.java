package com.akshatha.urlshortener.service;

/**
 * Used in the M4 baseline configuration, where there is no Redis to buffer in.
 * Counting clicks straight into Postgres on the read path would pollute the
 * very number M4 exists to measure.
 */
public class NoOpClickCounter implements ClickCounter {

    @Override
    public void record(String shortCode) {
    }
}
