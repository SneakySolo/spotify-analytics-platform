package com.spotifyanalytics.platform.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotifyConnectionRepository extends JpaRepository<SpotifyConnection, String> {
}
