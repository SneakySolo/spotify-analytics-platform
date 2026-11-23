# Spotify Analytics Platform V2

This is a synchronous Spring Boot modular monolith for generating Spotify listening analytics.

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

Open `http://127.0.0.1:8080`, connect Spotify, and click **Generate My Wrap**. Generation is synchronous: the endpoint reads a user-specific Redis cache first, fetches Spotify data on a miss, calculates the analytics, caches the JSON for the configured TTL (15 minutes by default), and returns the result. Reloading the page still makes one request to the application, but a valid Redis entry prevents the Spotify APIs from being called again.

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

## Deploy on Render

Create a Render Web Service from this repository and choose the Docker runtime. Render will build the root `Dockerfile` and run the image on the port supplied by `PORT`.

Set the health check path to `/actuator/health` and configure these environment variables in Render:

- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`
- `SPOTIFY_REDIRECT_URI` (for example, `https://your-service.onrender.com/login/oauth2/code/spotify`)
- `TOKEN_ENCRYPTION_KEY` (base64-encoded 32-byte key)
- `DATABASE_URL` (JDBC PostgreSQL URL from Supabase)
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `REDIS_URL` (the Render Key Value internal Redis URL)
- `THYMELEAF_CACHE=true`

Register the exact value of `SPOTIFY_REDIRECT_URI` in the Spotify Developer Dashboard. Use Supabase's Session Pooler connection details when Render cannot reach the direct IPv6 database endpoint; the JDBC URL must use the `jdbc:postgresql://` format.

The free Render web service can sleep after inactivity, so the first request after idle may be slow. Redis is only a cache: losing it after a restart is expected and causes Wraps to be regenerated.
