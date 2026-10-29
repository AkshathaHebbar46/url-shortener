# url-shortener

Base62 short-code service with Redis-backed read caching, rate limiting and
request validation. Spring Boot 3.5 / Java 21 / Postgres / Redis.

## Status

M1–M6 are done. **M7 is not** — so there is a baseline number but no
production number yet. See Results below.

The app runs in two configurations from one codebase, switched by profile:

| | Default (no profile) | `cached` profile |
|---|---|---|
| Read cache | `NoOpUrlCache` — every redirect hits Postgres | `RedisUrlCache` |
| Id source | `SequenceIdAllocator` — one `nextval` per write | `RedisBlockIdAllocator` |
| Click counts | not recorded | buffered in Redis, flushed every 30s |

The default is the **M4 control group** on purpose. Measure it before turning
the cache on; once the cache is in you cannot honestly reconstruct the
before-number.

## Running

```bash
docker-compose up -d     # this machine has the v1 binary, not the `docker compose` plugin
./gradlew bootRun                                          # baseline config
./gradlew bootRun --args='--spring.profiles.active=cached'  # Redis cache + block ids
```

Postgres on host port 5433, Redis on 6380, both with throwaway credentials. The Gradle
toolchain is pinned to Java 21, so it does not matter that `java -version`
on this machine says 17.

**Why the non-default host ports.** This machine already runs a native
Postgres on 127.0.0.1:5432 and a native Redis on :6379. Those bind the
loopback addresses specifically, which beats Docker's wildcard bind — so with
the usual `5432:5432` mapping `docker-compose up -d` reports success while the
app silently connects to the *native* Postgres and fails with
`FATAL: role "shortener" does not exist`. The compose file therefore maps
**5433** and **6380** on the host, and `application.yml` points at those. The
container ports are unchanged, so nothing inside the compose network cares.

If you later remove the native services, `lsof -nP -iTCP:5432 -sTCP:LISTEN`
confirms the port is free and you can map it back.

Note: `services.gradle.org` is unreachable from the JVM on this network, so
`./gradlew` could not self-install. The 8.14.6 distribution was fetched with
curl into `~/.gradle/wrapper/dists/` instead. Maven Central resolves fine, so
this only bites on a fresh machine — if the wrapper hangs on
"Downloading https://services.gradle.org/...", do the same curl-into-cache trick.

### Trying it

```bash
curl -i -X POST localhost:8080/api/v1/shorten \
     -H 'Content-Type: application/json' \
     -d '{"longUrl": "https://example.com/some/very/long/path"}'
# 201 Created, Location: http://localhost:8080/1000000

curl -i localhost:8080/1000000
# 302 Found, Location: https://example.com/some/very/long/path
```

Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

### API

| | |
|---|---|
| `POST /api/v1/shorten` | 201 + `Location`. Optional `customAlias` (Base62, 4–10) and `expiresAt`. |
| `GET /{code}` | 302 to the destination |
| 400 | invalid or unsafe URL — RFC 7807 body with per-field messages |
| 409 | custom alias already taken |
| 410 | code expired |
| 429 | rate limited, with `Retry-After` |
| 503 | `cached` profile only: Redis unreachable, so no id can be allocated |

### Tests

```bash
./gradlew test
```

Unit tests run anywhere. The integration tests need Docker; they skip
themselves rather than fail if it is not running. On colima, Testcontainers
needs to be pointed at the socket:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

| Test | Covers |
|---|---|
| `Base62CodecTest` | round trips, boundaries, alphabet, overflow, 7-char range |
| `SafeUrlValidatorTest` | scheme allowlist, SSRF ranges, metadata endpoint, self-reference |
| `ShortenerServiceTest` | cache-aside, negative caching, expiry, stampede, collision retry |
| `RedisBlockIdAllocatorTest` | 160k ids / 16 threads all distinct; one round-trip per block |
| `ShortenerIntegrationTest` | `cached` profile over real Postgres + Redis |
| `BaselineProfileIntegrationTest` | the control group still boots and works |
| `RateLimitIntegrationTest` | 429 + `Retry-After` over a real socket, separate buckets |

## Milestones

| # | Goal | Acceptance | |
|---|------|-----------|---|
| M1 | Base62 codec + tests | `decode(encode(n)) == n` for boundaries and thousands of random longs | done |
| M2 | Spring Boot, DB only, no cache | `curl` a URL in, paste the short one in a browser, land on the destination | done |
| M3 | Validation + error handling | `javascript:`, `localhost`, `169.254.169.254` and malformed input all give a clean 400 | done |
| M4 | **JMeter baseline** | A recorded p95, measured before Redis exists | done |
| M5 | Redis cache + block ID allocator | Two instances never collide; `redis-cli MONITOR` shows the hits | done |
| M6 | Rate limiting + OpenAPI | A curl loop starts getting 429s; Swagger UI drives the API | done |
| M7 | EC2 + Nginx, then re-measure | Two numbers in the table below, and you can explain the gap | **not done** |

**M4 is the one that matters.** The whole point of this project is producing
an honest before/after latency number. The code for M5 exists, but it is
switched off by default precisely so the baseline is still measurable — run
M4 against the default profile before you turn the cache on.

## Results

Fill in from `loadtest/results/`. Empty until M4.

| Run | Config | p50 | p95 | p99 | Throughput |
|-----|--------|-----|-----|-----|-----------|
| 1 | No cache, local | 12 ms | **28 ms** | 47 ms | 3,651 req/s |
| 2 | Redis, local | 10 ms | **20 ms** | 30 ms | 4,205 req/s |
| 3 | Redis, EC2 + Nginx | | | | |

Runs 1 and 2: 50 threads, 5 min hold after a 60s ramp, same 5,000-code uniform
sweep, 0 errors, 100% 302 in both. Run 2 held a **99.7% cache hit rate**.

**The cache cut p95 by 29% and p99 by 36%** — the tail improved far more than
the median, which is what a cache is for. The gain is modest because the
baseline was never slow: 5,000 rows sit in Postgres' shared buffers with no
network between app and database, so what Redis removed was JDBC/Hibernate
overhead, not database latency. Both runs are closed-loop on a single box.

See [`run1.md`](loadtest/results/run1.md) and
[`run2.md`](loadtest/results/run2.md) — the caveats have to be quoted
alongside the numbers.

See `loadtest/README.md` for how to run the plan, and the two **OPEN** entries
in `docs/decisions.md` (key distribution, coordinated omission) that have to
be settled before any of these numbers can be quoted.

## Layout

```
codec/        M1  Base62Codec — pure logic, no Spring
domain/       M2  UrlMapping entity
repository/   M2  Spring Data repository + bulk click flush
service/      M2  ShortenerService, IdAllocator + UrlCache seams
              M5  RedisBlockIdAllocator, RedisUrlCache, click buffering
web/          M2  ShortenController (write), RedirectController (read)
validation/   M3  @SafeUrl — the security-critical bit
exception/    M3  GlobalExceptionHandler — RFC 7807 for everything
config/       M2  ShortenerProperties, ServiceConfig (the profile switch)
              M6  RateLimitFilter, RateLimitConfig, OpenApiConfig
loadtest/     M4  redirect.jmx, seed.sh, results/
deploy/       M7  nginx.conf — half of the X-Forwarded-For trust boundary
docs/             decisions.md — what was chosen, rejected, and why
```

`SequenceIdAllocator` and `NoOpUrlCache` are the control group for the
benchmarks, not dead code. `BaselineProfileIntegrationTest` boots them for
real so they cannot quietly rot.

## What is still open

`docs/decisions.md` marks these **OPEN**; they are judgement calls and
measurements, not missing code:

- the M4 baseline and the M7 EC2 re-measurement
- key distribution for the load tests (uniform vs zipfian — both legitimate,
  picking the flattering one silently is not)
- coordinated omission: the constant-throughput timer is off by default, so
  the default run is closed-loop and under-reports the tail
- provoking the cache stampede in a load test before claiming the fix works
- whether packing the allocator's (next, end) pair into a single long beats
  the current CAS-on-a-record — only worth measuring if write volume ever
  justifies it
