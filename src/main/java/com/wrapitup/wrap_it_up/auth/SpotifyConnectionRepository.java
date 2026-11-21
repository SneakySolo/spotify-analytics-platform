package com.wrapitup.wrap_it_up.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotifyConnectionRepository extends JpaRepository<SpotifyConnection, String> {
}
