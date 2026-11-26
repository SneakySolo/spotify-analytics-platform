# Architecture and flows

## System shape

The code is a synchronous Spring Boot 3.2.5 / Java 21 modular monolith. One process contains web pages, REST controllers, OAuth handling, token persistence, Spotify integration, analytics, and cache access. There are no message consumers, scheduled jobs, queues, separate services, or event classes in `src/main`.

```text
Browser + Thymeleaf/JS
        |
Spring Security filter chain (OAuth2, session, CSRF)
        |
PageController / MeController / CsrfController / WrapController
        |
Auth: OAuth handlers -> JPA -> PostgreSQL
        |
Wrap: Redis cache -> SpotifyTokenService -> SpotifyClient
                                      | six virtual-thread RestClient calls
                                      v
                               Spotify Web API
        |
AnalyticsService -> WrapResult -> Jackson JSON -> browser
```

## Startup and wiring

`SpotifyAnalyticsApplication.main` starts Spring. Component scanning discovers `@Service` classes (`AnalyticsService`, `SpotifyClient`, `SpotifyTokenService`, `TokenCryptoService`), controllers, OAuth handlers, the JPA entity/repository, and `SecurityConfig`.

`application.yaml` wires:

- PostgreSQL through `DATABASE_URL`, username, and password; JPA validates the schema.
- Flyway migrations, with `V1__users.sql` creating only `spotify_users`.
- Redis through `REDIS_URL`, two-second command timeout, Redis repositories disabled.
- Spotify OAuth registration/provider URLs and scopes.
- Thymeleaf cache, server port, wrap TTL, token encryption key.
- Actuator exposure for `health` and `info`, with health probes enabled.

The Docker Compose file provides PostgreSQL 15 on host port `55432` and Redis 7 on `6379`. Redis is configured as disposable cache (`--save "" --appendonly no`) with `volatile-ttl` eviction. The Dockerfile builds with Maven/Temurin 21, copies the jar to a non-root `spring` user, and exposes `8080`.

## Security flow

`SecurityConfig.securityFilterChain` installs CSRF with a browser-readable cookie repository; permits the page, health, OAuth, and CSRF/me routes; requires authentication for all other routes; configures OAuth2 login handlers; and logs out to `/` while invalidating the session and clearing authentication.

The authenticated identity used by application data is `OAuth2AuthenticationToken.getName()`. Spotify provider configuration sets `user-name-attribute: id`, so the identity matches the Spotify user ID and the database primary key/cache-key suffix.

Tokens are not kept as plaintext in the application database. `TokenCryptoService` uses AES-256-GCM with a fresh 12-byte nonce per encryption. The key is `TOKEN_ENCRYPTION_KEY`, Base64-encoded 32 bytes. Changing or losing the key prevents existing ciphertext from being decrypted.

## Request flows

### Home and login

`GET /` -> `PageController.home()` -> `index.html`. The button calls `GET /api/me`. A logged-in user goes to `/wrap?autogenerate=1`; an anonymous user starts `/oauth2/authorization/spotify`. Spring handles Spotify redirect/callback. `OAuthSuccessHandler` loads the authorized client, encrypts tokens, upserts `SpotifyConnection`, and redirects to `/wrap?autogenerate=1`. `OAuthFailureHandler` redirects to `/wrap?oauth_error=...`.

### Wrap generation cache hit

The page calls `GET /api/csrf`, then `POST /wraps` with the returned header. `WrapController.generate` reads `spotify_wrap:<authentication.getName()>`. A non-null value is parsed as JSON and returned without touching Postgres, token refresh, Spotify, or analytics. This means a cache hit can succeed even if Spotify or the database is currently unavailable.

### Wrap generation cache miss

`WrapController.generate` calls `SpotifyClient.fetch`. `SpotifyTokenService.validAccessToken` checks the durable row and expiry; it decrypts a usable access token or refreshes it through Spotify's `https://accounts.spotify.com/api/token`. `SpotifyClient` fans out to six Spotify Web API reads on virtual threads, awaits all, maps JSON into `SpotifySnapshot`, then returns it. `AnalyticsService.analyze` creates the `WrapResult`; the controller attempts a Redis `SET` with the configured TTL and returns HTTP 200.

### Failure flow

Missing/disconnected/invalid refresh token yields reauth-required 401. Spotify client failures yield a 502 in `WrapController`; its `SpotifyException.retryable` flag is not used by the controller's local mapping, so both retryable and non-retryable client exceptions become 502. Unexpected generation errors become 500. Redis read/write failures are swallowed. `ApiExceptionHandler` exists as a global fallback, but the controller catches its expected exceptions first.

## External and persistence boundaries

| Boundary | Code | Stored/sent data |
|---|---|---|
| Spotify OAuth | Spring registration + `OAuthSuccessHandler` | authorization code, access token, refresh token, Spotify profile ID |
| Spotify token endpoint | `SpotifyTokenService.refresh` | Basic client credentials, refresh token; returns new access token/expiry |
| Spotify Web API | `SpotifyClient` | bearer access token; profile, saved tracks, top tracks/artists, recent plays |
| PostgreSQL | `SpotifyConnectionRepository` | encrypted token ciphertext, expiry, status, display name, timestamps |
| Redis | `StringRedisTemplate` | JSON `WrapResult` under `spotify_wrap:<userId>`, TTL default 15 minutes |

There is no wrap-history table. Redis is a derived-data cache, not durable analytics storage.

## Analytics data flow

`SpotifySnapshot` is an in-memory record with profile, saved counts, long/short top tracks, long-term artists, and recent played tracks. `AnalyticsService` null-protects lists, deduplicates recent events by track ID/time, computes metrics, and returns `WrapResult`. `Metric` carries `value`, `unit`, `available`, and `note`, allowing missing estimates to be represented honestly.

The response contains 13 UI concepts: profile, saved count, top tracks, unique recent tracks, top artists, unique albums, recent and annualized time, library growth, hidden gems, taste diversity, taste evolution, replay intensity, and outro. `topGenres` is computed but excluded from JSON via `@JsonIgnore`.

## Concurrency and lifecycle

`SpotifyClient` uses `Executors.newVirtualThreadPerTaskExecutor()` for six blocking `RestClient` tasks. The request still waits synchronously on `Future.get()`. One task failure cancels all sibling futures; partial results are not supported. `@PreDestroy shutdown()` closes the executor.

There is no cache stampede lock, request timeout, circuit breaker, per-user refresh lock, explicit rate limiter, or `Retry-After` handling. 429 handling is three attempts with fixed 200/400 ms sleeps.

## What is tested

`SpotifyAnalyticsApplicationTests.contextLoads` checks Spring context startup. `TokenCryptoServiceTest` verifies AES round trips and that repeated encryption uses different ciphertext/nonces. `AnalyticsServiceTest` checks compatibility-shaped fixture ranking, normalized genres, fallback recent minutes, album selection, and non-null fixed personality. The repository does not contain live OAuth, live Spotify, live Redis TTL/cache, token refresh, controller integration, or concurrency/stampede tests.

