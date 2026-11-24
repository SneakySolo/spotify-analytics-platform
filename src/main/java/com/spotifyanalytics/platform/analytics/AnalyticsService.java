package com.spotifyanalytics.platform.analytics;

import com.spotifyanalytics.platform.spotify.SpotifySnapshot;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {
    /** Maximum gap accepted when estimating one consecutive listening interval. */
    public static final long LISTENING_GAP_TOLERANCE_MS = 30_000L;

    public WrapResult analyze(SpotifySnapshot snapshot) {
        List<SpotifySnapshot.Track> longTracks = safe(snapshot.longTermTracks());
        List<SpotifySnapshot.Track> shortTracks = safe(snapshot.shortTermTracks());
        List<SpotifySnapshot.Artist> artists = safe(snapshot.longTermArtists());
        List<SpotifySnapshot.PlayedTrack> recent = deduplicateRecent(snapshot.recentlyPlayed());

        return new WrapResult(snapshot.profile(), snapshot.savedTrackCount(), longTracks.stream().limit(5).toList(),
                artists.stream().limit(5).toList(), estimatedTime(recent),
                countMetric(recent.stream().map(p -> id(p.track())).filter(Objects::nonNull).collect(Collectors.toSet()).size(), "tracks", "Unique tracks in the available recent sample."),
                countMetric(recent.stream().map(p -> p.track() == null ? null : p.track().albumId()).filter(Objects::nonNull).collect(Collectors.toSet()).size(), "albums", "Unique albums in the available recent sample."),
                genres(artists), hiddenGems(longTracks, shortTracks), diversity(longTracks), evolution(longTracks, shortTracks), replay(recent),
                "That's your Wrap for now! Keep listening, keep discovering, and tune in anytime for another Wrap.");
    }

    private WrapResult.Metric countMetric(int value, String unit, String note) { return new WrapResult.Metric((double) value, unit, true, note); }

    /** Adds only intervals where the next event plausibly follows the current track. */
    public WrapResult.Metric estimatedTime(List<SpotifySnapshot.PlayedTrack> events) {
        List<SpotifySnapshot.PlayedTrack> ordered = deduplicateRecent(events);
        if (ordered.size() < 2) return unavailable("estimated milliseconds", "Insufficient recent events to estimate listening time.");
        long total = 0;
        for (int i = 0; i < ordered.size() - 1; i++) {
            SpotifySnapshot.PlayedTrack current = ordered.get(i), next = ordered.get(i + 1);
            if (current.track() == null || current.playedAt() == null || next.playedAt() == null || current.track().durationMs() <= 0) continue;
            long gap = Duration.between(current.playedAt(), next.playedAt()).toMillis();
            if (gap > 0 && gap <= current.track().durationMs() + LISTENING_GAP_TOLERANCE_MS) total += Math.min(gap, current.track().durationMs());
        }
        return total == 0 ? unavailable("estimated milliseconds", "No plausible consecutive listening interval was available.") : new WrapResult.Metric((double) total, "estimated milliseconds", true, "Estimated from the recent sample; Spotify does not provide actual listened milliseconds.");
    }

    private List<WrapResult.GenreCount> genres(List<SpotifySnapshot.Artist> artists) {
        Map<String, Integer> counts = new HashMap<>();
        for (SpotifySnapshot.Artist artist : artists) new HashSet<>(safe(artist.genres())).forEach(g -> { if (g != null && !g.isBlank()) counts.merge(g, 1, Integer::sum); });
        return counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(5).map(e -> new WrapResult.GenreCount(e.getKey(), e.getValue())).toList();
    }
    private List<SpotifySnapshot.Track> hiddenGems(List<SpotifySnapshot.Track> longTracks, List<SpotifySnapshot.Track> shortTracks) {
        Set<String> current = shortTracks.stream().map(this::id).filter(Objects::nonNull).collect(Collectors.toSet());
        return longTracks.stream().filter(t -> id(t) != null && !current.contains(id(t))).limit(5).toList();
    }
    private WrapResult.Metric diversity(List<SpotifySnapshot.Track> tracks) {
        Map<String, Long> counts = tracks.stream().map(t -> t == null || t.artist() == null ? null : t.artist().id()).filter(Objects::nonNull).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (counts.isEmpty()) return unavailable("percent", "No valid long-term tracks were returned.");
        if (counts.size() == 1) return new WrapResult.Metric(0d, "percent", true, "Shannon entropy within the top-track sample.");
        double total = counts.values().stream().mapToLong(Long::longValue).sum();
        double entropy = counts.values().stream().mapToDouble(c -> { double p = c / total; return -p * Math.log(p); }).sum();
        return new WrapResult.Metric(entropy / Math.log(counts.size()) * 100, "percent", true, "Diversity within the top 50 track sample, not listening-time distribution.");
    }
    private WrapResult.Metric evolution(List<SpotifySnapshot.Track> longTracks, List<SpotifySnapshot.Track> shortTracks) {
        Set<String> a = ids(longTracks), b = ids(shortTracks), union = new HashSet<>(a); union.addAll(b);
        if (union.isEmpty()) return unavailable("percent", "Both affinity samples were empty.");
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        return new WrapResult.Metric((1d - intersection.size() / (double) union.size()) * 100, "percent", true, "Jaccard comparison of long-term and short-term affinity sets.");
    }
    private WrapResult.Metric replay(List<SpotifySnapshot.PlayedTrack> recent) {
        Set<String> ids = recent.stream().map(p -> id(p.track())).filter(Objects::nonNull).collect(Collectors.toSet()); int n = (int) recent.stream().map(p -> id(p.track())).filter(Objects::nonNull).count();
        return n == 0 ? unavailable("percent", "No valid recent listening events were returned.") : new WrapResult.Metric((n - ids.size()) * 100d / n, "percent", true, "Repeat intensity in the available recent sample, not actual play counts.");
    }
    private List<SpotifySnapshot.PlayedTrack> deduplicateRecent(List<SpotifySnapshot.PlayedTrack> input) {
        return safe(input).stream().filter(Objects::nonNull).filter(p -> p.track() != null && p.playedAt() != null).collect(Collectors.toMap(p -> id(p.track()) + "|" + p.playedAt(), Function.identity(), (first, ignored) -> first)).values().stream().sorted(Comparator.comparing(SpotifySnapshot.PlayedTrack::playedAt)).toList();
    }
    private Set<String> ids(List<SpotifySnapshot.Track> tracks) { return tracks.stream().map(this::id).filter(Objects::nonNull).collect(Collectors.toSet()); }
    private String id(SpotifySnapshot.Track t) { return t == null ? null : t.id(); }
    private WrapResult.Metric unavailable(String unit, String note) { return new WrapResult.Metric(null, unit, false, note); }
    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    public static String normalizeGenre(String genre) { String value = genre == null ? "" : genre.toLowerCase(Locale.ROOT); if (value.contains("rap") || value.contains("hip hop") || value.contains("trap")) return "Hip-Hop"; if (value.contains("pop")) return "Pop"; if (value.contains("rock")) return "Rock"; if (value.contains("electro") || value.contains("dance")) return "Electronic"; return "Other"; }
}
