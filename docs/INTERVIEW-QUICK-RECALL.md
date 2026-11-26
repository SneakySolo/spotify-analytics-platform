# Interview quick recall

These answers describe the current repository, including limitations. Do not claim features that are absent.

## Architecture

**What is the architecture?** A synchronous Spring Boot modular monolith. Web, auth, persistence, Spotify integration, caching, and analytics are modules in one process. A Wrap request is not a job submitted to a queue.

**What starts the app?** `SpotifyAnalyticsApplication.main` calls `SpringApplication.run`. Flyway creates `spotify_users`; JPA validates the schema; Spring wires controllers/services/security; the server listens on `PORT` or 8080.

**Why is Redis separate from Postgres?** Postgres durably stores encrypted Spotify connection state. Redis stores derived `WrapResult` JSON for a default 15-minute TTL. Losing Redis regenerates data; losing the encryption key makes stored tokens undecryptable.

## Request tracing

**What happens after clicking the home button?** `index.html` calls `/api/me`. Anonymous users start `/oauth2/authorization/spotify`; authenticated users open `/wrap`. `wrap.html` obtains `/api/csrf`, then POSTs `/wraps`.

**What protects `/wraps`?** `SecurityConfig.securityFilterChain` requires authentication for non-permitted routes and CSRF protection is enabled. `WrapController` receives `OAuth2AuthenticationToken` only after the filter chain admits the request.

**What is the cache key?** `spotify_wrap:` plus `authentication.getName()`. Because the Spotify provider uses `user-name-attribute: id`, this is the Spotify user ID, not browser input.

**Does a cache hit call Spotify?** No. It returns parsed Redis JSON before token lookup, refresh, Spotify calls, or analytics.

## OAuth and tokens

**Where are tokens stored?** `SpotifyConnection` maps to `spotify_users`; access and refresh tokens are ciphertext. `OAuthSuccessHandler` creates or updates the row.

**How are tokens encrypted?** `TokenCryptoService` uses AES/GCM/NoPadding, AES-256 from a 32-byte Base64 key, and a fresh 12-byte nonce per encryption. The stored format is Base64 nonce plus Base64 ciphertext/tag.

**When does refresh happen?** `SpotifyTokenService.validAccessToken` decrypts the access token only when its expiry is more than 60 seconds away. Otherwise `refresh` posts a refresh grant to Spotify and saves the new encrypted token.

**What happens on `invalid_grant`?** The connection is disconnected, ciphertext and expiry are cleared, and a reauth-required error becomes HTTP 401 from `WrapController`.

## Spotify and performance

**How many Spotify calls happen on a cold miss?** Six logical tasks: profile, saved tracks, long-term top tracks, short-term top tracks, long-term top artists, and recently played. Saved tracks may make multiple paged calls.

**Why virtual threads?** The code uses Java 21 `newVirtualThreadPerTaskExecutor()` around blocking `RestClient` calls. It overlaps independent network waits while keeping imperative code. It is not WebFlux and is not a Spring-managed executor.

**Are calls truly asynchronous to the user?** No. The endpoint is synchronous and calls `Future.get()` for all required results. The browser waits for the complete Wrap.

**What happens if one Spotify call fails?** `await` propagates the failure; `fetch` cancels sibling futures; no partial snapshot is returned. The controller maps `SpotifyException` to 502.

**How are 429s handled?** `SpotifyClient.get` retries up to three attempts with fixed 200 ms and 400 ms sleeps. It does not use Spotify's `Retry-After` header, jitter, circuit breaking, or a global rate limiter.

## Analytics honesty

**Is listening time exact?** No. `estimatedTime` infers plausible adjacent intervals from recent-play timestamps and caps each interval at the current track duration. Annual time averages observed UTC-day estimates and projects them through December 1 or the end of the year.

**What does replay intensity mean?** `(valid recent events - distinct track IDs) / valid recent events * 100`. It is repetition in the available 50-item recent sample, not Spotify play counts.

**How are taste scores calculated?** Diversity is normalized Shannon entropy over artist IDs in long-term top tracks. Evolution is `100 * (1 - intersection/union)` of long- and short-term track IDs. Empty inputs produce unavailable metrics where implemented.

**Is genre data in the response?** `AnalyticsService` computes `topGenres`, but `WrapResult.topGenres` is `@JsonIgnore`, and the current template does not render it.

**What is stubbed?** Compatibility methods on `WrapResult`: album concentration, rising star, artist loyalty, familiarity ratio, and discovery ratio return `0`/`null`. `personality()` returns a fixed `Personality`, not a data-driven classification.

## Failure and edge cases

**What if Redis is down?** GET/parse failures are swallowed, so the request generates fresh data. SET failures are swallowed, so the current response still succeeds. This preserves availability but can hide cache degradation and permit repeated upstream calls.

**What if tokens are missing?** Missing row or `DISCONNECTED` state raises `ReauthRequiredException`, mapped locally to 401.

**What if the database is down?** Token lookup/refresh fails before Spotify generation and falls into the controller's generic 500 unless the failure is otherwise mapped.

**What if recent data is sparse?** Invalid/null events are discarded. Metrics carry `available=false` and a note; the template renders `Unavailable` or `Not enough data`. The compatibility test's one-event fixture falls back to top-track duration for `recentListeningMinutes`, but the current UI reads the newer metric fields.

**Are duplicate cold misses prevented?** No. GET then generate then SET has no lock, `SETNX`, or single-flight mechanism. Simultaneous misses can each perform six Spotify fan-out tasks.

## Trade-offs and next improvements

**Why no queue?** The product contract is immediate on-demand generation and the code is small. A queue would require job state, polling/status UX, retries, and idempotency. The trade-off is request latency and weaker isolation.

**What would you fix first at higher traffic?** Add per-user single-flight/cache locking, bounded concurrency, endpoint timeouts, `Retry-After`-aware backoff, refresh-token serialization/versioning, metrics for cache/error rates, and possibly background generation.

**What is the data-retention story?** There is no Wrap history table. Postgres retains connection row/token ciphertext; Redis retains derived JSON only until eviction/TTL. The migration has no RLS or secondary indexes beyond the primary key.

**What tests are missing?** Live OAuth callback, token refresh/invalid grant, Redis hit/miss/TTL, Spotify JSON mapping, controller status codes, 429 retry behavior, cache stampede, and end-to-end authenticated generation. Existing tests cover context startup, crypto round trips/nonce uniqueness, and a small analytics fixture.

**How would you prove the virtual-thread choice?** Compare against a sequential implementation with the same controlled upstream latency, separately measure cold and warm cache, and report p50/p95/p99, throughput, 429s, errors, Redis hit rate, and connection/heap usage. The repository contains no measured benchmark result proving an improvement.

