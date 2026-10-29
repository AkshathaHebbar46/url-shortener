# Deploying (M7)

Nothing here is AWS-specific. The app is a self-contained 69 MB jar that needs
a JVM, a Postgres and a Redis. Any Linux host works.

## Files

| | |
|---|---|
| `nginx.conf` | complete nginx config for the server — replaces `/etc/nginx/nginx.conf` |
| `nginx.local.conf` | same, but upstream points at the host — for testing on your laptop |
| `shortener.service` | systemd unit |
| `shortener.env.example` | the environment file the unit reads; credentials live here, never in `application.yml` |

Both nginx configs are syntax-checked against real nginx 1.27.

---

## Part A — what you can do locally, today, for free

M7 is really two separate claims:

1. **"Nginx sits in front and the trust boundary is correct."** Entirely
   testable on your laptop. This is the security half, and it is the half an
   interviewer is more likely to probe.
2. **"Here is the latency over a real network."** Needs a remote host.

Do (1) now. It costs nothing.

### Start Nginx in front of the running app

The app must be running on 8080 **with rate limiting ON** (the load-test runs
turn it off):

```bash
./gradlew bootRun --args='--spring.profiles.active=cached'
```

Then, from `deploy/`:

```bash
docker run -d --name shortener-nginx -p 8081:80 \
  -v "$PWD/nginx.local.conf:/etc/nginx/nginx.conf:ro" \
  nginx:1.27-alpine

curl -i localhost:8081/<some-code>     # should 302, same as port 8080
```

You are now going through the proxy on 8081 and direct on 8080.

### The test that matters: can a client forge its rate-limit identity?

```bash
# 20 requests through nginx, each claiming a DIFFERENT client IP
for i in $(seq 1 20); do
  curl -s -o /dev/null -w '%{http_code} ' \
    -H "X-Forwarded-For: 203.0.113.$i" \
    -X POST localhost:8081/api/v1/shorten \
    -H 'Content-Type: application/json' \
    -d "{\"longUrl\":\"https://93.184.216.34/spoof$i\"}"
done; echo
```

**Expected: `201` ten times, then `429`.** The spoofed header is ignored
because nginx *overwrites* `X-Forwarded-For` with `$remote_addr` before the
app sees it, so all 20 requests land in one bucket.

**If you get twenty `201`s, the boundary is broken** — each forged IP got its
own bucket and the rate limiter is bypassable. To see that failure for
yourself, change the line in `nginx.local.conf` to:

```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;   # BROKEN
```

…restart nginx, and re-run. That one word is the whole difference between a
rate limiter and a decoration.

Clean up: `docker rm -f shortener-nginx`

### Optionally, run 3 "local, via Nginx"

Re-run the load test against port 8081 instead of 8080:

```bash
cd ../loadtest
jmeter -n -t redirect.jmx -l results/run3-local.jtl \
  -Jthreads=50 -Jrampup=60 -Jduration=360 -Jport=8081 -Jcodes=codes.csv
```

This measures the proxy's overhead. Label it honestly — it is **not** the
"over a real network" number, it is "same box, one extra hop".

---

## Part B — a real host, when you want one

You do not need AWS. Ranked for this project:

| Option | Cost | Notes |
|---|---|---|
| **Oracle Cloud Always Free** | free indefinitely | 4 ARM cores / 24 GB RAM. Easily the most generous. Card needed for identity check, not charged |
| **Google Cloud** | $300 credit, 90 days | plus a small always-free VM |
| **Hetzner** | ~€4/month | cheapest paid, no credit games |
| **Azure / AWS free tier** | 12 months | AWS free tier exists without a "subscription" — a new account gets 750 hrs/month of t3.micro |
| Fly.io / Render | free tiers | awkward here: you cannot easily put a load generator next to the app |

For an honest run 3 you want **two** machines in the same region — one for the
app, one for the load generator. Generating load from your laptop over home
broadband measures your ISP, not your service.

### Setup on any Ubuntu host

```bash
# 1. dependencies
sudo apt update && sudo apt install -y openjdk-21-jre-headless nginx docker.io docker-compose-v2

# 2. app user and layout
sudo useradd -r -s /usr/sbin/nologin shortener
sudo mkdir -p /opt/shortener /etc/shortener

# 3. ship the jar (from your laptop)
./gradlew bootJar
scp build/libs/url-shortener-0.0.1-SNAPSHOT.jar user@HOST:/tmp/
sudo mv /tmp/url-shortener-0.0.1-SNAPSHOT.jar /opt/shortener/url-shortener.jar
sudo chown -R shortener:shortener /opt/shortener

# 4. Postgres + Redis (scp docker-compose.yml across first)
sudo docker compose up -d

# 5. configuration — fill in SHORTENER_BASE_URL with the public address
sudo cp shortener.env.example /etc/shortener/shortener.env
sudo nano /etc/shortener/shortener.env
sudo chmod 600 /etc/shortener/shortener.env

# 6. service
sudo cp shortener.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now shortener
sudo journalctl -u shortener -f          # wait for "Started UrlShortenerApplication"

# 7. nginx
sudo cp nginx.conf /etc/nginx/nginx.conf
sudo nginx -t && sudo systemctl reload nginx

# 8. verify from your laptop
curl -i http://HOST/actuator/health      # expect 403 — actuator is not public
curl -X POST http://HOST/api/v1/shorten -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://www.wikipedia.org/"}'
```

### Firewall

Open **80** and **22** only. Do **not** expose 8080, 5433 or 6380 — the app
and its datastores should only be reachable through nginx and over loopback.

### Before you call it done

- `SHORTENER_BASE_URL` is the public address, or every `shortUrl` you hand out
  points at localhost.
- Re-run the spoofing test from Part A against the real host.
- `curl http://HOST/actuator/health` from outside should be **403**.
