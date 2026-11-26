# A-Z walkthrough: one Spotify Wrap request

This is the current execution trace from the source under `src/main`. A Wrap is generated synchronously by `POST /wraps`. There is no queue, scheduled job, event consumer, or background worker. Spotify reads are parallel inside that request, and the Redis result cache can avoid them on later requests.

## Call-chain tree

```text
JVM
`-- SpotifyAnalyticsApplication.main()
    `-- SpringApplication.run()
        `-- SecurityFilterChain + controllers + services + repositories

Browser GET /
`-- PageController.home() -> templates/index.html
    `-- GET /api/me -> MeController.me()
        |-- already authenticated -> GET /wrap?autogenerate=1
        `-- anonymous -> /oauth2/authorization/spotify
            `-- Spotify consent and code exchange
                `-- /login/oauth2/code/spotify
                    `-- OAuthSuccessHandler.onAuthenticationSuccess()
                        `-- OAuth2AuthorizedClientService.loadAuthorizedClient()
                        `-- TokenCryptoService.encrypt()
                        `-- SpotifyConnectionRepository.findById()/save()
                        `-- redirect to /wrap?autogenerate=1

Browser GET /wrap
`-- PageController.wrap() -> templates/wrap.html
    `-- JavaScript generate()
        |-- GET /api/csrf -> CsrfController.csrf()
        `-- POST /wraps (session + CSRF header)
            `-- WrapController.generate()
                |-- Redis GET spotify_wrap:<spotify user id> (unless cache disabled)
                |   `-- hit -> cached JSON HTTP 200
                `-- miss/error -> SpotifyClient.fetch()
                    |-- SpotifyTokenService.validAccessToken()
                    |   `-- decrypt stored token OR refresh at Spotify /api/token
                    |-- five virtual-thread tasks in parallel
                    |   |-- GET /v1/me
                    |   |-- GET /v1/me/top/tracks?time_range=long_term&limit=50
                    |   |-- GET /v1/me/top/tracks?time_range=short_term&limit=50
                    |   |-- GET /v1/me/top/artists?time_range=long_term&limit=50
                    |   `-- GET /v1/me/player/recently-played?limit=50
                    |-- await all futures -> SpotifySnapshot
                    `-- AnalyticsService.analyze(snapshot) -> WrapResult
                        `-- Jackson JSON + optional Redis SET -> HTTP 200
```

## 1. The application starts

**Caller -> Callee:** JVM -> `SpotifyAnalyticsApplication.main(String[] args)`  
**Takes in:** command-line arguments.  
**Does:** calls `SpringApplication.run(SpotifyAnalyticsApplication.class, args)`. Spring Boot creates the application context, embedded server, MVC controllers, Security filter chain, JPA/Flyway database wiring, Redis client, OAuth client, Thymeleaf renderer, and service beans.  
**Returns/passes next:** a running HTTP application, port `8080` by default (`server.port` comes from `PORT`).

`application.yaml` supplies PostgreSQL (`DATABASE_URL`, username, password), Redis (`REDIS_URL`), Spotify OAuth values, `WRAP_CACHE_TTL`, `PERFORMANCE_DISABLE_WRAP_CACHE`, `THYMELEAF_CACHE`, `TOKEN_ENCRYPTION_KEY`, and `PORT`. Flyway runs `V1__users.sql`, which creates the `spotify_users` table. Hibernate is set to `ddl-auto: validate`, so JPA validates the schema rather than creating it. The JPA starter supplies the normal Spring Boot JDBC data-source setup; this repository does not set explicit Hikari pool sizes.

`TokenCryptoService` validates a non-empty encryption key at bean construction: it must be Base64 that decodes to exactly 32 bytes. An empty key does not fail startup, but any later encrypt/decrypt call fails with `TOKEN_ENCRYPTION_KEY is required`.

## 2. The browser opens the home page

**Caller -> Callee:** browser `GET /` -> `PageController.home()`  
**Takes in:** no application parameters.  
**Does:** returns the Thymeleaf view name `index`.  
**Returns/passes next:** `templates/index.html` is rendered.

`SecurityConfig.securityFilterChain(HttpSecurity)` permits `/`, `/wrap`, `/api/me`, `/api/csrf`, `/css/**`, `/js/**`, `/oauth2/**`, `/login/**`, and `/actuator/health`. The page is therefore visible before login.

The `#start-wrap` button in `index.html` calls `fetch('/api/me')`. A successful authenticated response sends the browser to `/wrap?autogenerate=1`. Any unauthenticated response or fetch failure sends it to `/oauth2/authorization/spotify`.

## 3. The browser checks login state

**Caller -> Callee:** `index.html` `fetch('/api/me')` -> `MeController.me(Authentication authentication)`  
**Takes in:** Spring Security's `Authentication`, which can be null, anonymous, or authenticated.  
**Does:** treats null, `!isAuthenticated()`, and `AnonymousAuthenticationToken` as not connected.  
**Returns/passes next:** HTTP `401` with `{"authenticated":false,"message":"Connect Spotify to continue."}`, or HTTP `200` with `{"authenticated":true,"spotifyUserId":authentication.getName()}`.

The identity is the Spotify user id because the OAuth provider sets `user-name-attribute: id`.

## 4. OAuth connects Spotify and stores tokens

**Caller -> Callee:** browser `/oauth2/authorization/spotify` -> Spring Security OAuth2 client flow  
**Takes in:** the `spotify` registration from `application.yaml`: authorization-code grant, configured client id/secret/redirect URI, and scopes `user-read-private`, `user-read-email`, `user-top-read`, and `user-read-recently-played`.  
**Does:** redirects to Spotify; Spotify returns an authorization code; Spring exchanges it at `https://accounts.spotify.com/api/token`, loads the user profile from `https://api.spotify.com/v1/me`, and creates the authenticated session.  
**Returns/passes next:** success or failure handler execution.

**Caller -> Callee:** Spring success flow -> `OAuthSuccessHandler.onAuthenticationSuccess(...)`  
**Takes in:** an `OAuth2AuthenticationToken`.  
**Does:** calls `OAuth2AuthorizedClientService.loadAuthorizedClient("spotify", login.getName())`; reads `id` and `display_name` from `OAuth2User`; encrypts the access token and optional refresh token; looks up `SpotifyConnectionRepository.findById(userId)`; inserts a new `SpotifyConnection` or calls `SpotifyConnection.updateTokens(...)`; saves it.  
**Returns/passes next:** redirect to `/wrap?autogenerate=1`. A missing Spotify `id` throws `IllegalStateException`.

**Caller -> Callee:** `OAuthSuccessHandler` -> `TokenCryptoService.encrypt(String plaintext)`  
**Takes in:** a raw OAuth token.  
**Does:** uses AES/GCM/NoPadding with a fresh 12-byte random nonce and a 128-bit authentication tag.  
**Returns/passes next:** `Base64(nonce):Base64(ciphertext+tag)`, stored in Postgres columns `access_token_ciphertext` and `refresh_token_ciphertext`.

**Caller -> Callee:** Spring failure flow -> `OAuthFailureHandler.onAuthenticationFailure(...)`  
**Takes in:** an `AuthenticationException`.  
**Does:** uses the OAuth error code when available, otherwise `login_failed`, URL-encodes it, and redirects.  
**Returns/passes next:** `/wrap?oauth_error=<code>`.

## 5. The Wrap page loads

**Caller -> Callee:** browser `GET /wrap` -> `PageController.wrap()`  
**Takes in:** no controller argument.  
**Does:** returns the view name `wrap`.  
**Returns/passes next:** `templates/wrap.html` is rendered.

The `autogenerate` query parameter is used only by the redirect URL; the current page script does not inspect it. Unless `oauth_error` is present, the script calls `generate()` on every page load.

## 6. The page obtains CSRF and posts the generation request

**Caller -> Callee:** `wrap.html` `csrf()` -> `CsrfController.csrf(CsrfToken token)`  
**Takes in:** the CSRF token created by Spring Security.  
**Does:** returns the token object unchanged.  
**Returns/passes next:** JSON containing the token value and header name; JavaScript uses that header on the POST.

**Caller -> Callee:** browser `POST /wraps` -> Spring Security filter chain -> `WrapController.generate(...)`  
**Takes in:** the authenticated session, OAuth2 principal, and CSRF header/cookie pair.  
**Does:** the filter chain requires authentication for `/wraps` and rejects a missing or invalid CSRF token before controller code runs.  
**Returns/passes next:** a valid request reaches `generate`; an unauthenticated request receives `401` from the configured `HttpStatusEntryPoint`.

## 7. `WrapController` checks the Redis result cache

**Caller -> Callee:** `wrap.html` -> `WrapController.generate(OAuth2AuthenticationToken authentication)`  
**Takes in:** the authenticated principal.  
**Does:** builds `spotify_wrap:` + `authentication.getName()` as the user-specific key. If `performance.disable-wrap-cache` is false, it calls `redis.opsForValue().get(key)` and parses a hit with `ObjectMapper.readTree`. Redis errors and malformed cached JSON are treated as cache misses. If the value exists, no Spotify or analytics call is made.  
**Returns/passes next:** cached JSON with HTTP `200`, or the cache-miss path.

The property is bound from `PERFORMANCE_DISABLE_WRAP_CACHE`; the default is `false`. With it set to `true`, both the Redis read and write are skipped, so every POST fetches Spotify and recomputes analytics. `WRAP_CACHE_TTL` controls the write TTL and defaults to `15m`.

## 8. A usable Spotify access token is selected

**Caller -> Callee:** `WrapController.generate` -> `SpotifyClient.fetch(authentication)` -> `SpotifyTokenService.validAccessToken(String userId)`  
**Takes in:** `authentication.getName()`, the Spotify user id.  
**Does:** loads `SpotifyConnectionRepository.findById(userId)`, rejects a missing or `DISCONNECTED` row, and checks expiry. If the access token expires more than 60 seconds from now, it decrypts the stored ciphertext. Otherwise it calls `refresh(connection)`.  
**Returns/passes next:** a raw access token for the Spotify Web API, or `ReauthRequiredException`.

**Caller -> Callee:** `validAccessToken` -> `SpotifyTokenService.refresh(SpotifyConnection connection)`  
**Takes in:** a connected row with an encrypted refresh token.  
**Does:** decrypts the refresh token; builds HTTP Basic auth from `SPOTIFY_CLIENT_ID:SPOTIFY_CLIENT_SECRET`; POSTs form data to Spotify `/api/token`; reads `access_token`, optional replacement `refresh_token`, and `expires_in`; encrypts new values; calls `connection.updateTokens(...)`; saves the row. A `400` body containing `invalid_grant` calls `connection.disconnect()`, saves the disconnected row, and requires login again.  
**Returns/passes next:** the new raw access token or an exception.

## 9. `SpotifyClient` fetches five datasets in parallel

**Caller -> Callee:** `SpotifyClient.fetch` -> five tasks submitted to `Executors.newVirtualThreadPerTaskExecutor()`  
**Takes in:** the one raw bearer token.  
**Does:** submits five independent operations. Each is wrapped by `timed(endpoint, operation)`, which logs endpoint name, duration, and success/failure. The token is captured by each task; no SecurityContext is required inside the task.  
**Returns/passes next:** `await(...)` gathers every future into one `SpotifySnapshot`.

The HTTP calls share one long-lived `RestClient` backed by Apache HttpClient's `PoolingHttpClientConnectionManager`: maximum 50 connections total and 20 per route. The request factory sets a 2-second connection-request timeout and 15-second response timeout, evicts expired connections, and the `@PreDestroy shutdown()` method closes both the executor and HTTP client. This is connection reuse, not five new clients per request.

| Task | Callee | Spotify request | Mapped output |
| --- | --- | --- | --- |
| 1 | `profile(token)` | `GET /v1/me` | `SpotifySnapshot.Profile(display_name, first image URL)` |
| 2 | `topTracks(token, "long_term")` | `GET /v1/me/top/tracks?time_range=long_term&limit=50` | up to 50 `Track` records |
| 3 | `topTracks(token, "short_term")` | same endpoint with `short_term` | up to 50 `Track` records |
| 4 | `topArtists(token)` | `GET /v1/me/top/artists?time_range=long_term&limit=50` | up to 50 `Artist` records |
| 5 | `recentlyPlayed(token)` | `GET /v1/me/player/recently-played?limit=50` | valid `PlayedTrack(track, playedAt)` records |

There is no current `/v1/me/tracks` call and no saved-library-count calculation in this code path.

**Caller -> Callee:** each task -> `SpotifyClient.get(String token, String path)`  
**Takes in:** bearer token and relative API path.  
**Does:** performs the GET, sets `Authorization: Bearer ...`, parses an object JSON response, and retries HTTP `429` up to three attempts with 200 ms then 400 ms sleeps. It does not read `Retry-After`. Invalid bodies, network errors, and exhausted rate limits become `SpotifyException`.  
**Returns/passes next:** a `JsonNode` to the endpoint mapper.

`track(JsonNode)` keeps the first artist, duration, album id/name, and first album image. `artist(JsonNode)` copies id, name, genres, and first image. Invalid `played_at` timestamps are skipped by `recentlyPlayed`.

If a future fails, `fetch` cancels all five futures and rethrows. `await` restores interrupt status on interruption and unwraps runtime causes.

## 10. The snapshot is analyzed

**Caller -> Callee:** `WrapController.generate` -> `AnalyticsService.analyze(SpotifySnapshot snapshot)`  
**Takes in:** profile, long-term tracks, short-term tracks, long-term artists, and recent plays.  
**Does:** replaces null lists with empty lists, deduplicates recent events by `track id|playedAt`, sorts them by timestamp, limits displayed top tracks/artists to five, and computes each response field.  
**Returns/passes next:** a `WrapResult` for the 13-slide UI.

The calculations are:

- `estimatedTime(events)`: accepts an adjacent interval only when it is positive and no more than the current track duration plus `LISTENING_GAP_TOLERANCE_MS` (30 seconds); adds the smaller of gap and duration. Otherwise it returns an unavailable metric.
- `estimatedYearListeningTime(events, LocalDate.now(UTC))`: estimates each observed UTC day's usable milliseconds, averages those days, and projects through December 1 (or the actual year length after December 1). It is explicitly an estimate.
- `listeningRoutine(recent)`: counts UTC hours and UTC weekdays; ties prefer the later key because of the comparator.
- `comfortArtist(recent)`: groups recent artists by id, or name when id is absent, and returns the largest group and its share.
- `totalTracksListened` / `totalAlbumsListened`: distinct non-null ids in the recent sample.
- `genres(artists)`: counts each distinct raw genre once per artist, sorts by count descending then name, and keeps five. The result is stored in `WrapResult.topGenres` but `@JsonIgnore` means it is not in HTTP JSON.
- `hiddenGems(longTracks, shortTracks)`: up to five long-term tracks whose ids are absent from short-term tracks.
- `diversity(longTracks)`: normalized Shannon entropy of valid artist-id counts, expressed as a percent; one artist is 0%.
- `tasteChanges(longTracks, shortTracks)`: Jaccard difference `100 * (1 - intersection/union)` plus the count of short-term ids absent from long-term ids.
- `replay(recent)`: `(valid event count - distinct track count) / valid event count * 100`; this is repeat intensity in the returned sample, not Spotify play counts.

`normalizeGenre` is a public compatibility helper that maps rap/hip-hop/trap to `Hip-Hop`, pop to `Pop`, rock to `Rock`, electro/dance to `Electronic`, and everything else to `Other`. `WrapResult` also keeps older compatibility accessors; `albumConcentration`, `artistLoyalty`, `familiarityRatio`, and `discoveryRatio` currently return `0`, `risingStar` returns `null`, and `personality()` returns a fixed `Personality("WRAP", "Your Wrap", ...)`.

## 11. The response is cached and returned

**Caller -> Callee:** `AnalyticsService.analyze` -> `WrapController.generate`  
**Takes in:** `WrapResult`.  
**Does:** when caching is enabled, serializes the result with Jackson and calls `redis.opsForValue().set(key, json, cacheTtl)`. A cache-write error is ignored because Redis is an optional performance cache.  
**Returns/passes next:** `ResponseEntity.ok(result)`, serialized as JSON by Spring MVC.

The controller's local error mapping is:

- `ReauthRequiredException` -> `401`, `{code:"reauth_required", message:...}`
- `SpotifyException` -> `502`, `{code:"spotify_error", message:...}`
- any other exception -> `500`, `{code:"generation_failed", message:"We could not generate your wrap. Please try again."}`

`ApiExceptionHandler` declares separate advice mappings, but `WrapController.generate` catches these exceptions itself, so the controller's response normally wins for this endpoint.

## 12. The browser renders the 13 slides

**Caller -> Callee:** `wrap.html` `generate()` -> `render(data)` -> `activate()`  
**Takes in:** JSON response.  
**Does:** hides the loading panel, builds 13 story cards from profile, top tracks/artists, counts, time estimates, routine, comfort artist, taste changes, hidden gems, diversity, replay, and outro; escapes text with `esc`, hides broken images, and wires previous/next buttons.  
**Returns/passes next:** the completed Wrap page in the browser.

If the response is `401`, the page tells the user to reconnect Spotify. Other non-2xx responses show the server message when present. A query-string `oauth_error=spotify_rate_limited` gets a specific rate-limit message; other OAuth errors get a generic reconnect message.
