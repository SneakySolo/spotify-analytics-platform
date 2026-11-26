# Architecture and flows

## System shape

The repository is a synchronous Spring Boot 3.2.5 / Java 21 modular monolith. One JVM contains the web pages, REST endpoints, Spring Security OAuth flow, token persistence, Spotify integration, analytics, and Redis cache.

```text
Browser + Thymeleaf/JavaScript
            |
Spring Security filter chain (session, OAuth2, CSRF)
            |
PageController / MeController / CsrfController / WrapController
            |
      +-----+------------------------+
      |                              |
 OAuth success/failure          Wrap request
      |                              |
 JPA -> PostgreSQL       Redis result cache (optional)
                                      |
                             SpotifyTokenService
                                      |
                             SpotifyClient
                         pooled HTTP + 5 virtual tasks
                                      |
                               Spotify Web API
                                      |
                             AnalyticsService
                                      |
                           WrapResult -> JSON/UI
```

There are no message queues, scheduled jobs, event listeners, repository abstractions for Redis, or separate deployable services in `src/main`.

## Startup and configuration wiring

`SpotifyAnalyticsApplication.main` calls `SpringApplication.run`. Component scanning finds the controllers, `@Service` beans, `@Configuration` security class, JPA entity, and Spring Data repository. Auto-configuration supplies MVC, Jackson, Thymeleaf, a JDBC data source, JPA, Redis, OAuth2 client, and Actuator.

### Configuration in `application.yaml`

| Area | Configuration | Code use |
| --- | --- | --- |
| HTTP server | `PORT`, default `8080` | embedded server |
| PostgreSQL | `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`; local URL defaults to `jdbc:postgresql://localhost:55432/spotify_analytics` | JPA/Flyway persistence |
| JPA | `open-in-view: false`, `ddl-auto: validate` | validates the Flyway schema; does not generate tables |
| Flyway | enabled, `baseline-on-migrate: true` | runs `db/migration/V1__users.sql` |
| Redis | `REDIS_URL`, 2-second command timeout, repositories disabled | `StringRedisTemplate` in `WrapController` |
| Spotify OAuth | client id/secret, redirect URI, authorization-code grant, four scopes | Spring OAuth2 client |
| Thymeleaf | `THYMELEAF_CACHE` | template caching |
| Wrap cache | `WRAP_CACHE_TTL`, default `15m` | Redis write TTL |
| Load-test switch | `PERFORMANCE_DISABLE_WRAP_CACHE`, default `false` | `WrapController.disableWrapCache` |
| Encryption | `TOKEN_ENCRYPTION_KEY` | AES-256-GCM token protection |
| Health | Actuator exposes `health,info`; probes enabled | `/actuator/health` |

The local `docker-compose.yml` starts PostgreSQL 15 on host port `55432` and Redis 7 on `6379`. Redis is configured as disposable cache (`--save "" --appendonly no`) with `volatile-ttl` eviction. The Dockerfile builds with Maven/Temurin 21, copies the jar into a non-root `spring` user image, and exposes `8080`.

The JPA starter provides the normal Spring Boot JDBC pool. No `spring.datasource.hikari.*` values are set here, so pool sizing is framework/environment configuration rather than an application-specific number. Spotify HTTP pooling is explicit and described below.

## Security flow

`SecurityConfig.securityFilterChain(HttpSecurity)` configures:

- CSRF with `CookieCsrfTokenRepository.withHttpOnlyFalse()`, allowing JavaScript to read the CSRF cookie/token pair.
- Public paths: `/`, `/wrap`, `/api/me`, `/api/csrf`, `/css/**`, `/js/**`, `/actuator/health`, `/oauth2/**`, and `/login/**`.
- Authentication for every other path, including `POST /wraps`.
- A `401` `HttpStatusEntryPoint` specifically for unauthenticated `/wraps` requests.
- OAuth2 success/failure handlers: `OAuthSuccessHandler` and `OAuthFailureHandler`.
- Logout to `/`, invalidating the HTTP session and clearing authentication.

The authenticated name is the Spotify id because the provider sets `user-name-attribute: id`. That same id becomes the Postgres primary key and the suffix of the Redis key.

## OAuth and token storage flow

1. The browser starts `/oauth2/authorization/spotify`.
2. Spring redirects to Spotify with scopes from `application.yaml`.
3. Spotify redirects to the configured `/login/oauth2/code/spotify` callback.
4. Spring exchanges the code and creates an `OAuth2AuthenticationToken` plus an `OAuth2AuthorizedClient`.
5. `OAuthSuccessHandler` loads the authorized client, reads the Spotify profile id/display name, encrypts access and refresh tokens through `TokenCryptoService`, and inserts or updates `SpotifyConnection` through `SpotifyConnectionRepository`.
6. The handler redirects to `/wrap?autogenerate=1`.

`TokenCryptoService` uses AES/GCM/NoPadding, a 32-byte decoded key, a new 12-byte nonce per encryption, and a 128-bit tag. The stored format is `Base64(nonce):Base64(ciphertext+tag)`. Losing or changing the key makes existing ciphertext undecryptable. `SpotifyConnection.disconnect()` removes ciphertext and marks the row `DISCONNECTED` after an `invalid_grant` refresh failure.

## Browser request flow

### Home/login

`GET /` is served by `PageController.home()` and rendered from `templates/index.html`. The start button calls `GET /api/me`:

- `MeController.me` returns `200` and the Spotify id for an authenticated session.
- It returns `401` and `authenticated:false` for anonymous, missing, or unauthenticated state.
- JavaScript then chooses `/wrap?autogenerate=1` or starts OAuth.

### Wrap generation

`GET /wrap` is served by `PageController.wrap()` and rendered from `templates/wrap.html`. The script first calls `GET /api/csrf`, then sends `POST /wraps` with the returned CSRF header. The `autogenerate` query parameter is not read by the page script; generation occurs on every load unless an `oauth_error` parameter causes an error screen.

## Cache flow

`WrapController.generate` uses the key `spotify_wrap:<authentication.getName()>`.

With caching enabled (the default):

1. Redis GET runs before token lookup or Spotify calls.
2. A valid JSON value is parsed with Jackson and returned immediately.
3. A miss, Redis outage, or malformed value falls through to fresh generation.
4. A successful `WrapResult` is serialized and written with `WRAP_CACHE_TTL`.
5. Redis read/write failures are ignored so the cache cannot be the reason generation fails.

With `PERFORMANCE_DISABLE_WRAP_CACHE=true`, the controller skips both GET and SET. This is useful for load testing the generation path, but it means every request reaches token handling, Spotify, and analytics.

## Spotify request flow, parallelism, and connection pooling

`SpotifyClient` creates one shared `RestClient` in its constructor. That client uses Apache HttpClient 5 through `HttpComponentsClientHttpRequestFactory` and a `PoolingHttpClientConnectionManager` configured with:

- `maxConnTotal = 50`
- `maxConnPerRoute = 20`
- connection-request timeout = 2 seconds
- response timeout = 15 seconds
- expired-connection eviction

`SpotifyClient.fetch` resolves one access token, then submits five tasks to a single `Executors.newVirtualThreadPerTaskExecutor()`:

1. `profile` -> `/me`
2. `topTracks(long_term)` -> `/me/top/tracks?time_range=long_term&limit=50`
3. `topTracks(short_term)` -> `/me/top/tracks?time_range=short_term&limit=50`
4. `topArtists` -> `/me/top/artists?time_range=long_term&limit=50`
5. `recentlyPlayed` -> `/me/player/recently-played?limit=50`

The tasks share the pooled HTTP client, so concurrent calls can reuse connections. `timed` logs endpoint duration and outcome. The controller waits for all futures with `await`; if one fails, the other five futures are cancelled and the error is rethrown. The client closes the virtual-thread executor and pooled HTTP client from `@PreDestroy shutdown()`.

`SpotifyClient.get` sends the bearer token, parses object JSON, and retries only HTTP 429 responses, up to three attempts with 200 ms and 400 ms sleeps. It does not inspect `Retry-After`. Invalid JSON shape, network failures, and exhausted retries become `SpotifyException`.

The token refresh call is separate: `SpotifyTokenService` owns a `RestClient` based at `https://accounts.spotify.com`, sends Basic client authentication to `/api/token`, encrypts replacement tokens, and saves them.

## Analytics flow

`SpotifyClient` maps JSON into `SpotifySnapshot` records (`Profile`, `Artist`, `Track`, and `PlayedTrack`). `AnalyticsService.analyze` then:

1. null-protects lists and deduplicates recent events by track id plus timestamp;
2. limits visible long-term tracks and artists to five;
3. estimates recent and annualized listening time from plausible adjacent timestamps;
4. counts distinct recent tracks and albums;
5. calculates routine, comfort artist, raw top genres, hidden gems, diversity, taste changes, and replay intensity;
6. builds `WrapResult`.

`WrapResult.topGenres` is annotated `@JsonIgnore`, so genre data exists in memory but is not emitted to the browser. Several older compatibility accessors remain intentionally stubbed (`0`/`null`).

## Persistence model

Flyway migration `V1__users.sql` creates one table:

```text
spotify_users
  spotify_user_id             primary key
  display_name
  access_token_ciphertext
  refresh_token_ciphertext
  access_token_expires_at
  connection_status            CONNECTED or DISCONNECTED
  created_at
  updated_at
```

`SpotifyConnection` is the JPA entity. `@PrePersist` and `@PreUpdate` maintain timestamps. `SpotifyConnectionRepository` is a plain `JpaRepository<SpotifyConnection, String>`; there are no custom query methods.

Redis is not durable application storage. Compose disables persistence, and the code treats cache loss as a normal miss. Postgres is the durable store for encrypted Spotify connection state.

## Error and response flow

`WrapController.generate` returns:

| Condition | HTTP | JSON code |
| --- | ---: | --- |
| missing/disconnected/invalid-refresh connection | 401 | `reauth_required` |
| Spotify/network/rate-limit failure | 502 | `spotify_error` |
| any other generation exception | 500 | `generation_failed` |
| success | 200 | serialized `WrapResult` |

`ApiExceptionHandler` also defines advice mappings. Because `WrapController.generate` catches its own known exceptions, its local response normally handles this endpoint. `wrap.html` displays a reconnect screen for 401 and a message for other failures.

## What is intentionally not present

- No `/v1/me/tracks` saved-library fetch or saved-track growth metric.
- No async job queue or persisted Wrap history.
- No Spotify API pagination in the current five calls; each top/recent request asks for at most 50 items.
- No custom database pool-size configuration.
- No server-side rendering of the 13 result cards; the page builds them in JavaScript from JSON.
