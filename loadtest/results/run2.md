# Run 2 — Redis cache, local

The after-number. Identical hardware, identical load profile, identical
dataset to run 1. One thing changed: `--spring.profiles.active=cached`, which
swaps `NoOpUrlCache` for `RedisUrlCache` and the Postgres sequence allocator
for the Redis block allocator.

## Configuration

Everything matches run 1 except:

| | Run 1 | Run 2 |
|---|---|---|
| Profile | default | `cached` |
| Read cache | `NoOpUrlCache` | `RedisUrlCache` |
| Id allocator | Postgres sequence | Redis blocks |
| Redis | unused | redis:7-alpine, host port 6380 |

Same 5,000-code `codes.csv`, same 50 threads / 60s ramp / 300s hold, same
box, same JVM, rate limiting off, separate 90s warm-up discarded.

## Results

Steady state, ramp-up discarded. Computed from `run2.jtl`.

| | Run 1 (no cache) | Run 2 (Redis) | Change |
|---|---|---|---|
| p50 | 12 ms | 10 ms | **−16.7%** |
| **p95** | **28 ms** | **20 ms** | **−28.6%** |
| p99 | 47 ms | 30 ms | **−36.2%** |
| p99.9 | 119 ms | 77 ms | −35.3% |
| mean | 13.6 ms | 11.0 ms | −19.4% |
| max | 532 ms | 276 ms | −48.1% |
| Throughput | 3,721 req/s | 4,205 req/s | **+13.0%** |
| Samples | 1,339,179 | 1,511,664 | |
| Response codes | 100% 302 | 100% 302 | |
| Error rate | 0.0000% | 0.0000% | |

### Cache behaviour during the run

| | |
|---|---|
| Redis keyspace hit rate | **99.74%** (1,932,371 hits / 5,082 misses) |
| `url:*` keys resident | 5,000 — the entire dataset |

The 5,082 misses are the cold start: each of the 5,000 codes misses exactly
once on first touch, then stays resident for the configured 24h TTL.

## Reading these numbers honestly

**The headline is the tail, not the median.** p50 improved 17%, but p99
improved 36% and the worst case halved. That is the shape you expect from a
cache: it does not make a fast query faster, it removes the slow outliers —
connection pool waits, JPA entity materialisation, the occasional Postgres
hiccup.

**The gain is modest and the reason matters.** A 28% p95 improvement is
smaller than the "Redis made it 10x faster" claim people expect, and the
honest explanation is that *run 1 was never slow*:

- 5,000 rows fit entirely in Postgres' shared buffers, so the baseline was
  already serving from memory. There was no disk I/O to remove.
- The app and Postgres are on the same machine. There is no network round-trip
  between them for Redis to eliminate — just a loopback socket.
- At 4,200 req/s on 8 shared cores, both runs are partly CPU-bound on JMeter
  itself, which compresses the difference.

What the cache actually removed here is JDBC + Hibernate overhead per request,
not database latency. That is a real cost — it is most of the 12 ms — but it
is the smallest version of the win.

**Where this would look very different:**

| Condition | Effect on the gap |
|---|---|
| Postgres across a network (RDS) | Much larger — adds 1–5 ms per query to run 1 only |
| Table larger than shared buffers | Much larger — run 1 starts paying disk I/O |
| Zipfian key distribution | Larger — hot keys always resident, cold rows slow in run 1 |
| Load generator on its own box | Both runs faster; gap likely wider |

This run used a **uniform sweep** over 5,000 keys, which is the pessimistic
distribution for a cache in general — but note that because all 5,000 keys fit
in Redis, the hit rate was still 99.7%. So this is close to a best-case *hit
rate* on a worst-case *baseline difficulty*. Say that out loud when quoting it.

**Coordinated omission still applies.** Closed-loop run, constant-throughput
timer off, exactly as in run 1. The comparison between the two is valid
because both were measured the same way; the absolute p95 of either is
optimistic.

## What to quote

> Adding a Redis read-through cache cut p95 redirect latency from 28 ms to
> 20 ms (−29%) and p99 from 47 ms to 30 ms (−36%), at 4,200 req/s with a
> 99.7% cache hit rate — measured on identical hardware with the baseline
> recorded first.

And be ready for "why only 29%?" — the answer is in *Reading these numbers
honestly* above, and it is a better answer than a bigger number would be.

Next: run 3 on EC2 behind Nginx (M7), where the network makes this gap real.
