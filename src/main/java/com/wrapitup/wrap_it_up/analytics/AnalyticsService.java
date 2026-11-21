package com.wrapitup.wrap_it_up.analytics;

import com.wrapitup.wrap_it_up.spotify.SpotifySnapshot;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    public WrapResult analyze(SpotifySnapshot snapshot) {
        List<SpotifySnapshot.Track> tracks = safe(snapshot.longTermTracks());
        List<SpotifySnapshot.Artist> artists = safe(snapshot.longTermArtists());

        Map<String, Integer> artistCounts = tracks.stream()
                .collect(Collectors.toMap(
                        track -> track.artist().id(),
                        track -> 1,
                        Integer::sum));
        double loyalty = normalizeHhi(artistCounts, 50);

        Map<String, Integer> albumScores = albumScores(tracks);
        String topAlbumId = albumScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        long topAlbumTracks = tracks.stream()
                .filter(track -> track.albumId().equals(topAlbumId))
                .count();
        double albumConcentration = topAlbumTracks / 50.0;

        Map<String, Double> genreScores = genreScores(artists);
        double genreDiversity = normalizeEntropy(genreScores);

        var recentArtists = safe(snapshot.recentlyPlayed()).stream()
                .map(track -> track.artist().id())
                .collect(Collectors.toSet());
        var longTermArtists = artists.stream()
                .map(SpotifySnapshot.Artist::id)
                .collect(Collectors.toSet());

        double familiarityRatio = ratio(
                recentArtists.stream().filter(longTermArtists::contains).count(),
                recentArtists.size());
        double discoveryRatio = ratio(
                recentArtists.stream().filter(id -> !longTermArtists.contains(id)).count(),
                recentArtists.size());

        SpotifySnapshot.Artist topArtist = artists.isEmpty() ? null : artists.get(0);
        SpotifySnapshot.Album topAlbum = tracks.stream()
                .filter(track -> track.albumId().equals(topAlbumId))
                .findFirst()
                .map(SpotifySnapshot.Track::album)
                .orElse(null);
        SpotifySnapshot.Artist risingStar = findRisingStar(
                snapshot.shortTermArtists(),
                artists);

        Personality personality = classifyPersonality(
                loyalty,
                albumConcentration,
                genreDiversity,
                discoveryRatio);

        int recentMinutes = (int) (safe(snapshot.recentlyPlayed()).stream()
                .mapToLong(SpotifySnapshot.Track::durationMs)
                .sum() / 60000.0);
        String topGenre = genreScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");

        return new WrapResult(
                recentMinutes,
                topArtist,
                tracks.stream().limit(5).toList(),
                topAlbum,
                albumConcentration,
                topGenre,
                risingStar,
                loyalty,
                genreDiversity,
                familiarityRatio,
                discoveryRatio,
                personality);
    }

    private Map<String, Integer> albumScores(List<SpotifySnapshot.Track> tracks) {
        Map<String, Integer> scores = new HashMap<>();
        for (int index = 0; index < tracks.size(); index++) {
            String albumId = tracks.get(index).albumId();
            scores.merge(albumId, Math.max(1, 50 - index), Integer::sum);
        }
        return scores;
    }

    private Map<String, Double> genreScores(List<SpotifySnapshot.Artist> artists) {
        Map<String, Double> scores = new HashMap<>();
        for (int index = 0; index < artists.size(); index++) {
            double weight = 20 - index;
            for (String genre : safe(artists.get(index).genres())) {
                scores.merge(normalizeGenre(genre), weight, Double::sum);
            }
        }
        return scores;
    }

    private SpotifySnapshot.Artist findRisingStar(
            List<SpotifySnapshot.Artist> shortTerm,
            List<SpotifySnapshot.Artist> longTerm) {
        Map<String, Integer> shortTermScores = rank(shortTerm);
        Map<String, Integer> longTermScores = rank(longTerm);

        return safe(shortTerm).stream()
                .limit(10)
                .filter(artist -> growthScore(artist, shortTermScores, longTermScores) > 0)
                .max(Comparator.comparingInt(artist ->
                        growthScore(artist, shortTermScores, longTermScores)))
                .orElse(null);
    }

    private int growthScore(
            SpotifySnapshot.Artist artist,
            Map<String, Integer> shortTermScores,
            Map<String, Integer> longTermScores) {
        return shortTermScores.getOrDefault(artist.id(), 0)
                - longTermScores.getOrDefault(artist.id(), 0);
    }

    private Map<String, Integer> rank(List<SpotifySnapshot.Artist> artists) {
        Map<String, Integer> scores = new HashMap<>();
        for (int index = 0; index < artists.size(); index++) {
            scores.put(artists.get(index).id(), 20 - index);
        }
        return scores;
    }

    private double normalizeHhi(Map<String, Integer> counts, int totalTracks) {
        return Math.min(100, counts.values().stream()
                .mapToDouble(count -> Math.pow(count / (double) totalTracks, 2))
                .sum() * 100);
    }

    private double normalizeEntropy(Map<String, Double> scores) {
        if (scores.size() < 2) {
            return 0;
        }
        double total = scores.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        double entropy = scores.values().stream()
                .mapToDouble(score -> {
                    double probability = score / total;
                    return -probability * Math.log(probability);
                })
                .sum();
        return Math.min(100, entropy / Math.log(scores.size()) * 100);
    }

    public static String normalizeGenre(String genre) {
        String normalized = genre.toLowerCase(Locale.ROOT);
        if (normalized.contains("rap")
                || normalized.contains("hip hop")
                || normalized.contains("trap")) {
            return "Hip-Hop";
        }
        if (normalized.contains("pop")) {
            return "Pop";
        }
        if (normalized.contains("rock")) {
            return "Rock";
        }
        if (normalized.contains("electro")
                || normalized.contains("dance")) {
            return "Electronic";
        }
        return "Other";
    }

    private Personality classifyPersonality(
            double loyalty,
            double albumConcentration,
            double genreDiversity,
            double discoveryRatio) {
        if (loyalty >= 80 && albumConcentration >= 0.50) {
            return new Personality(
                    "OBSESSIVE",
                    "The Obsessive",
                    "You know exactly what you like and return to it often.");
        }
        if (albumConcentration >= 0.40) {
            return new Personality(
                    "ALBUM_PERSON",
                    "The Album Person",
                    "You let a full album become part of your routine.");
        }
        if (loyalty >= 70) {
            return new Personality(
                    "LOYALIST",
                    "The Loyalist",
                    "A few familiar artists keep earning their place.");
        }
        if (discoveryRatio >= 0.50) {
            return new Personality(
                    "DISCOVERER",
                    "The Discoverer",
                    "Your recent listening keeps finding new artists.");
        }
        if (genreDiversity >= 65 && discoveryRatio >= 0.45) {
            return new Personality(
                    "CHAOS_LISTENER",
                    "The Chaos Listener",
                    "Your listening takes wonderfully unexpected turns.");
        }
        if (genreDiversity >= 70 && loyalty < 60) {
            return new Personality(
                    "EXPLORER",
                    "The Explorer",
                    "You roam widely across sounds and styles.");
        }
        return new Personality(
                "DEFAULT",
                "The Soundtrack Maker",
                "Your listening has its own unmistakable rhythm.");
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : numerator / (double) denominator;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
