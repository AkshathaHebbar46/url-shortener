package com.akshatha.urlshortener.service;

/**
 * Click accounting seam, kept off the redirect's critical path.
 *
 * The redirect is the hot endpoint and sits between a click and the page the
 * user wanted; a synchronous UPDATE there would add a write round-trip, and a
 * row lock on the hottest rows in the table, to every single click.
 */
public interface ClickCounter {

    /** Record one click. Must be cheap and must never throw. */
    void record(String shortCode);
}
