# Design decisions

One short entry per real decision: what was chosen, what was rejected, why.

Entries marked **OPEN** are yours to fill in — they record a measurement or a
judgement that has not been made yet. Everything else was decided while the
code was written.

---

## M2 — core

### 302 Found, not 301 Moved Permanently

**Chosen:** 302, with `Cache-Control: no-store`.

**Rejected:** 301.

A 301 is cached by the browser more or less forever. Repeat clicks never reach
the service again, which is the fastest possible experience for the user and
costs nothing to serve — and it destroys click analytics, because the clicks
are invisible. For a shortener the analytics *are* the product; that is what
the business sells. Real shorteners mostly choose 302 and the reason is
revenue, not technology.

What it costs: every click is a request we have to serve. That is precisely
why the read path has a cache in front of it, and why M4/M5 measure it.

`no-store` is explicit rather than implied, because a 302 without cache
headers may still be cached heuristically by intermediaries.

### Counter + Base62, not random, not a hash

**Chosen:** a monotonic counter, Base62-encoded.

**Rejected:** random codes; hash of the URL (truncated SHA-256).

- Random needs a uniqueness check on every insert, and as the keyspace fills,
  the retry rate climbs. It buys unguessability.
- Hashing makes the same URL always produce the same code, which sounds
  elegant until someone wants two codes for the same destination with
  different expiry — and truncation reintroduces collisions anyway.
- A counter is collision-free by construction: one INSERT, no check.

What a counter costs is **enumerability**. Codes are sequential, so anyone can
walk the keyspace and enumerate every URL ever shortened. This is a real and
accepted weakness of this design, and it is the first thing a reviewer should
push on. Mitigations if it mattered: multiply the id by a secret odd constant
mod 62^7 (a bijection, so still collision-free, but scrambled), or switch to
random codes and accept the retries. Not done here, because the codes are
public URLs that anyone holding one can already share, and scrambling would
make the "first code is 7 characters" property harder to reason about.

Note that the counter is also what makes the M5 block allocator possible at
all. Random codes have nothing to batch.

### Sequence seeded at 62^6, not 0

**Chosen:** `START WITH 56800235584` (= 62^6).

Base62 of 62^6 is `1000000` — exactly 7 characters, which is the configured
`code-length`. Starting at 1 would hand out `1`, `a`, `zz`: codes that look
broken, and that would collide with any reserved path added later (`/health`,
`/api`, `/admin`). Seeding above 62^6 means every code issued is 7 characters
until the space is exhausted, which `Base62CodecTest` asserts directly.

`ServiceConfig.firstIdOfLength` computes the same seed for the Redis
allocator, so changing `code-length` moves both.

### No separate index on short_code

**Chosen:** rely on the `UNIQUE` constraint.

In Postgres a `UNIQUE` constraint is *implemented by* a unique btree index —
creating the constraint creates the index. `findByShortCode` is therefore
already index-backed. A second `CREATE INDEX ON url_mapping (short_code)`
would be a duplicate: it doubles the write cost on every insert, consumes
cache that the real index wants, and the planner would never choose it over
the unique one. Verifiable with `\d url_mapping` in psql.

(This is Postgres-specific. MySQL behaves the same way; not every engine does.)

### 410 Gone for an expired code, 404 for an unknown one

**Chosen:** distinguish them.

404 says "I have never heard of this". 410 says "this existed and is now
permanently gone" — which is more honest, and tells a crawler to stop asking.
The cost is that it leaks the existence of a code that once existed, which for
a public shortener is not sensitive.

404 for both would also be defensible, and would be the right call if code
existence were sensitive.

### shorten() is not @Transactional

Each retry attempt must run in its **own** transaction. A transaction that has
already hit a constraint violation is marked rollback-only, so retrying inside
it fails with a different and much more confusing error. Each
`repository.saveAndFlush(...)` opens and commits its own transaction, so the
retry loop sits outside any transaction deliberately. This is easy to "fix"
wrongly by adding `@Transactional` to the method.

### A taken custom alias is 409, not a retry

Retrying would hand the caller a different code than the one they asked for.
For a generated code that is fine — the caller does not care which code they
get. For a vanity alias the specific string is the entire request, so
substituting silently is worse than refusing. The race between
`existsByShortCode` and the INSERT is caught by the unique constraint and
mapped to the same 409.

### The redirect route is regex-constrained

`@GetMapping("/{code:[0-9A-Za-z]{4,10}}")`, not `/{code}`.

An unconstrained single-segment mapping at the application root is greedy: it
swallows `/swagger-ui.html`, `/favicon.ico`, and anything else later added at
the root. The regex excludes them (they contain `.` or `-`), and as a bonus a
malformed code now 404s at routing time, before any service or database call.
`ShortenerIntegrationTest.theRedirectMappingDoesNotSwallowActuatorOrSwagger`
is the regression test; without it this breaks silently in M6.

---

## M3 — validation and errors

### What the URL validator protects against, and what it does not

**Closes:** non-http(s) schemes (`javascript:`, `data:`, `file:`, `jar:`);
loopback, wildcard, link-local, site-local, IPv6 unique-local and multicast
destinations; self-referential URLs.

The link-local case is the one that matters most on EC2: `169.254.169.254` is
the instance metadata endpoint, and an open redirect or fetch reaching it can
leak IAM credentials.

**Knowingly does NOT close: DNS rebinding.** Resolving the host at validation
time and checking the IP is a TOCTOU check across two separate network calls.
An attacker controls their own DNS and can answer with a public address when
we validate, then `127.0.0.1` when someone clicks. The check is worth having —
it stops the lazy, direct attempts, which are most of them — but it is not a
boundary.

What actually defends this service is architectural: **the server never
fetches the destination.** It returns a 302 and the browser makes the request,
from the browser's network position, with the browser's credentials. There is
no SSRF primitive here because there is no server-side fetch to subvert.

That stops being true the moment anyone adds link previews, favicon fetching,
or safe-browsing checks. If that happens, this validator is not sufficient:
re-validate at fetch time, pin the resolved IP between check and connect, or
put the fetcher in a network namespace that cannot reach RFC 1918 at all.

### Unresolvable hosts are rejected (fail closed)

A host that will not resolve is a typo or an attempt to slip an unresolvable
name past the IP check. Rejecting costs a legitimate user a retry during a DNS
blip; accepting costs the control entirely. Note this does make the write path
depend on DNS being up.

### The catch-all must not sit in front of Spring's own exceptions

**Chosen:** `GlobalExceptionHandler extends ResponseEntityExceptionHandler`.

A bare `@ExceptionHandler(Exception.class)` in a `@RestControllerAdvice`
catches *everything*, including the exceptions Spring MVC raises for perfectly
ordinary malformed requests. Before this class extended anything, all of these
returned **500**:

| Request | Was | Should be |
|---|---|---|
| `GET /no-such-path` | 500 | 404 |
| `GET /favicon.ico` | 500 | 404 |
| `POST /1000000` | 500 | 405 |
| `POST /api/v1/shorten` as `text/plain` | 500 | 415 |

Worse than the status code: each one was logged at ERROR with a full stack
trace and a correlation id, so a crawler asking for `/favicon.ico` produced
what looked like a server fault. The real 500 — the one worth paging on —
would have been buried in that noise.

`ResponseEntityExceptionHandler` maps all of them onto the right 4xx as
ProblemDetail documents. The two cases with custom bodies
(`MethodArgumentNotValid`, `HttpMessageNotReadable`) are **overrides** of the
base class, not additional `@ExceptionHandler` methods for the same types,
which would be an ambiguous mapping and fail at startup.

Caught by probing the error paths by hand; no test covered them, and three
regression tests now do.

### RedisUnavailableException is 503, not 500

The allocator refuses to issue ids when Redis is down (see the M5 entry) and
that was reaching the catch-all, so a documented 503 was actually returning
500 with a stack trace. A dependency being down is transient and retryable —
the caller should see 503 with `Retry-After`, and the log should carry a WARN
line rather than a trace. Reads are unaffected and keep returning 302, which
is the behaviour the whole fail-soft design exists to produce.

### An expiry in the past is rejected

`@Future` on `expiresAt`. Accepting it minted a short URL that returned 410 on
its very first use — a dead link, created on request, with a 201 saying it
worked.

### Reserved codes

`actuator` and `error` are valid Base62 strings, so they pass the alias
pattern and the route regex — but `/actuator` is the discovery endpoint and
`/error` is Spring's dispatch target, so those mappings win and the alias can
never resolve. The user would pay for a vanity alias that silently does
nothing.

Both are now refused (409), along with a handful of names reserved ahead of
time for routes this service might grow. Un-reserving a name later is easy;
taking one back from someone already linking to it is not.

The same check guards *generated* codes. It is astronomically unlikely that
the counter reaches `decode("actuator")`, but the unique constraint cannot
catch that case — there would be no conflicting row, just a code nobody can
reach — so the allocator skips the id instead.

Paths containing `.`, `-` or a second segment (`/swagger-ui.html`,
`/v3/api-docs`, `/favicon.ico`) cannot collide, because the route regex only
matches Base62.

### RFC 7807 ProblemDetail for every error

One shape for every error the API produces, including the 429 that originates
in a servlet filter. Stack traces never reach the response — they tell an
attacker the framework versions and package layout. The 500 handler returns a
correlation id and logs the trace under it.

---

## M4 — measurement

### Key distribution used in load tests — **OPEN**

Decide and write it down **before** quoting any number:

- **Uniform random** over all seeded codes is the pessimistic case. With a
  dataset larger than the cache, most requests miss. It understates the cache.
- **Zipfian** (a few very hot keys) is what real traffic looks like, and it
  flatters the cache enormously — a handful of keys serve most requests and
  they are always resident.

Both are legitimate. Silently picking the flattering one and quoting it is
not. The seeded dataset and the `expect 302` assertion in `redirect.jmx` are
there to stop the accidental version of this (running against 10 rows, or
against codes that all 404).

### Coordinated omission — **OPEN**

The constant-throughput timer in `redirect.jmx` is disabled by default, which
means the default run is **closed-loop**: JMeter waits for a response before
sending the next request, so when the server stalls JMeter stops sending. The
stall gets sampled once instead of thousands of times, and p95 comes out far
better than reality — hiding exactly the tail you are claiming to have
improved.

Either enable the timer and pick a target rate, or quote the numbers with the
caveat stated out loud. Record which you did in `results/runN.md`.

---

## M5 — Redis

### ID block size 1000, and the arithmetic for restart waste

An instance that restarts mid-block abandons the rest of that block forever.
At `id-block-size: 1000` a restart wastes at most 999 ids, ~500 on average.

The 7-character space holds 62^7 − 62^6 = **3,464,814,370,624** ids.

| | |
|---|---|
| Restarts to exhaust the space by waste alone | ~3.46 billion |
| At an absurd 100 restarts/day | ~95,000 years |
| Exhaustion at 1,000 sustained writes/s (no waste) | ~110 years |
| Exhaustion at 10,000 sustained writes/s | ~11 years |

So restart waste is **never** the binding constraint — ordinary consumption
exhausts the space thousands of times sooner, and even that is a century away.
1000 is comfortably inside the range where the tradeoff does not matter; it
was chosen because it makes the Redis round-trip 0.1% of writes while keeping
the waste number small enough to say out loud.

The real cost of a larger block is not waste, it is **gaps**: ids from
different instances interleave in large chunks, so codes issued at the same
moment are not adjacent. Nothing depends on that here.

### Writes fail when Redis is down — they do NOT fall back to the DB sequence

**Chosen:** `RedisUnavailableException` → 503 on the write path.

**Rejected:** falling back to `nextval('url_mapping_id_seq')`.

The fallback is tempting and wrong. The Postgres sequence has no idea which
ids Redis has already handed out — both are seeded at 62^6 and advance
independently, so the moment you fall back you start reissuing ids Redis
already gave away. The unique constraint turns those into collisions, and the
collisions are *systematic*, not rare, so the bounded retry in `shorten()`
cannot absorb them. That breaks the exact uniqueness guarantee the block
allocator exists to provide.

What this costs: the write endpoint is down while Redis is down. That is
acceptable because writes are ~1% of traffic and nobody urgently needs to
create a short URL, while **reads keep working** — `RedisUrlCache` degrades to
a miss and Postgres serves them.

A genuinely safe fallback would need the two sources to occupy disjoint id
ranges by construction (e.g. a second sequence seeded far above anything Redis
will reach for years). Worth doing if write availability ever matters more
than it does now.

### The Redis counter is seeded from the database, not from 62^6 alone

**Chosen:** on first block claim, seed the counter to
`max(62^6, MAX(id) + 1)`.

**Rejected:** seeding from the static 62^6; making Redis durable.

Redis here is deliberately **not** persistent (`--appendonly no --save ""` in
docker-compose) because it is a cache. Postgres is durable. That asymmetry has
a sharp edge: restart Redis and the id counter is gone, while every row it
already issued an id for is still in Postgres. Seeding from 62^6 alone would
replay ids that exist, so `short_code` would collide on *every* write until
the counter climbed past the high-water mark — and the collisions are
systematic, so the bounded retry in `shorten()` cannot absorb them. The write
endpoint is simply down until someone notices.

This is not hypothetical: it fired the first time the stack was restarted
against a surviving `pgdata` volume.

Taking the database's high-water mark closes it for one indexed query
(`SELECT COALESCE(MAX(id), 0)`, a backwards scan on the primary key), read
once per JVM on the first block claim only. If that read fails the allocator
falls back to the static seed rather than making id allocation the thing that
reports a database outage — the INSERT will report it a moment later anyway.

Making Redis durable would also work, and would be the right call if the
counter were the only thing in there. It is not: turning on AOF to protect a
counter would also make the cache and the click buffer pay fsync costs, which
is the wrong trade for the thing M4/M5 are measuring.

### Why INCRBY, not WATCH/MULTI/EXEC

`INCRBY` is a single atomic command — there is no read-modify-write window to
guard. `WATCH`/`MULTI`/`EXEC` is optimistic locking: under contention it
retries, which is strictly more round-trips and more code to do a job one
command already does atomically.

### Refill: CAS over a single (next, end) pair, with a synchronized refill

**Chosen:** an `AtomicReference<Block>` where `Block` is a record of
`(next, end)`; ids are produced only by a successful CAS on a pair that was
read together. Refill itself stays `synchronized`, so exactly one thread goes
to Redis.

**Rejected:** the obvious two-field version — `AtomicLong cursor` plus
`volatile long blockEnd`, take an id with `getAndIncrement`, then compare it
against the bound.

That rejection was not a design preference, it was a bug. The two-field
version was written first (option (a) in the scaffold), and it has a real
duplicate-id race:

1. Thread A calls `cursor.getAndIncrement()` and gets a value past the end of
   the current block — it will discard it and retry.
2. A stalls there, before reading `blockEnd`.
3. Another thread refills: the cursor is reset to the new block's start and
   `blockEnd` is published.
4. A resumes, reads the **new** `blockEnd`, and finds its stale value now
   falls inside the new block's range — so it returns it.
5. The refilled cursor hands the same id out again.

A rewrite ordering trick ("set cursor before blockEnd") closes a *different*
window and does not close this one; the earlier version of this entry claimed
it did, and that was wrong. The only real fix is to stop reading the two
fields separately.

What makes this worth writing down is how it surfaced. It survived 160,000
ids across 16 threads many times, and a 25-repetition boundary test, before
failing **once** — with 159,999 distinct ids instead of 160,000. It only
became reproducible after an unrelated change widened the refill window. That
is the normal shape of a concurrency bug: a passing test proves nothing, and
the one that catches it looks identical to the thousand that did not.

`neverIssuesADuplicateWhenRefillsAreConstant` is the regression test — block
size 4 against 12 threads, so a refill lands roughly every third id,
repeated 50 times. The suite was then run six times over (504 assertions of
uniqueness) before this was called fixed.

The cost of the CAS version is one small short-lived object per id. On the
write path, at ~1% of traffic, that is not worth optimising away; packing
both numbers into a single long would be the next step if it ever were.

### Three-state cache lookup, not Optional

`Optional<String>` encodes "here is a URL" and "nothing". It cannot
distinguish **"Redis says this code definitely does not exist"** from
**"Redis had no opinion"**. Collapsing those is what lets a flood of junk
codes hammer Postgres, so `CacheLookup` is a sealed interface with `Hit`,
`KnownAbsent` and `Miss`. `ShortenerService.resolve` pattern-matches on it and
reads as the three cases it actually has.

### Negative TTL (60s) much shorter than positive (24h)

A positive entry caches a fact that is already true and will stay true. A
negative entry caches the *absence* of something that may be created a second
later — if it had a 24h TTL, a code created now would 404 for a day. 60s is
short enough that a newly created code appears almost immediately, long enough
to absorb a scan of junk codes.

Belt and braces: `shorten()` also calls `cache.evict(code)` after a successful
insert, so a code that happened to be probed just before creation does not
have to wait out even those 60 seconds.

### Cached entries never outlive their mapping

A mapping expiring in 10 minutes is cached for 10 minutes, not 24 hours
(`UrlCache.put(code, url, maxTtl)` takes the min). Without this, an expired
code keeps resolving from cache long after the row says it is dead — a bug
that only shows up for URLs with short expiries, which is to say rarely, and
in production.

### Stampede: SETNX lock, non-blocking

When a hot code expires, every in-flight request for it misses at once and
they all hit Postgres together. Only the request that wins a short `SETNX`
lock does the database read.

The losers **do not block**. They back off ~25ms, re-check the cache, and if
it is still empty they go to the database anyway. Blocking would trade a load
spike for a latency spike, and on the redirect path — sitting between a click
and a blank tab — latency is the worse of the two. The lock is an
optimisation, not a correctness barrier, and it has a TTL so a caller that
dies mid-load cannot wedge the key.

**OPEN:** the scaffold's instruction was to *provoke* the stampede in a load
test before fixing it, so the fix can be shown to work. That has not been
done — the behaviour is unit-tested
(`aLoserOfTheStampedeLock...`), not measured. To do it: seed one very hot key,
drive load at it, `DEL` its cache key mid-run, and watch Postgres connections.

### Click counting: Redis hash, drained by RENAME

`HINCRBY` on the redirect path — one command, no row lock. A scheduled flush
drains the buffer into one bulk `UPDATE ... FROM unnest(...)` rather than N
updates.

The drain is **RENAME-then-read**, not read-then-delete. `RENAME` is atomic,
so clicks arriving during the flush land in a fresh buffer under the original
key instead of being counted and then deleted unread. If the bulk UPDATE
fails, the snapshot key is deliberately left in place — the counts are still
in Redis and recoverable by hand, where deleting would have turned a failed
flush into silent data loss.

What this costs: a Redis loss drops up to one flush interval (30s) of clicks.
For analytics that is an acceptable trade against a write round-trip and row
contention on the hottest rows in the table, on every single click.

---

## M6 — rate limiting

### Fail OPEN when Redis is unreachable

**Chosen:** `shortener.rate-limit.fail-open: true` — serve everything
unlimited if the limiter cannot reach Redis.

**Rejected (but one property away):** fail closed.

Fail-closed turns a cache outage into a total outage: Redis hiccups and the
entire service returns 429 to everyone. Fail-open risks abuse for the duration
of the outage, which is bounded and visible, and Nginx's own `limit_req` zone
(`deploy/nginx.conf`) is still standing in front as a cruder outer bound.

There is no universally right answer. For an endpoint that costs real money
per call, fail-closed is correct. For a shortener, availability wins. The
property exists because the right answer changes with the endpoint.

### Separate buckets for shorten and redirect

10/min and 100/min. Shortening is expensive and abusable; redirects are cheap
and legitimately bursty — one page with twenty short links is twenty redirects
in a second from a normal user. A single shared limit gets whichever endpoint
it was not tuned for wrong.

### Greedy refill, not interval

`refillGreedy` drips tokens back continuously instead of restoring the full
budget on a minute boundary. Interval refill encourages every client to
synchronise into one burst at the top of each minute.

### X-Forwarded-For trust boundary

This has **two halves and both are required**:

1. `server.forward-headers-strategy=framework` in `application.yml`, which
   installs Spring's `ForwardedHeaderFilter` to rewrite `getRemoteAddr()`.
   `RateLimitFilter` is registered at `LOWEST_PRECEDENCE - 100` so it runs
   *after* that filter — ordering matters here, and getting it wrong is
   silent.
2. Nginx **overwriting** the header:
   `proxy_set_header X-Forwarded-For $remote_addr`.

Half 2 is the security-critical one. `X-Forwarded-For` is client-supplied. The
common idiom `$proxy_add_x_forwarded_for` *appends* the real client to
whatever the client sent, producing `1.2.3.4, <real ip>` — and Spring takes
the **first** entry as the client. An attacker sending a fresh fake value per
request would pick their own bucket every time and bypass the limit entirely.
Overwriting means the app only ever sees an address Nginx observed on the TCP
connection.

Caveat to remember: if a CDN or second proxy is ever put in front of Nginx,
`$remote_addr` is no longer the real client and this line needs revisiting.

Without any proxy handling at all, `getRemoteAddr()` behind Nginx is always
the proxy, so every user shares one bucket and the first busy user locks out
everyone.

### 429 is rendered through HandlerExceptionResolver

A servlet filter sits outside the `DispatcherServlet`, so an exception thrown
there never reaches `@RestControllerAdvice`. Rather than hand-writing JSON in
the filter and letting the two error shapes drift apart, the filter passes
`RateLimitExceededException` to the injected `handlerExceptionResolver`, which
routes it through `GlobalExceptionHandler`. One error shape for the whole API.

`Retry-After` is always set. A 429 with no hint about when to come back just
produces tight retry loops, which is the opposite of what a limiter is for.

### Bucket4j gets its own Lettuce client

It needs a `byte[]`-valued connection whose serializers it controls, so it
cannot share Spring Data's `RedisTemplate`. The second client is deliberate,
not an oversight. Both point at the same Redis.

### Actuator and Swagger are exempt from rate limiting

Rate-limiting `/actuator/health` would let ordinary traffic starve the load
balancer's probe and take a healthy instance out of rotation.

---

## M7 — deployment — **OPEN**

Not done. `deploy/nginx.conf` is written and commented, but nothing has been
deployed to EC2 and run 3 has not been measured. Run 3 is the quotable number:
localhost runs have no network in them, and anyone who load-tests for a living
will know that immediately.
