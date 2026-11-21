# Spotify Analytics Platform V2

This is a synchronous Spring Boot modular monolith. The active application is the root Maven project; the old service directories are preserved as legacy files but are not included in the root build.

## Run locally

1. Start Redis:

   ```powershell
   docker compose up -d redis
   ```

2. Set the required environment variables. Copy `.env.example` into your IDE run configuration or export the values in the shell.

3. Point `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` at PostgreSQL/Supabase. Flyway creates the `spotify_users` table on startup.

4. Register the callback URL with Spotify:

   `http://localhost:8080/login/oauth2/code/spotify`

5. Run:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

Open `http://localhost:8080`, connect Spotify, and click **Generate My Wrap**. Generation is synchronous: the endpoint reads a user-specific Redis cache first, fetches Spotify data on a miss, calculates the analytics, caches the JSON for 15 minutes, and returns the result.

## Verification

```powershell
.\mvnw.cmd test
```

The test suite covers application startup, AES-256-GCM token round trips with unique nonces, genre normalization, rankings, recent listening minutes, album selection, and personality output.

Required production variables are `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_URL`, and a base64-encoded 32-byte `TOKEN_ENCRYPTION_KEY`.
