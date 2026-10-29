# Load testing (M4 and M7)

M4 is the milestone that makes the resume claim true. Everything else is
plumbing around it.

## The rule

**Run the baseline before Redis exists.** Once the cache is in, you cannot
honestly reconstruct the before-number, and "it got faster" without a
before-number is not a claim you can defend in an interview.

## Runs to record

| # | When | Config | Measures |
|---|------|--------|----------|
| 1 | M4 | No cache, local | Baseline p95 |
| 2 | M5 | Redis cache, local | Cached p95, same hardware |
| 3 | M7 | Redis cache, EC2, via Nginx | The number you actually quote |

Run 3 is the quotable one. Localhost numbers have no network in them and
anyone who load-tests for a living will know that immediately.

## Running the plan in this directory

`redirect.jmx` implements the shape described below. Every knob is a `-J`
property, so the same plan file runs for every milestone and only the app
configuration changes between runs. That is what makes the runs comparable.

### 1. Start the app in the configuration you are measuring

```bash
docker-compose up -d

# Run 1 (M4 baseline) — no cache, Postgres sequence ids. This is the default.
./gradlew bootRun --args='--shortener.rate-limit.enabled=false'

# Runs 2 and 3 (M5+) — Redis cache and block allocator. Same code, same box.
./gradlew bootRun --args='--spring.profiles.active=cached --shortener.rate-limit.enabled=false'
```

Rate limiting is switched off for load tests on purpose. Leaving it on means
you measure the rate limiter rejecting you at 100 req/min, not the redirect
path. Say so when you quote the numbers.

### 2. Seed the dataset

```bash
cd loadtest
COUNT=5000 ./seed.sh          # writes codes.csv
```

Re-seed between runs only if you also reset the database. Reusing `codes.csv`
against a fresh database is the mistake the `expect 302` assertion in the plan
is there to catch: every sample 404s, the run looks fast, and it is measuring
the miss path.

### 3. Run it headless

```bash
jmeter -n -t redirect.jmx -l results/run1.jtl -e -o results/run1-report \
       -Jthreads=50 -Jrampup=60 -Jduration=360 \
       -Jhost=localhost -Jport=8080 -Jcodes=codes.csv
```

| Property | Default | |
|---|---|---|
| `threads` | 50 | concurrent users |
| `rampup` | 60 | seconds to reach full load |
| `duration` | 360 | total seconds, including ramp-up |
| `host` / `port` / `scheme` | localhost / 8080 / http | target |
| `codes` | codes.csv | CSV of short codes, one per line |

Duration defaults to six minutes: one minute ramping, five minutes held. The
30–60s of warm-up samples still have to be discarded from the report — the
plan records them, it does not throw them away for you.

### 4. Record it

Copy `results/TEMPLATE.md` to `results/run1.md` and fill it in before you move
on. The constant-throughput timer in the plan is **disabled by default**; see
the coordinated omission section below before deciding whether to enable it,
and write down which way you ran it.

## Test plan shape

This is what `redirect.jmx` already does; the notes matter when you change it.

- Thread Group: ramp to N users over ~60s, hold ~5 min. Do not quote numbers
  from a 30-second run — the JVM is still warming up and you will be
  measuring the interpreter, not your code.
- CSV Data Set Config: pre-seeded short codes, one per line. Recycle on EOF.
- HTTP Request: `GET /${code}`
- **Uncheck "Follow Redirects"**, or you benchmark the destination site
  instead of your app. This is the single most common mistake here.
- Listener: Aggregate Report for percentiles. Disable the GUI tree listeners
  before a real run, they cost more than the app does.
- Run headless: `jmeter -n -t plan.jmx -l results/run1.jtl -e -o results/run1-report`

## Before each run

- Warm the JVM — throw away the first 30–60s of samples.
- Seed enough URLs that you are not hitting the same 10 rows (unrealistically
  cache-friendly). A few thousand minimum.
- Decide your key distribution and WRITE IT DOWN. Uniform random across all
  codes is the pessimistic case. Zipfian (a few hot keys) is realistic and
  flatters the cache. Both are legitimate; silently picking the flattering
  one and quoting it is not.
- For runs 2 and 3: run the load generator on a SEPARATE machine. Sharing a
  box means your load tool and your app fight for the same cores and both
  numbers are garbage.

## Coordinated omission

Read about this before you trust any number you produce.

JMeter's closed-loop thread model waits for a response before sending the
next request. When the server stalls, JMeter stops sending — so the stall is
sampled once instead of thousands of times, and your p95 comes out far better
than reality. Your tail latency is the thing this hides, and the tail is
exactly what you are claiming.

Mitigations: use a constant-throughput timer, or cross-check with a tool that
has an open workload model. At minimum, know that the effect exists and say
so when you quote numbers.

## Record in `results/`

For every run: date, machine spec, JVM version, thread count, duration,
dataset size, key distribution, p50/p95/p99, error rate, and anything that
went wrong. A result you cannot reproduce is not a result.

Then put p95 into the README table and into the resume bullet — replacing the
`[X]` placeholders that this project exists to fill.
