# Spotify Analytics Platform

An on-demand Spotify listening story built with Spring Boot, Java 21, and a small browser UI.

Connect Spotify, click **Reveal my wrap**, and the app pulls a fresh snapshot of your current listening data, turns it into a set of analytics, and presents the result as a 13-slide story. The project is deliberately small enough to follow from the browser click all the way to the Spotify API call, but it still includes the pieces that matter in a real service: OAuth, encrypted token storage, CSRF protection, Redis caching, HTTP connection pooling, parallel I/O, timeouts, retries, and load-test support.

## What it does

- Connects a Spotify account with OAuth 2.0 and keeps the access/refresh tokens encrypted in PostgreSQL.
- Fetches the Spotify profile, long-term and short-term top tracks, long-term top artists, and recently played tracks.
- Runs the five independent Spotify reads concurrently on Java 21 virtual threads.
- Reuses connections through a pooled Apache HttpClient (`50` total connections, `20` per route).
- Calculates listening time estimates, annualized listening time, top tracks/artists, unique tracks/albums, listening routine, comfort artist, hidden gems, taste diversity, taste changes, and replay intensity.
- Caches each user's generated Wrap in Redis for 15 minutes by default.
- Provides `PERFORMANCE_DISABLE_WRAP_CACHE=true` for measuring the real generation path during load tests.
- Returns useful failure states when Spotify is rate-limiting, a token needs reauthentication, or recent data is too sparse for a metric.

## A quick look at the request path

```text
Browser
  -> Spring Security (session + OAuth2 + CSRF)
  -> WrapController
  -> Redis cache (user-specific, optional)
  -> SpotifyTokenService (decrypt or refresh token)
  -> SpotifyClient (five parallel calls through one pooled HTTP client)
  -> SpotifySnapshot
  -> AnalyticsService
  -> WrapResult JSON
  -> 13-slide browser UI
```

The request is synchronous: the browser waits for the complete result. There is no queue, scheduled job, or background worker. A warm Redis hit returns before any Spotify call is made.

## Analytics in the Wrap

The calculations are intentionally transparent and sample-based:

- Recent listening time uses plausible gaps between consecutive Spotify timestamps and caps each gap at the current track duration.
- Annual listening time averages usable observed UTC days and projects that average through the early-December Wrap date.
- Taste diversity is normalized Shannon entropy across artists in the long-term top-track sample.
- Taste changes compare long-term and short-term track-id sets with a Jaccard-style difference.
- Replay intensity measures repetition in the recent sample; it is not Spotify's lifetime play count.
- Missing or insufficient data produces an unavailable metric with a reason instead of an invented number.

## Stack

- Java 21
- Spring Boot 3.2.5
- Spring MVC + Thymeleaf
- Spring Security OAuth2 Client
- Spring Data JPA + Flyway
- PostgreSQL 15
- Redis 7
- Apache HttpClient 5
- Docker Compose
- k6 load-test script

## Run locally

### Prerequisites

- Java 21
- Docker Desktop (for PostgreSQL and Redis)
- A Spotify Developer application
- Git Bash if you want to use the commands below exactly as written

### 1. Start the local services

```bash
docker compose up -d postgres redis
```

PostgreSQL is exposed on `localhost:55432`; Redis is exposed on `localhost:6379`. The PostgreSQL data is kept in the named `postgres-data` volume.

### 2. Configure Spotify and token encryption

Register this callback URL in the Spotify Developer Dashboard:

```text
http://127.0.0.1:8080/login/oauth2/code/spotify
```

Then export the values in the same shell that will run Maven:

```bash
export SPOTIFY_CLIENT_ID='your-client-id'
export SPOTIFY_CLIENT_SECRET='your-client-secret'
export SPOTIFY_REDIRECT_URI='http://127.0.0.1:8080/login/oauth2/code/spotify'
export TOKEN_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

Keep the same `TOKEN_ENCRYPTION_KEY` when restarting an existing local database. Changing it makes previously stored Spotify ciphertext impossible to decrypt.

The local defaults already point to the Compose services, so you only need to override the database or Redis variables if you are using different services.

### 3. Start the app

```bash
./mvnw spring-boot:run
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080), connect Spotify, and reveal your Wrap.

Flyway creates the `spotify_users` table at startup. Hibernate validates that schema rather than creating tables itself.

## Environment variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `SPOTIFY_CLIENT_ID` | yes | - | Spotify OAuth client id |
| `SPOTIFY_CLIENT_SECRET` | yes | - | Spotify OAuth client secret |
| `SPOTIFY_REDIRECT_URI` | yes | `http://127.0.0.1:8080/login/oauth2/code/spotify` | OAuth callback registered with Spotify |
| `TOKEN_ENCRYPTION_KEY` | yes | - | Base64 key that decodes to exactly 32 bytes |
| `DATABASE_URL` | no locally | `jdbc:postgresql://localhost:55432/spotify_analytics` | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | no locally | `postgres` | PostgreSQL username |
| `DATABASE_PASSWORD` | no locally | `postgres` | PostgreSQL password |
| `REDIS_URL` | no locally | `redis://localhost:6379` | Redis connection URL |
| `WRAP_CACHE_TTL` | no | `15m` | Redis TTL for generated Wrap JSON |
| `PERFORMANCE_DISABLE_WRAP_CACHE` | no | `false` | Skip Redis reads/writes when `true` |
| `THYMELEAF_CACHE` | no | `true` | Thymeleaf template caching |
| `PORT` | no | `8080` | HTTP server port |

`.env.example` contains the local variable names and safe placeholders. Secrets are not committed to the repository.

## API surface

| Method | Route | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/` | public | Home page |
| `GET` | `/api/me` | public | Reports whether the current session is connected to Spotify |
| `GET` | `/oauth2/authorization/spotify` | public | Starts Spotify OAuth |
| `GET` | `/api/csrf` | public | Returns the CSRF token used by the browser |
| `GET` | `/wrap` | public | Wrap page shell |
| `POST` | `/wraps` | authenticated + CSRF | Reads/cache-generates the current user's Wrap |
| `GET` | `/actuator/health` | public | Health check |

`POST /wraps` returns `401` when the connection needs to be restored, `502` for Spotify/upstream failures, and `500` for an unexpected generation error.

## Load testing the generation path

The cache is useful for normal browsing, but it hides the work you may want to measure. Set this before starting the app:

```bash
export PERFORMANCE_DISABLE_WRAP_CACHE=true
./mvnw spring-boot:run
```

The repository includes [`performance/k6/uncached-wrap.js`](performance/k6/uncached-wrap.js). It expects an authenticated session cookie and CSRF token because OAuth is intentionally kept out of the load script:

```bash
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e SESSION_COOKIE='your-session-cookie' \
  -e CSRF_TOKEN='your-csrf-token' \
  -e VUS=5 \
  -e ITERATIONS=20 \
  performance/k6/uncached-wrap.js
```

The script defaults to one virtual user, 20 iterations, a one-second pause between requests, and thresholds of p50 under 10 seconds and p95 under 15 seconds. Spotify rate limits and the account's available recent data still affect the result.

## Verify the project

```bash
./mvnw test
```

The test suite covers application startup, AES-GCM token round trips and nonce uniqueness, genre normalization, ranking compatibility, taste-change math, and analytics fixture behavior. It does not call the live Spotify API.

## Stop and restart local services

```bash
docker compose stop    # stop containers, keep data
docker compose start   # start them again
```

Use `docker compose down` when you want to remove the containers. The named PostgreSQL volume remains unless you explicitly remove it.

## Deployment

The root [`Dockerfile`](Dockerfile) builds the application with Maven and runs it on Eclipse Temurin 21 as a non-root `spring` user. It listens on the `PORT` supplied by the platform.

For Render or another container host, provide the Spotify credentials, a stable 32-byte Base64 encryption key, PostgreSQL connection details, and Redis URL. Point the health check at `/actuator/health`, and register the deployed value of `SPOTIFY_REDIRECT_URI` in Spotify's dashboard.

Redis is a performance cache, not durable application storage. Losing it after a restart is expected; the next Wrap simply fetches Spotify data again. PostgreSQL stores the encrypted connection state.

## Current scope

This version intentionally works with a bounded Spotify sample: the four collection endpoints request up to 50 items each, alongside one profile request. It does not fetch saved-library tracks, build a Wrap history table, paginate these endpoints, or run generation as a background job. Those limits keep the request path understandable and make the performance characteristics easier to measure.

## Documentation

- [`docs/A-Z-WALKTHROUGH.md`](docs/A-Z-WALKTHROUGH.md) - complete request trace from browser click to JSON response
- [`docs/ARCHITECTURE-AND-FLOWS.md`](docs/ARCHITECTURE-AND-FLOWS.md) - system shape, security, storage, caching, pooling, and concurrency
- [`docs/CLASS-AND-FUNCTION-REFERENCE.md`](docs/CLASS-AND-FUNCTION-REFERENCE.md) - class-by-class responsibilities and call relationships
- [`docs/INTERVIEW-QUICK-RECALL.md`](docs/INTERVIEW-QUICK-RECALL.md) - concise explanations of design choices, trade-offs, and edge cases
