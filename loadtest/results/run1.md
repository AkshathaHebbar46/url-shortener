# Run 1 — M4 baseline: no cache, local

The before-number. Measured with `shortener.cache.enabled=false` and the
Postgres sequence allocator, i.e. every redirect is a database round-trip.
Recorded before the cache was switched on, which is the only reason run 2
means anything.

## Configuration

| | |
|---|---|
| Date | 2026-10-28 |
| App config | default profile (no `--spring.profiles.active`) |
| | `url cache: DISABLED`, `id allocator: Postgres sequence` — confirmed in the startup log |
| Rate limiting | **off** (`--shortener.rate-limit.enabled=false`) — otherwise the limiter rejects at 100 req/min and you measure the limiter |
| App host | localhost, Apple M1 (4 perf + 4 eff cores), 8 GB RAM, macOS 26.5 |
| Load generator host | **SAME BOX** — see Caveats |
| JVM | Temurin/Oracle 21.0.8+12-LTS (Gradle toolchain) |
| JMeter | 5.6.3 |
| Postgres | postgres:17-alpine in colima (2 vCPU, 4 GiB VM), host port 5433 |
| Redis | not used in this configuration |

## Load profile

| | |
|---|---|
| Threads | 50 |
| Ramp-up | 60s |
| Total duration | 360s (60s ramp + 300s hold) |
| Warm-up | separate 90s run first, discarded entirely; the 60s ramp of this run is also excluded from the steady-state figures |
| Dataset size | 5,000 seeded codes, all distinct (`seed.sh COUNT=5000`) |
| Key distribution | **uniform sweep.** CSV Data Set with `shareMode.all` + `recycle=true`, so the 50 threads draw from one shared cursor over the 5,000 codes and each code is hit about equally often. This is the pessimistic case and it is the honest one to quote: there are no hot keys for a cache to exploit. |
| Constant-throughput timer | **off** — closed loop. See Caveats. |

## Results

Computed directly from `run1.jtl` (JMeter's HTML reporter OOMs on 1.3M
samples at its default heap; the raw file is the source of truth).

**Steady state — ramp-up discarded. These are the numbers to quote.**

| p50 | p95 | p99 | p99.9 | Throughput | Error rate |
|-----|-----|-----|-------|------------|------------|
| 12 ms | **28 ms** | 47 ms | 119 ms | 3,651 req/s | 0.0000% |

Full run including ramp-up, for completeness:

| p50 | p95 | p99 | p99.9 | Throughput | Error rate |
|-----|-----|-----|-------|------------|------------|
| 11 ms | 27 ms | 44 ms | 106 ms | 3,721 req/s | 0.0000% |

- Samples: 1,339,179
- Response codes: `{302: 1339179}` — every single sample was a redirect
- Mean 13.6 ms, max 532 ms

The response-code breakdown matters: the `expect 302` assertion in the plan
exists because a dataset/database mismatch makes every sample a fast 404 and
the run looks *better* while measuring nothing. 100% 302 proves the run
actually exercised the redirect path.

## Caveats — read these before quoting the number

1. **Load generator shares the box with the app.** JMeter and the JVM under
   test fought for the same 8 cores, and Postgres was in a 2-vCPU VM on the
   same machine. Throughput here is a floor, not a ceiling, and the tail is
   contaminated by scheduler contention. Run 3 on EC2 with a separate
   generator is the number to actually quote.

2. **Coordinated omission is NOT mitigated.** The constant-throughput timer is
   disabled, so this is a closed-loop run: JMeter waits for a response before
   sending the next request, so a stall stops the load instead of queueing
   behind it. Real p95 under an open workload would be worse than 28 ms. The
   honest framing is "28 ms p95 closed-loop", not "28 ms p95".

3. **Localhost has no network in it.** Zero RTT, no TLS, no proxy. A real
   deployment adds all three.

4. 8 GB of RAM total, with Postgres in a VM alongside. The 5,000-row table
   sits entirely in Postgres' shared buffers, so this baseline is measuring
   query + JDBC + JPA overhead on a warm cache, not disk I/O. That makes it a
   *conservative* baseline — a cold or larger table would be slower, and the
   cache would look better by comparison.

## What this tells you

12 ms median for what is a single indexed primary-key-ish lookup is dominated
by JPA/Hibernate entity materialisation and connection handling, not by
Postgres. That is the overhead run 2 should remove by not going to the
database at all.

Next: run 2, identical hardware and load profile, `--spring.profiles.active=cached`.
