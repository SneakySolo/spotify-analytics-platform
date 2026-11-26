# Class and function reference

This inventory covers every application class under `src/main/java/com/spotifyanalytics/platform` and the functions that participate in behavior.

## Application and security

### `SpotifyAnalyticsApplication`

- `main(String[] args)`: starts Spring Boot with `SpotifyAnalyticsApplication.class`; called by the JVM.

### `SecurityConfig`

- constructor: receives `OAuthSuccessHandler` and `OAuthFailureHandler`.
- `securityFilterChain(HttpSecurity)`: configures CSRF, permitted routes, authentication requirement, OAuth2 login handlers, and logout. Called by Spring during startup.

### `PageController`

- `home()`: `GET /` -> view name `index`.
- `wrap()`: `GET /wrap` -> view name `wrap`.

### `MeController`

- `me(Authentication)`: `GET /api/me`; returns 401 for null/anonymous authentication, otherwise 200 with `authenticated` and `spotifyUserId`.

### `CsrfController`

- `csrf(CsrfToken)`: `GET /api/csrf`; returns Spring's CSRF token unchanged.

### `ApiExceptionHandler`

- `reauth(ReauthRequiredException)`: maps to 401 with `REAUTH_REQUIRED`.
- `spotify(SpotifyException)`: maps retryable errors to 503 and other Spotify errors to 502.
- `generic(Exception)`: maps unknown errors to 500 with `WRAP_ERROR`.

`WrapController` catches its own expected exceptions, so these methods are fallback mappings rather than the normal local path for `/wraps`.

## OAuth, tokens, and persistence

### `OAuthSuccessHandler`

- `onAuthenticationSuccess(HttpServletRequest, HttpServletResponse, Authentication)`: casts to `OAuth2AuthenticationToken`; loads the authorized client; reads Spotify `id`; encrypts access/refresh tokens; upserts `SpotifyConnection` through `SpotifyConnectionRepository`; redirects to `/wrap?autogenerate=1`.

Calls `OAuth2AuthorizedClientService.loadAuthorizedClient`, `TokenCryptoService.encrypt`, repository `findById`, `SpotifyConnection.updateTokens`, and repository `save`.

### `OAuthFailureHandler`

- `onAuthenticationFailure(HttpServletRequest, HttpServletResponse, AuthenticationException)`: extracts an OAuth error code if present and redirects to `/wrap?oauth_error=...`.

### `TokenCryptoService`

- constructor `TokenCryptoService(String encodedKey)`: Base64-decodes the configured key, requiring 32 bytes when nonblank.
- `encrypt(String plaintext)`: AES/GCM encrypts with a random 12-byte nonce; returns `nonce:ciphertext` Base64 strings.
- `decrypt(String storedValue)`: splits the stored format, reconstructs GCM parameters, and returns plaintext.
- `requireKey()`: throws if the configured key was blank.

### `SpotifyConnection`

JPA entity for `spotify_users`; Lombok `@Getter` supplies getters for fields.

- public constructor `(id, name, accessToken, refreshToken, expiresAt)`: creates connected state and timestamps.
- protected no-arg constructor: required by JPA.
- `created()`: `@PrePersist`; initializes missing `createdAt` and updates `updatedAt`.
- `updated()`: `@PreUpdate`; refreshes `updatedAt`.
- `updateTokens(accessToken, refreshToken, expiresAt)`: replaces access/expiry, replaces refresh only when non-null, marks connected.
- `disconnect()`: clears ciphertext/expiry and marks disconnected.

### `SpotifyConnectionRepository`

Plain `JpaRepository<SpotifyConnection, String>`. Spring supplies `findById` and `save`; no custom query exists.

### `SpotifyTokenService`

- constructor: receives repository, crypto, environment; reads `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`.
- `validAccessToken(String userId)`: loads connection; rejects missing/disconnected; decrypts a token if expiry is more than 60 seconds away; otherwise delegates to `refresh`.
- `refresh(SpotifyConnection)`: POSTs refresh grant to Spotify Accounts, updates encrypted token state, saves entity, and returns the new access token. `invalid_grant` disconnects and raises `ReauthRequiredException`.
- nested `ReauthRequiredException`: runtime signal for a missing/expired Spotify connection.

## Spotify integration

### `SpotifyClient`

- constructor: receives `SpotifyTokenService`; creates a Spotify `/v1` `RestClient` and a virtual-thread-per-task executor.
- `fetch(OAuth2AuthenticationToken)`: resolves one access token, submits profile/saved/top/recent tasks, awaits all, and constructs `SpotifySnapshot`; cancels siblings on runtime failure.
- `await(Future<T>)`: gets a future, preserves interrupt status, unwraps runtime causes, and converts other failures to `SpotifyException`.
- `profile(String token)`: maps `/me` to `Profile`.
- `savedTrackCounts(String token)`: pages `/me/tracks` in 50-item pages, counts total and hard-coded 2025/2026 additions.
- `topTracks(String token, String range)`: calls `/me/top/tracks` for a requested time range and maps tracks.
- `topArtists(String token)`: calls long-term `/me/top/artists` and maps artists.
- `recentlyPlayed(String token)`: calls recent-play endpoint and skips records with invalid timestamps.
- `get(String token, String path)`: sends Bearer GET, parses JSON, retries 429 twice with fixed sleeps, and wraps failures.
- `artist(JsonNode)`: maps artist ID/name/genres/first image.
- `track(JsonNode)`: maps track ID/name/duration, first artist, album ID/name/image.
- `image(JsonNode)`: returns first image URL or null.
- `shutdown()`: `@PreDestroy`; closes executor.
- nested `SavedCounts`: internal total/2025/2026 carrier.
- nested `SpotifyException`: runtime error with message, cause, and `retryable` flag.

### `SpotifySnapshot`

In-memory record passed from client to analytics. Fields: `Profile`, saved total, 2025/2026 counts, long/short tracks, long-term artists, recent plays.

- compatibility constructor `(shortTermArtists, mediumTermArtists, longTermArtists, longTermTracks, recentlyPlayed)`: creates a minimal fixture shape, converts tracks to `PlayedTrack` at `Instant.EPOCH`, and does not preserve the unused artist arguments.
- nested `Profile`, `Artist`, `Album`, `Track`, `PlayedTrack` records hold API data.
- `Artist` compact constructor converts null genres to an immutable empty list.
- `Track.album()`: creates an `Album` from the track's album fields.

## Wrap endpoint and analytics

### `WrapController`

- constructor: injects `StringRedisTemplate`, `ObjectMapper`, `SpotifyClient`, `AnalyticsService`, and `cache.wrap.ttl` defaulting to `15m`.
- `generate(OAuth2AuthenticationToken)`: `POST /wraps`; reads cache; on miss fetches and analyzes; attempts cache write; returns 200/401/502/500 response bodies. Redis failures are ignored.

### `AnalyticsService`

- `analyze(SpotifySnapshot)`: orchestrates all calculations and creates `WrapResult`.
- `libraryGrowth(SpotifySnapshot)`: private year comparison and trend/note construction.
- `countMetric(int,String,String)`: private available numeric metric helper.
- `estimatedTime(List<PlayedTrack>)`: public adjacent-event listening estimate with 30-second tolerance.
- `estimatedYearListeningTime(List<PlayedTrack>, LocalDate)`: public daily-average annualization through early December or year-end.
- `genres(List<Artist>)`: private per-artist distinct genre counts, sorted/limited.
- `hiddenGems(List<Track>,List<Track>)`: private long-term tracks absent from short-term IDs.
- `diversity(List<Track>)`: private normalized Shannon entropy over artist IDs.
- `evolution(List<Track>,List<Track>)`: private Jaccard difference percentage.
- `replay(List<PlayedTrack>)`: private duplicate intensity percentage.
- `deduplicateRecent(List<PlayedTrack>)`: private null-filter, duplicate-key, chronological sort.
- `ids(List<Track>)`, `id(Track)`: private ID helpers.
- `unavailable(String,String)`: private unavailable `Metric` constructor.
- `safe(List<T>)`: private null-to-empty helper.
- `normalizeGenre(String)`: public static broad category mapper.

### `WrapResult`

Response DTO with profile, saved count, library growth, top tracks/artists, time metrics, distinct counts, hidden gems, scores, and outro.

- constructor: assigns all response fields.
- nested `Metric`: `(Double value, String unit, boolean available, String note)`.
- nested `LibraryGrowth`: year counts, difference, trend, availability, note.
- nested `GenreCount`: genre and artist count.
- JSON-ignored compatibility accessors: `recentListeningMinutes`, `topArtist`, `topAlbum`, `albumConcentration`, `topGenre`, `risingStar`, `artistLoyalty`, `genreDiversity`, `familiarityRatio`, `discoveryRatio`, `personality`. Several are placeholders: `albumConcentration`/loyalty/ratios return 0, rising star returns null, personality is fixed.

### `Personality`

Simple record with `category`, `title`, and `description`. Current `WrapResult.personality()` returns a fixed instance; no scoring logic calls this record.

## Tests and configuration artifacts

- `SpotifyAnalyticsApplicationTests.contextLoads()`: Spring context smoke test.
- `AnalyticsServiceTest.rankAndPersonalityAreDeterministic()`: compatibility fixture assertions for top artist, normalized genre, fallback recent minutes, album, personality.
- `AnalyticsServiceTest.genreNormalizationUsesBroadCategories()`: trap and indie pop mappings.
- `TokenCryptoServiceTest.roundTripUsesUniqueNonce()`: encryption uniqueness and round trips.
- `application.yaml`: runtime wiring described above.
- `V1__users.sql`: sole database migration.
- `templates/index.html` and `templates/wrap.html`: browser entry, CSRF fetch, generation call, formatting, rendering, and carousel behavior.

