# Run N — <one-line description>

Copy this file to `runN.md` and fill it in while the run is still fresh.
A result you cannot reproduce is not a result.

## Configuration

| | |
|---|---|
| Date | |
| App config | `--spring.profiles.active=` (blank = M4 baseline, `cached` = M5+) |
| App host | localhost / EC2 instance type |
| Load generator host | SAME BOX / separate box |
| JVM | `java -version` output |
| Postgres | 17-alpine, local container / RDS |
| Redis | none / 7-alpine local / ElastiCache |

## Load profile

| | |
|---|---|
| Threads | |
| Ramp-up (s) | |
| Hold (s) | |
| Dataset size (codes) | |
| Key distribution | uniform random / zipfian (state the skew) |
| Warm-up discarded | first __ s |
| Constant-throughput timer | off (closed loop) / on at __ req/min |

## Results

| p50 | p95 | p99 | Throughput (req/s) | Error rate |
|-----|-----|-----|--------------------|------------|
|     |     |     |                    |            |

## Notes

- Anything that went wrong, restarted, or looked odd:
- Coordinated omission: closed-loop runs under-report the tail. State whether
  the timer was on, and if not, say so when quoting these numbers.
- What surprised you:
