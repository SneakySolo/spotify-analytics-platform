# Class and function reference

This reference covers the application classes that do real work in `src/main`. For each entry, **called by** means the important incoming path, and **calls** means the important outgoing collaborators.

## Application and security

### `SpotifyAnalyticsApplication`

Path: `com.spotifyanalytics.platform.SpotifyAnalyticsApplication`

- `main(String[] args)`
  - **Called by:** the JVM.
  - **Input:** command-line arguments.
  - **Work:** starts Spring with `SpringApplication.run(...)`.
  - **Output:** running application context and embedded server.

### `SecurityConfig`

Path: `com.spotifyanalytics.platform.security.SecurityConfig`

- constructor(`OAuthSuccessHandler`, `OAuthFailureHandler`)
  - **Called by:** Spring dependency injection.
  - **Work:** stores OAuth handlers.
- `securityFilterChain(HttpSecurity http)`
  - **Called by:** Spring Security configuration.
  - **Work:** installs cookie CSRF, public-path rules, authentication for other paths, a 401 entry point for `/wraps`, OAuth handlers, and logout behavior.
  - **Calls:** `OAuthSuccessHandler`, `OAuthFailureHandler` through the configured filter chain.
  - **Output:** `SecurityFilterChain` bean.

## Web controllers

### `PageController`

Path: `com.spotifyanalytics.platform.web.PageController`

- `home()`
  - **Called by:** browser `GET /`.
  - **Work:** selects the Thymeleaf view `index`.
  - **Output:** `templates/index.html`.
- `wrap()`
  - **Called by:** browser `GET /wrap`.
  - **Work:** selects view `wrap`.
  - **Output:** `templates/wrap.html`.

### `MeController`

Path: `com.spotifyanalytics.platform.web.MeController`

- `me(Authentication authentication)`
  - **Called by:** `index.html` `fetch('/api/me')`.
  - **Input:** Spring Security authentication, possibly null/anonymous.
  - **Work:** checks authentication state.
  - **Output:** 401 `{authenticated:false,...}` or 200 `{authenticated:true, spotifyUserId:...}`.

### `CsrfController`

Path: `com.spotifyanalytics.platform.web.CsrfController`

- `csrf(CsrfToken token)`
  - **Called by:** `wrap.html` `GET /api/csrf`.
  - **Input:** Spring's generated `CsrfToken`.
  - **Work:** returns it unchanged.
  - **Output:** JSON token/header name for the next POST.

### `WrapController`

Path: `com.spotifyanalytics.platform.wraps.WrapController`

- constructor(`StringRedisTemplate`, `ObjectMapper`, `SpotifyClient`, `AnalyticsService`, `Duration cacheTtl`, `boolean disableWrapCache`)
  - **Called by:** Spring dependency injection.
  - **Input:** cache, JSON mapper, Spotify client, analytics service, `cache.wrap.ttl`, and `performance.disable-wrap-cache`.
  - **Work:** stores collaborators and runtime cache settings.
- `generate(OAuth2AuthenticationToken authentication)`
  - **Called by:** authenticated browser `POST /wraps` after the Security filter chain has accepted auth and CSRF.
  - **Input:** OAuth2 principal; its name is the Spotify user id.
  - **Work:** builds `spotify_wrap:<user id>`; reads Redis unless cache is disabled; parses a cache hit; on miss calls `SpotifyClient.fetch`, then `AnalyticsService.analyze`; writes successful JSON to Redis with TTL unless disabled.
  - **Output:** 200 cached JSON or `WrapResult`; 401 `reauth_required`; 502 `spotify_error`; 500 `generation_failed`.
  - **Calls:** `StringRedisTemplate`, `ObjectMapper`, `SpotifyClient.fetch`, `AnalyticsService.analyze`.

### `ApiExceptionHandler`

Path: `com.spotifyanalytics.platform.web.ApiExceptionHandler`

- `reauth(ReauthRequiredException)` -> 401 with `REAUTH_REQUIRED`.
- `spotify(SpotifyException)` -> 503 when `retryable()` is true, otherwise 502, with `SPOTIFY_ERROR`.
- `generic(Exception)` -> 500 with `WRAP_ERROR`.

These are `@RestControllerAdvice` fallbacks. `WrapController.generate` catches its known exceptions locally first.

## OAuth and token classes

### `OAuthSuccessHandler`

Path: `com.spotifyanalytics.platform.auth.OAuthSuccessHandler`

- `onAuthenticationSuccess(HttpServletRequest, HttpServletResponse, Authentication)`
  - **Called by:** Spring OAuth2 login success flow.
  - **Input:** `OAuth2AuthenticationToken`.
  - **Work:** loads the authorized Spotify client; reads profile id/display name; encrypts access/refresh tokens; finds or creates the connection; saves it; redirects.
  - **Output:** HTTP redirect to `/wrap?autogenerate=1`.
  - **Calls:** `OAuth2AuthorizedClientService.loadAuthorizedClient`, `TokenCryptoService.encrypt`, `SpotifyConnectionRepository.findById/save`, `SpotifyConnection.updateTokens`.

### `OAuthFailureHandler`

Path: `com.spotifyanalytics.platform.auth.OAuthFailureHandler`

- `onAuthenticationFailure(HttpServletRequest, HttpServletResponse, AuthenticationException)`
  - **Called by:** Spring OAuth2 login failure flow.
  - **Input:** authentication exception.
  - **Work:** extracts an OAuth error code when available, URL-encodes it, and redirects.
  - **Output:** `/wrap?oauth_error=<code>` or `login_failed`.

### `SpotifyTokenService`

Path: `com.spotifyanalytics.platform.auth.SpotifyTokenService`

- constructor(`SpotifyConnectionRepository`, `TokenCryptoService`, `Environment`)
  - **Called by:** Spring.
  - **Work:** stores repository/crypto and reads `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`.
- `validAccessToken(String userId)`
  - **Called by:** `SpotifyClient.fetch`.
  - **Input:** Spotify user id.
  - **Work:** loads the row; rejects missing or disconnected state; decrypts a token with more than 60 seconds remaining or refreshes it.
  - **Output:** raw bearer token or `ReauthRequiredException`.
  - **Calls:** `SpotifyConnectionRepository.findById`, `TokenCryptoService.decrypt`, `refresh`.
- `refresh(SpotifyConnection connection)` (private)
  - **Called by:** `validAccessToken` when expiry is near/missing.
  - **Input:** connected entity with encrypted refresh token.
  - **Work:** POSTs Basic-authenticated form data to Spotify `/api/token`; reads token fields; encrypts new tokens; updates and saves the entity. On `invalid_grant`, disconnects it.
  - **Output:** new raw access token or an exception.
  - **Calls:** `TokenCryptoService.decrypt/encrypt`, `SpotifyConnection.updateTokens/disconnect`, repository `save`.
- `ReauthRequiredException(String message)`
  - Runtime exception used when the user must connect Spotify again.

### `TokenCryptoService`

Path: `com.spotifyanalytics.platform.auth.TokenCryptoService`

- constructor(`String encodedKey`)
  - **Called by:** Spring with `encryption.key`.
  - **Work:** Base64-decodes the key and requires exactly 32 bytes; an empty value leaves the key unset until use.
- `encrypt(String plaintext)`
  - **Called by:** OAuth success and token refresh.
  - **Input:** raw token text.
  - **Work:** AES/GCM/NoPadding, fresh 12-byte nonce, 128-bit tag.
  - **Output:** `Base64(nonce):Base64(ciphertext+tag)`.
- `decrypt(String storedValue)`
  - **Called by:** `SpotifyTokenService.validAccessToken/refresh`.
  - **Input:** stored two-part ciphertext.
  - **Work:** decodes nonce/ciphertext and verifies/decrypts GCM data.
  - **Output:** raw token text.
- `requireKey()` (private)
  - **Work:** throws if the configured key is absent.

### `SpotifyConnection`

Path: `com.spotifyanalytics.platform.auth.SpotifyConnection`

JPA entity mapped to `spotify_users`.

- constructor(id, name, accessToken, refreshToken, expiresAt): initializes a connected row and timestamps.
- `updateTokens(accessToken, refreshToken, expiresAt)`: replaces access token, keeps the old refresh token when the provider did not return a replacement, updates expiry, and marks `CONNECTED`.
- `disconnect()`: clears both ciphertexts/expiry and marks `DISCONNECTED`.
- `created()` (`@PrePersist`): fills timestamps.
- `updated()` (`@PreUpdate`): refreshes `updatedAt`.
- token/status getters: read values used by `SpotifyTokenService`.

### `SpotifyConnectionRepository`

Path: `com.spotifyanalytics.platform.auth.SpotifyConnectionRepository`

- Extends `JpaRepository<SpotifyConnection, String>`.
- **Called by:** `OAuthSuccessHandler` and `SpotifyTokenService`.
- **Important inherited operations:** `findById`, `save`.
- **Custom queries:** none.

## Spotify integration

### `SpotifyClient`

Path: `com.spotifyanalytics.platform.spotify.SpotifyClient`

- constructor(`SpotifyTokenService tokens`)
  - **Called by:** Spring.
  - **Work:** creates a shared `RestClient` backed by Apache HttpClient's `PoolingHttpClientConnectionManager` (50 total, 20 per route), 2-second connection-request timeout, 15-second response timeout, and expired-connection eviction; creates a virtual-thread-per-task executor.
- `fetch(OAuth2AuthenticationToken authentication)`
  - **Called by:** `WrapController.generate` on a cache miss.
  - **Input:** authenticated principal.
  - **Work:** resolves one token, submits five parallel tasks, waits for all, cancels all on failure.
  - **Output:** `SpotifySnapshot`.
  - **Calls:** `SpotifyTokenService.validAccessToken`, `profile`, `topTracks` twice, `topArtists`, `recentlyPlayed`, `await`.
- `timed(String endpoint, Supplier<T> operation)` (private)
  - **Called by:** each fetch task.
  - **Work:** logs duration/outcome around the endpoint operation.
- `elapsedMillis(long started)` (private)
  - **Work:** converts `System.nanoTime()` difference to milliseconds.
- `await(Future<T> future)` (private)
  - **Called by:** `fetch`.
  - **Work:** gets the result, restores interrupt status, and unwraps runtime causes.
- `profile(String token)` (private)
  - **Calls:** `get(token, "/me")`; maps display name and first image.
- `topTracks(String token, String range)` (private)
  - **Calls:** `/me/top/tracks` with `time_range` and `limit=50`; maps each item with `track`.
- `topArtists(String token)` (private)
  - **Calls:** `/me/top/artists?time_range=long_term&limit=50`; maps each item with `artist`.
- `recentlyPlayed(String token)` (private)
  - **Calls:** `/me/player/recently-played?limit=50`; maps track plus `Instant.parse(played_at)` and skips invalid timestamps.
- `get(String token, String path)` (private)
  - **Work:** bearer GET, object-JSON validation, three-attempt 429 retry (200/400 ms sleeps), and exception conversion.
  - **Output:** `JsonNode` or `SpotifyException`.
- `artist(JsonNode n)` / `track(JsonNode n)` / `image(JsonNode n)` (private)
  - **Work:** map only the fields the analytics/UI model needs; `track` keeps the first artist and first album image.
- `shutdown()` (`@PreDestroy`)
  - **Work:** closes executor and pooled HTTP client.
- `SpotifyException`
  - Runtime exception with `retryable()` flag. Current `get` paths mark service/network failures retryable and invalid JSON non-retryable.

### `SpotifySnapshot`

Path: `com.spotifyanalytics.platform.spotify.SpotifySnapshot`

- Main record: `profile`, `longTermTracks`, `shortTermTracks`, `longTermArtists`, `recentlyPlayed`.
- Compatibility constructor: accepts the older artist/track fixture shape and converts tracks to `PlayedTrack` events at `Instant.EPOCH`.
- `Profile(displayName, imageUrl)`: user profile data.
- `Artist(id, name, genres, imageUrl)`: copies null genres to an empty immutable list.
- `Album(id, name, imageUrl)`: album view.
- `Track(id, name, durationMs, artist, albumId, albumName, imageUrl)`: track data.
- `Track.album()`: creates an `Album` from track album fields.
- `PlayedTrack(track, playedAt)`: one recent listening event.

## Analytics and response model

### `AnalyticsService`

Path: `com.spotifyanalytics.platform.analytics.AnalyticsService`

- `analyze(SpotifySnapshot snapshot)`
  - **Called by:** `WrapController.generate`.
  - **Input:** one assembled Spotify snapshot.
  - **Work:** null-protects lists, deduplicates recent events, limits displayed rankings, and computes all `WrapResult` fields.
  - **Output:** `WrapResult`.
  - **Calls:** `estimatedTime`, `estimatedYearListeningTime`, `listeningRoutine`, `comfortArtist`, `genres`, `hiddenGems`, `diversity`, `tasteChanges`, `replay`.
- `countMetric(int value, String unit, String note)` (private): wraps a number as an available `Metric`.
- `listeningRoutine(List<PlayedTrack>)` (private): counts UTC hour/day and chooses the highest count.
- `comfortArtist(List<PlayedTrack>)` (private): groups recent artists by id/name and returns the largest group plus percentage.
- `estimatedTime(List<PlayedTrack>)`
  - **Input:** recent events.
  - **Work:** sums plausible adjacent intervals, capped by current track duration; uses 30-second tolerance.
  - **Output:** estimated-millisecond `Metric`, or unavailable.
- `estimatedYearListeningTime(List<PlayedTrack>, LocalDate today)`
  - **Work:** averages observed usable daily time and projects through December 1 or year end after that date.
  - **Output:** estimated-millisecond `Metric`, or unavailable.
- `genres(List<Artist>)` (private): counts distinct raw genres per artist, sorts, limits to five.
- `hiddenGems(List<Track> longTracks, List<Track> shortTracks)` (private): long-term ids absent from short-term ids, max five.
- `diversity(List<Track>)` (private): normalized Shannon entropy of artist-id counts.
- `tasteChanges(List<Track> longTracks, List<Track> shortTracks)` (private): Jaccard difference and discovered short-term ids.
- `replay(List<PlayedTrack>)` (private): repeated-event percentage from valid recent track ids.
- `deduplicateRecent(List<PlayedTrack>)` (private): removes null/duplicate `track id|timestamp` events and sorts by timestamp.
- `ids`, `id`, `unavailable`, `safe` (private): set/id, unavailable-metric, and null-list helpers.
- `normalizeGenre(String genre)` (public static): broad compatibility mapping to Hip-Hop, Pop, Rock, Electronic, or Other.

### `Personality`

Path: `com.spotifyanalytics.platform.analytics.Personality`

- Record with `category`, `title`, and `description`.
- **Called by:** `WrapResult.personality()` compatibility accessor.
- **Current value:** fixed `WRAP` / `Your Wrap` text; it is not calculated from user data.

### `WrapResult`

Path: `com.spotifyanalytics.platform.analytics.WrapResult`

- Constructor: receives profile, top-five tracks/artists, metrics, genre counts, hidden gems, routine, comfort artist, taste changes, replay, and outro.
- Public response fields: `profile`, `topTracks`, `topArtists`, `estimatedListeningTime`, `estimatedYearListeningTime`, `totalTracksListened`, `totalAlbumsListened`, `listeningRoutine`, `comfortArtist`, `hiddenGems`, `tasteDiversityScore`, `tasteChanges`, `replayIntensityScore`, `outro`.
- `topGenres`: populated but `@JsonIgnore`, so omitted from HTTP JSON.
- Nested records: `Metric(value, unit, available, note)`, `TasteChanges`, `ListeningRoutine`, `ComfortArtist`, and `GenreCount`.
- Compatibility accessors: `recentListeningMinutes`, `topArtist`, `topAlbum`, `topGenre`, `genreDiversity`, and `personality` provide older callers a view of the new fields; `albumConcentration`, `artistLoyalty`, `familiarityRatio`, and `discoveryRatio` return `0`, and `risingStar` returns `null`.

## Persistence and infrastructure files

### `V1__users.sql`

Creates `spotify_users` with encrypted access/refresh token columns, expiry, connection status, and timestamps. It is run by Flyway, not by application Java code.

### `templates/index.html`

Home page. Its inline JavaScript checks `/api/me`, then routes to `/wrap` or OAuth.

### `templates/wrap.html`

Wrap page. Its inline JavaScript obtains CSRF, posts `/wraps`, parses JSON, escapes text, creates 13 cards, and handles navigation/errors.

### `performance/k6/uncached-wrap.js`

Load-test client, not server code. It posts `/wraps` with a supplied session cookie and `X-XSRF-TOKEN`; its name reflects the recommended `PERFORMANCE_DISABLE_WRAP_CACHE=true` mode.
