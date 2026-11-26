# Interview quick recall

Use these answers as a compact, code-backed explanation of the current repository. They describe what exists today, including deliberate limits and compatibility leftovers.

## Architecture

### What is the architecture?

It is a Spring Boot 3.2.5 / Java 21 modular monolith. Web controllers, OAuth, token persistence, Spotify integration, analytics, and Redis caching run in one process. The app handles a Wrap synchronously in `POST /wraps`.

### What happens on a normal request?

The browser loads `/`, checks `/api/me`, completes Spotify OAuth if needed, loads `/wrap`, obtains a CSRF token, and posts `/wraps`. `WrapController` checks a user-keyed Redis cache, then calls `SpotifyClient.fetch`, `AnalyticsService.analyze`, and returns `WrapResult` JSON.

### Why is generation synchronous?

There is no queue, job table, scheduler, or worker in the code. The caller waits for the five Spotify reads and analytics before receiving the response. This keeps the flow simple, but request latency includes external API time.

## Security and tokens

### How is `/wraps` protected?

`SecurityConfig` requires authentication for every non-public path and uses cookie-based CSRF. The page calls `/api/csrf` and sends the returned header on `POST /wraps`. An unauthenticated `/wraps` request is stopped with 401 before the controller.

### What identifies a user?

Spotify's provider config sets `user-name-attribute: id`, so `OAuth2AuthenticationToken.getName()` is the Spotify user id. It is the Postgres primary key and the suffix in `spotify_wrap:<id>`.

### How are OAuth tokens stored?

`TokenCryptoService` encrypts them with AES/GCM/NoPadding using a Base64-encoded 32-byte key, a new 12-byte nonce per encryption, and a 128-bit tag. The database stores `Base64(nonce):Base64(ciphertext+tag)`, never the raw token.

### What happens if the encryption key changes?

Existing ciphertext cannot be decrypted, so token use fails. The key must remain stable for the lifetime of the stored connections. An empty key can let startup complete, but the first encrypt/decrypt call throws.

### How does refresh work?

`SpotifyTokenService.validAccessToken` decrypts a stored access token only when it has more than 60 seconds left. Otherwise `refresh` sends a Basic-authenticated refresh-token request to Spotify, saves the new encrypted values, and returns the new access token. `invalid_grant` clears the row and returns a reauthentication response.

## Performance and concurrency

### What is parallel in this app?

`SpotifyClient.fetch` submits five independent Spotify calls to `Executors.newVirtualThreadPerTaskExecutor`: profile, long-term tracks, short-term tracks, long-term artists, and recently played. It waits for all five and cancels the remaining futures if one fails.

### Why use virtual threads?

These tasks spend most of their time waiting on HTTP. Virtual threads allow the blocking client calls to run concurrently with low thread overhead. They do not make Spotify itself faster, and the request still waits for the slowest task.

### Is the HTTP client pooled?

Yes. `SpotifyClient` creates one Apache HttpClient 5 `PoolingHttpClientConnectionManager` with 50 total connections and 20 per route. The shared `RestClient` reuses those connections, has a 2-second connection-request timeout and 15-second response timeout, and evicts expired connections. This is separate from the framework-managed database data source, whose pool size is not configured in this repository.

### How does load testing bypass cache?

Set `PERFORMANCE_DISABLE_WRAP_CACHE=true`. `WrapController` then skips both Redis GET and SET, so each request measures token handling, Spotify calls, and analytics. The default is false, which is the normal user-facing mode.

### What are the rate-limit semantics?

`SpotifyClient.get` retries HTTP 429 up to three attempts with 200 ms and 400 ms sleeps. It does not read `Retry-After`. After the final failure it returns `SpotifyException`; the controller responds 502.

## Data and analytics

### What data is fetched?

The current client calls `/v1/me`, top tracks for `long_term` and `short_term`, long-term top artists, and recently played tracks. Each asks for at most 50 items. There is no current saved-library `/me/tracks` request or saved-track growth metric.

### How is listening time calculated?

Recent events are deduplicated and sorted. An adjacent pair is accepted only when its positive gap is no larger than the current track duration plus 30 seconds. The estimate adds the smaller of the gap and duration. The annual metric averages usable observed UTC days and projects through December 1 (or year end after that date). It is an estimate, not Spotify's verified play duration.

### What does taste diversity mean here?

It is normalized Shannon entropy over artist-id counts in the long-term top-track sample, expressed as a percentage. A single artist scores 0%; missing artist ids make the metric unavailable.

### What does replay intensity mean?

It is `(valid recent event count - distinct recent track count) / valid event count * 100`. It measures repetition in the returned recent sample, not actual Spotify play counts.

### Are genres sent to the browser?

`AnalyticsService` calculates up to five raw genre counts, but `WrapResult.topGenres` is annotated `@JsonIgnore`. The current JSON response and UI do not expose them. `normalizeGenre` remains as a compatibility helper and maps broad labels such as trap, pop, rock, and dance.

## Failure handling and trade-offs

### What if Redis is down?

Redis GET/SET exceptions are swallowed in `WrapController`. A read failure becomes a cache miss; a write failure does not fail a successful Wrap. This favors availability, at the cost of doing fresh Spotify work.

### What if one parallel Spotify call fails?

`SpotifyClient.fetch` cancels all five futures and rethrows. The controller returns a 502 for `SpotifyException`; partial snapshots are not analyzed.

### What if recent data is incomplete?

Invalid `played_at` timestamps are skipped. Missing ids, null lists, too few events, or no plausible intervals produce unavailable metrics with explanatory `note` values. The UI renders “Unavailable” or a fallback message.

### What if the access token is missing or disconnected?

`validAccessToken` throws `ReauthRequiredException`; `WrapController` returns 401 with `reauth_required`, and `wrap.html` shows a reconnect action.

### Why are there two exception response mechanisms?

`ApiExceptionHandler` declares global advice mappings, while `WrapController.generate` catches its own known exceptions and returns local maps. For `/wraps`, the local controller mappings normally win. This duplication is a maintenance smell worth mentioning in an interview.

### What are the main trade-offs?

- Synchronous generation is easy to follow and returns a complete result, but external latency is on the request path.
- Five parallel reads reduce wall-clock time, but increase concurrent load against Spotify and depend on rate limits.
- A pooled shared HTTP client avoids connection setup per call, while fixed pool limits can still become a bottleneck under enough simultaneous users.
- Redis improves repeat latency but is intentionally disposable; it is not the source of truth.
- Encrypted tokens improve database safety, but key loss makes existing connections unusable.
- The code asks for fixed 50-item samples and does not paginate these endpoints, so analytics are based on a bounded sample.

## Code-quality details to be ready to explain

### Why is `open-in-view` false?

The app does not rely on a database session remaining open through view rendering. The result is assembled before the controller returns.

### What does shutdown do?

`SpotifyClient.shutdown()` closes the virtual-thread executor and pooled Apache HTTP client through `@PreDestroy`, preventing those resources from outliving the Spring context.

### What is deliberately incomplete or legacy?

`WrapResult` keeps compatibility accessors from the earlier API. `albumConcentration`, `artistLoyalty`, `familiarityRatio`, and `discoveryRatio` return 0; `risingStar` returns null; `personality()` returns fixed text. `topGenres` is calculated but ignored during JSON serialization. These are facts to call out rather than presenting them as richer behavior than the code provides.

### What tests exist?

The tests cover context startup, AES-GCM round trips with unique nonces, genre normalization, ranking/personality compatibility, taste-change math, and recent-listening fixture behavior. There are no repository-wide integration tests for real Spotify, Redis, or the parallel HTTP client.

## A short interview answer

“This is a Java 21 Spring Boot modular monolith. OAuth creates a session and stores encrypted Spotify tokens in Postgres. A CSRF-protected `/wraps` request first checks a user-scoped Redis cache. On a miss, one shared pooled Apache HTTP client makes five Spotify reads concurrently on virtual threads. The results become a `SpotifySnapshot`, `AnalyticsService` turns that into a `WrapResult`, and the JSON is returned and optionally cached. The design is intentionally synchronous and sample-based: there is no background job, the five Spotify endpoints are limited to 50 items each, and cache loss simply causes regeneration.”
