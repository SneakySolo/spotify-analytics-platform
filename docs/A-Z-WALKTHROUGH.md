# A–Z walkthrough: one Spotify Wrap request

This is the execution trace for the code in `src/main`. It describes what the repository actually does today. The important boundary is that a Wrap is generated synchronously on `POST /wraps`; there is no queue, scheduler, event listener, or background worker.

## Call-chain tree

```text
Browser /
├─ GET /                         -> PageController.home() -> index.html
├─ click #start-wrap             -> GET /api/me -> MeController.me()
│  ├─ authenticated              -> browser navigates /wrap?autogenerate=1
│  └─ not authenticated          -> browser navigates /oauth2/authorization/spotify
│     └─ Spring OAuth2 login      -> Spotify -> /login/oauth2/code/spotify
│        -> OAuthSuccessHandler.onAuthenticationSuccess()
│           -> OAuth2AuthorizedClientService.loadAuthorizedClient()
│           -> TokenCryptoService.encrypt()
│           -> SpotifyConnectionRepository.findById()/save()
│           -> redirect /wrap?autogenerate=1
└─ GET /wrap                     -> PageController.wrap() -> wrap.html
   └─ JavaScript generate()
      -> GET /api/csrf            -> CsrfController.csrf()
      -> POST /wraps              -> WrapController.generate()
         ├─ Redis GET spotify_wrap:<spotify user id>
         ├─ cache hit              -> parse JSON -> HTTP 200
         └─ cache miss              -> SpotifyClient.fetch()
            -> SpotifyTokenService.validAccessToken()
               ├─ unexpired token  -> TokenCryptoService.decrypt()
               └─ expired token    -> refresh() -> Spotify /api/token
                  -> repository save
            -> six virtual-thread tasks:
               ├─ profile()          -> GET Spotify /v1/me
               ├─ savedTrackCounts() -> GET /v1/me/tracks (paged)
               ├─ topTracks(long)    -> GET /v1/me/top/tracks
               ├─ topTracks(short)   -> GET /v1/me/top/tracks
               ├─ topArtists()       -> GET /v1/me/top/artists
               └─ recentlyPlayed()   -> GET /v1/me/player/recently-played
            -> await() all futures -> SpotifySnapshot
            -> AnalyticsService.analyze(snapshot)
               -> WrapResult
            -> Jackson serialize -> Redis SET with TTL -> HTTP 200 JSON
```

## 1. Application starts

**Caller -> Callee:** JVM -> `SpotifyAnalyticsApplication.main(String[] args)`  
**Takes in:** command-line arguments.  
**Does:** calls `SpringApplication.run(SpotifyAnalyticsApplication.class, args)`. Spring Boot discovers the `@SpringBootApplication`, creates beans, configures the embedded web server, JPA, Redis, Flyway, Thymeleaf, OAuth2 client, and Spring Security.  
**Returns/passes next:** a running application context and HTTP server, normally on `server.port`, default `8080`.

At startup, `application.yaml` reads `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_URL`, `SPOTIFY_*`, `TOKEN_ENCRYPTION_KEY`, `WRAP_CACHE_TTL`, `THYMELEAF_CACHE`, and `PORT`, supplying local defaults for most non-secret values. Flyway runs `db/migration/V1__users.sql`, creating `spotify_users`; JPA uses `ddl-auto: validate`, so it validates rather than creates the schema. `TokenCryptoService` rejects a present encryption key unless it is Base64 that decodes to exactly 32 bytes, but an empty key is allowed until encryption/decryption is attempted.

## 2. The browser opens the home page

**Caller -> Callee:** browser `GET /` -> `PageController.home()`  
**Takes in:** no application arguments.  
**Does:** returns the Thymeleaf view name `index`.  
**Returns/passes next:** Spring renders `templates/index.html` as HTML.

Before the controller, `SecurityConfig.securityFilterChain(HttpSecurity)` has configured CSRF using `CookieCsrfTokenRepository.withHttpOnlyFalse()` and has permitted `/`, `/wrap`, `/api/me`, `/api/csrf`, static assets, OAuth paths, and `/actuator/health`. Therefore this page request is allowed without a login.

`index.html` has a `#start-wrap` button. Its JavaScript first calls `fetch("/api/me")`. If the response is successful and the JSON field `authenticated` is true, it navigates to `/wrap?autogenerate=1`; otherwise it navigates to `/oauth2/authorization/spotify`. A network error also starts OAuth.

## 3. The browser checks the current login

**Caller -> Callee:** `index.html` `fetch` -> `MeController.me(Authentication authentication)`  
**Takes in:** Spring Security's `Authentication`, which may be null, anonymous, or authenticated.  
**Does:** rejects null, unauthenticated, and `AnonymousAuthenticationToken`; otherwise exposes the authenticated principal name.  
**Returns/passes next:** HTTP `401` with `{authenticated:false,message:...}` or HTTP `200` with `{authenticated:true,spotifyUserId:...}`.

`/api/me` is explicitly permitted, so the controller itself makes the login decision.

## 4. OAuth login and token persistence

**Caller -> Callee:** browser `/oauth2/authorization/spotify` -> Spring Security OAuth2 client filter chain  
**Takes in:** the configured Spotify registration from `application.yaml`: authorization code grant, scopes `user-read-private,user-read-email,user-top-read,user-read-recently-played,user-library-read`, and the configured redirect URI.  
**Does:** redirects to Spotify; after consent, Spring exchanges the authorization code using Spotify's token endpoint and builds an `OAuth2AuthenticationToken`.  
**Returns/passes next:** the callback enters Spring Security's success or failure handler.

**Caller -> Callee:** Spring OAuth2 success flow -> `OAuthSuccessHandler.onAuthenticationSuccess(HttpServletRequest, HttpServletResponse, Authentication)`  
**Takes in:** the authenticated `OAuth2AuthenticationToken`.  
**Does:** loads the authorized client with `OAuth2AuthorizedClientService.loadAuthorizedClient("spotify", login.getName())`; reads the Spotify user `id` from `OAuth2User`; encrypts the access token and optional refresh token with `TokenCryptoService.encrypt`; finds the row with `SpotifyConnectionRepository.findById`; inserts a new `SpotifyConnection` or calls `SpotifyConnection.updateTokens`; saves it.  
**Returns/passes next:** HTTP redirect to `/wrap?autogenerate=1`. A missing Spotify `id` throws `IllegalStateException`.

**Caller -> Callee:** `OAuthSuccessHandler` -> `TokenCryptoService.encrypt(String plaintext)`  
**Takes in:** a raw OAuth access or refresh token.  
**Does:** requires the 32-byte key, creates a fresh 12-byte random nonce, encrypts with `AES/GCM/NoPadding` and a 128-bit tag.  
**Returns/passes next:** `Base64(nonce):Base64(ciphertext+tag)` stored in Postgres.

**Caller -> Callee:** Spring OAuth2 failure flow -> `OAuthFailureHandler.onAuthenticationFailure(...)`  
**Takes in:** an `AuthenticationException`.  
**Does:** extracts an OAuth error code when available, otherwise uses `login_failed`, URL-encodes it.  
**Returns/passes next:** redirects to `/wrap?oauth_error=<code>`.

## 5. The wrap page loads

**Caller -> Callee:** browser `GET /wrap` -> `PageController.wrap()`  
**Takes in:** no controller argument.  
**Does:** returns the Thymeleaf view name `wrap`.  
**Returns/passes next:** renders `templates/wrap.html`; its JavaScript immediately calls `generate()` regardless of the query string.

## 6. CSRF is obtained before the write request

**Caller -> Callee:** `wrap.html` `csrf()` -> `CsrfController.csrf(CsrfToken token)`  
**Takes in:** the CSRF token supplied by Spring Security.  
**Does:** returns it unchanged.  
**Returns/passes next:** JSON containing the token header name and token value; the browser uses that object as headers for `POST /wraps`.

## 7. Security filters protect generation

**Caller -> Callee:** HTTP request -> Spring Security filter chain built by `SecurityConfig.securityFilterChain(HttpSecurity)`  
**Takes in:** method, path, session, authentication, CSRF cookie/header.  
**Does:** permits the public paths listed in `SecurityConfig`; requires authentication for everything else, including `/wraps`; checks CSRF on the POST because the repository is configured with a cookie token repository; installs OAuth login handlers and logout behavior.  
**Returns/passes next:** an authenticated, CSRF-valid request reaches `WrapController.generate`; a failed check is stopped by Spring Security before the controller.

## 8. Cache lookup

**Caller -> Callee:** `wrap.html` `generate()` -> `WrapController.generate(OAuth2AuthenticationToken authentication)`  
**Takes in:** the authenticated OAuth2 token.  
**Does:** creates the exact key `spotify_wrap:` + `authentication.getName()`, calls `redis.opsForValue().get(key)`, and if a value exists parses it with `ObjectMapper.readTree`. Redis errors and malformed JSON are swallowed and treated as misses.  
**Returns/passes next:** cache hit returns parsed JSON immediately with HTTP 200; miss continues to `SpotifyClient.fetch`.

The cache is not authoritative. A successful result is serialized with `mapper.writeValueAsString(result)` and written using `redis.opsForValue().set(key, json, cacheTtl)`, defaulting to `15m`; a write failure is also ignored.

## 9. Access-token selection and refresh

**Caller -> Callee:** `WrapController.generate` -> `SpotifyClient.fetch(OAuth2AuthenticationToken)` -> `SpotifyTokenService.validAccessToken(String userId)`  
**Takes in:** `authentication.getName()`, which is based on Spotify's configured `user-name-attribute: id`.  
**Does:** loads `SpotifyConnectionRepository.findById(userId)`; rejects a missing or `DISCONNECTED` connection; if expiry is more than 60 seconds away, decrypts the stored access ciphertext; otherwise calls `refresh(connection)`.  
**Returns/passes next:** a raw access token for Spotify calls, or `ReauthRequiredException`.

**Caller -> Callee:** `SpotifyTokenService.validAccessToken` -> `SpotifyTokenService.refresh(SpotifyConnection connection)`  
**Takes in:** the stored connection with an encrypted refresh token.  
**Does:** decrypts the refresh token, builds HTTP Basic auth from `SPOTIFY_CLIENT_ID:SPOTIFY_CLIENT_SECRET`, POSTs form data to `https://accounts.spotify.com/api/token`, reads `access_token`, optional replacement `refresh_token`, and `expires_in`, encrypts new tokens, calls `connection.updateTokens`, and saves it. On a 400 body containing `invalid_grant`, calls `connection.disconnect`, saves, and throws reauth-required.  
**Returns/passes next:** the new raw access token or an exception.

## 10. Spotify data is fetched in parallel

**Caller -> Callee:** `WrapController.generate` -> `SpotifyClient.fetch(authentication)`  
**Takes in:** the authenticated principal.  
**Does:** resolves one token, then submits six tasks to `Executors.newVirtualThreadPerTaskExecutor()`. The token string, not the SecurityContext, is captured by each task.  
**Returns/passes next:** after all required futures succeed, a `SpotifySnapshot`; on failure, all six futures are cancelled and the error propagates.

Each task follows the same path:

| Caller -> callee | Spotify request | What is produced |
|---|---|---|
| `fetch` -> `profile(token)` | `GET /v1/me` | `Profile(display_name, first image URL)` |
| `fetch` -> `savedTrackCounts(token)` | `GET /v1/me/tracks?limit=50&offset=N` until a page has fewer than 50 items | total saved count plus counts added in hard-coded years 2025 and 2026 |
| `fetch` -> `topTracks(token,"long_term")` | `GET /v1/me/top/tracks?time_range=long_term&limit=50` | up to 50 mapped `Track` records |
| `fetch` -> `topTracks(token,"short_term")` | same endpoint with `short_term` | up to 50 mapped `Track` records |
| `fetch` -> `topArtists(token)` | `GET /v1/me/top/artists?time_range=long_term&limit=50` | up to 50 mapped `Artist` records |
| `fetch` -> `recentlyPlayed(token)` | `GET /v1/me/player/recently-played?limit=50` | valid `PlayedTrack(track, playedAt)` records |

`get(token,path)` sets a Bearer token and parses JSON. HTTP 429 is retried up to three attempts with sleeps of 200 ms and 400 ms; it does not inspect `Retry-After`. Other response/network/invalid-body errors become `SpotifyException`, marked retryable for the current exception mapping. `await(Future<T>)` unwraps runtime causes, restores interrupt status on interruption, and converts non-runtime causes to a temporary Spotify error.

Mapping is done by `artist(JsonNode)`, `track(JsonNode)`, and `image(JsonNode)`. A track keeps only the first Spotify artist, duration, album ID/name, and first album image. Invalid recent timestamps are skipped. Missing saved `added_at` values are skipped instead of invented.

## 11. The snapshot is analyzed

**Caller -> Callee:** `WrapController.generate` -> `AnalyticsService.analyze(SpotifySnapshot snapshot)`  
**Takes in:** the assembled profile, saved counts, long/short top tracks, long-term artists, and recent plays.  
**Does:** null-protects lists, deduplicates/sorts recent plays, takes the first five long-term tracks and artists, and computes every `WrapResult` field.  
**Returns/passes next:** a `WrapResult` containing UI data and `Metric` availability/notes.

Inside `analyze`, the exact calculations are:

- `libraryGrowth(snapshot)`: compares `savedTracks2025` and `savedTracks2026`, sets trend to `growth`, `decline`, or `tie`, and always marks the result available.
- `estimatedTime(recent)`: `deduplicateRecent` removes null track/time events and duplicates by `track id|playedAt`, sorts ascending, then accepts adjacent gaps only when positive and no more than current duration + `LISTENING_GAP_TOLERANCE_MS` (30,000 ms). It adds the smaller of gap and duration. Fewer than two usable events or zero total produces `available=false`.
- `estimatedYearListeningTime(recent, LocalDate.now(ZoneOffset.UTC))`: groups the same plausible intervals by UTC day, averages observed daily milliseconds, and projects through December 1; after December 1 it projects through the actual year length. It is explicitly an estimate, not verified annual Spotify listening time.
- `totalTracksListened` and `totalAlbumsListened`: count distinct non-null recent track IDs and album IDs.
- `genres(artists)`: counts each distinct genre once per artist, sorts by descending count then genre name, and limits to five. Note: the record is placed in `topGenres` but `WrapResult` annotates that field with `@JsonIgnore`, so it is not sent in the JSON response.
- `hiddenGems(longTracks, shortTracks)`: returns up to five long-term tracks whose IDs are absent from the short-term set.
- `diversity(longTracks)`: groups valid tracks by artist ID and computes normalized Shannon entropy as a percent. One artist is 0%; no valid tracks is unavailable.
- `evolution(longTracks, shortTracks)`: computes `100 * (1 - intersection/union)` of track-ID sets. Empty union is unavailable.
- `replay(recent)`: computes `(valid event count - distinct track count) / valid event count * 100`; it is repeat intensity in this sample, not actual Spotify play counts.

`normalizeGenre(String)` maps strings containing rap/hip hop/trap to `Hip-Hop`, pop to `Pop`, rock to `Rock`, electro/dance to `Electronic`, and everything else to `Other`. The legacy `WrapResult` compatibility methods `albumConcentration`, `risingStar`, `artistLoyalty`, `familiarityRatio`, and `discoveryRatio` return `0`/`null`; `personality()` returns a fixed `Personality("WRAP", "Your Wrap", ...)`.

## 12. The HTTP response is returned

**Caller -> Callee:** `AnalyticsService.analyze` -> `WrapController.generate`  
**Takes in:** `WrapResult`.  
**Does:** attempts Redis serialization/write, ignoring cache-write failures.  
**Returns/passes next:** `ResponseEntity.ok(result)`, serialized by Jackson as JSON.

Error outcomes in `WrapController.generate` are explicit: `ReauthRequiredException` -> 401 `{code:reauth_required,...}`; `SpotifyException` -> 502 `{code:spotify_error,...}`; any other exception -> 500 `{code:generation_failed,...}`. `ApiExceptionHandler` also declares mappings for the same exception types, but `WrapController` catches them locally, so this endpoint's local responses normally win.

The browser renders the JSON into 13 cards in `wrap.html`, formats metric units, escapes text with `esc`, hides broken images, and enables previous/next carousel buttons. It does not display `topGenres`, and the current implementation starts generation on every `/wrap` page load rather than checking `autogenerate` itself.

