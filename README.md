# Spotify Analytics Platform V2

This is a synchronous Spring Boot modular monolith. The active application is the root Maven project; the old service directories are preserved as legacy files but are not included in the root build.

## Run locally

1. Start PostgreSQL and Redis. Docker downloads each image only the first time; later runs reuse the local images and containers:

   ```bash
   docker compose up -d postgres redis
   ```

2. In Git Bash, export the required environment variables. These are Git Bash commands, not PowerShell commands:

   ```bash
   export SPOTIFY_CLIENT_ID='your-client-id'
   export SPOTIFY_CLIENT_SECRET='your-client-secret'
   export SPOTIFY_REDIRECT_URI='http://127.0.0.1:8080/login/oauth2/code/spotify'
   export TOKEN_ENCRYPTION_KEY="$(openssl rand -base64 32)"
   ```

   Keep the same encryption key when restarting an existing local database. The local Compose database is exposed on port `55432` to avoid collisions with an existing PostgreSQL installation.

3. Point `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` at PostgreSQL/Supabase. Flyway creates the `spotify_users` table on startup.

4. Register this exact callback URL with Spotify:

   `http://127.0.0.1:8080/login/oauth2/code/spotify`

5. Run:

   ```bash
   ./mvnw spring-boot:run
   ```

Open `http://127.0.0.1:8080`, connect Spotify, and click **Generate My Wrap**. Generation is synchronous: the endpoint reads a user-specific Redis cache first, fetches Spotify data on a miss, calculates the analytics, caches the JSON for 15 minutes, and returns the result.

## Verification

```bash
./mvnw test
```

To stop the containers without deleting their data:

```bash
docker compose stop
```

To start them again:

```bash
docker compose start
```

The named `postgres-data` volume keeps the local database across container restarts and `docker compose down`. Remove it only when you intentionally want a fresh database.

The test suite covers application startup, AES-256-GCM token round trips with unique nonces, genre normalization, rankings, recent listening minutes, album selection, and personality output.

Required production variables are `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REDIRECT_URI`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `REDIS_URL`, and a base64-encoded 32-byte `TOKEN_ENCRYPTION_KEY`.
