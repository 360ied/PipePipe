package org.schabi.newpipe.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class StreamFilters {
    private StreamFilters() { }

    public static List<AudioStream> filterAudioStreamsByLanguage(
            final List<AudioStream> audioStreams, final String preferredLanguage) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return Collections.emptyList();
        }

        if ("original".equals(preferredLanguage)) {
            final List<AudioStream> originalStreams = audioStreams.stream()
                    .filter(stream -> {
                        final String trackName = stream.getAudioTrackName();
                        if (trackName == null) return true;
                        final String nameLower = trackName.toLowerCase();
                        return nameLower.contains("original") || nameLower.contains("default");
                    })
                    .collect(Collectors.toList());
            if (!originalStreams.isEmpty()) {
                return originalStreams;
            }
            final List<AudioStream> noTrackInfoStreams = audioStreams.stream()
                    .filter(stream -> stream.getAudioTrackId() == null)
                    .collect(Collectors.toList());
            if (!noTrackInfoStreams.isEmpty()) {
                return noTrackInfoStreams;
            }
            return audioStreams;
        }

        final List<AudioStream> matchedStreams = audioStreams.stream()
                .filter(stream -> {
                    final String locale = stream.getAudioLocale();
                    if (locale != null && locale.equals(preferredLanguage)) {
                        return true;
                    }
                    final String trackId = stream.getAudioTrackId();
                    if (trackId != null) {
                        final String langCode = trackId.split("\\.")[0].split("-")[0];
                        return langCode.equals(preferredLanguage);
                    }
                    return false;
                })
                .collect(Collectors.toList());

        return matchedStreams.isEmpty() ? audioStreams : matchedStreams;
    }

    /**
     * Return a {@link Stream} list which only contains non-torrent streams.
     *
     * @param streamList the original stream list
     * @param <S>        the item type's class that extends {@link Stream}
     * @return a stream list which only contains non-torrent streams
     */
    @NonNull
    public static <S extends Stream> List<S> removeTorrentStreams(
            @NonNull final List<S> streamList) {
        if (streamList.isEmpty()) {
            // Mutable: callers may append fallbacks to the result.
            return new ArrayList<>();
        }

        final Iterator<S> streamListIterator = streamList.iterator();
        while (streamListIterator.hasNext()) {
            final S stream = streamListIterator.next();
            if (stream.getDeliveryMethod() == DeliveryMethod.TORRENT) {
                streamListIterator.remove();
            }
        }

        return streamList;
    }

    public static List<AudioStream> filterUnsupportedFormats(@NonNull final List<AudioStream> streamList,
                                                             @NonNull final Context context) {
        return streamList.stream()
                .filter(stream -> {
                    if (stream.getCodec() == null) {
                        return true;
                    }
                    final String codec = stream.getCodec().toLowerCase(Locale.ROOT);
                    // flac support has issue: InsufficientCapacityException. ec-3 (Dolby
                    // Digital Plus 5.1) has no toggle anymore, so it is never offered.
                    return !codec.equals("flac") && !codec.equals("ec-3");
                }).collect(Collectors.toList());
    }

    public static List<VideoStream> filterVideoStreamsByPreferredLanguage(
            final Context context,
            final List<VideoStream> videoStreams,
            final List<AudioStream> audioStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return new ArrayList<>();
        }
        final boolean hasAudioTracks = videoStreams.stream()
                .filter(Objects::nonNull)
                .anyMatch(s -> !isTrackless(s.getAudioTrackId()));
        if (!hasAudioTracks) {
            return videoStreams;
        }
        final List<AudioStream> filtered = AudioStreamSelector.getFilteredAudioStreams(context, audioStreams);
        final int defaultIdx = AudioStreamSelector.getDefaultAudioFormat(context, filtered);
        String defaultTrackId = null;
        if (defaultIdx >= 0 && defaultIdx < filtered.size()) {
            defaultTrackId = filtered.get(defaultIdx).getAudioTrackId();
        }
        if (defaultTrackId == null) {
            return collapseAudioTrackVariants(videoStreams);
        }
        final String trackId = defaultTrackId;
        final List<VideoStream> result = new ArrayList<>();
        for (final VideoStream s : videoStreams) {
            // Blank track ids count as trackless, matching bucketKeyOf/labels:
            // universal streams must survive track-aware filtering.
            if (s != null && (trackId.equals(s.getAudioTrackId())
                    || isTrackless(s.getAudioTrackId()))) {
                result.add(s);
            }
        }
        return result.isEmpty()
                ? collapseAudioTrackVariants(videoStreams)
                : collapseAudioTrackVariants(result);
    }

    /** Null or blank audio track ids count as trackless (universal streams). */
    private static boolean isTrackless(@Nullable final String trackId) {
        return trackId == null || trackId.trim().isEmpty();
    }

    // Package-private for tests.
    static List<VideoStream> collapseAudioTrackVariants(final List<VideoStream> videoStreams) {
        final Map<String, VideoStream> result = new LinkedHashMap<>();
        for (final VideoStream stream : videoStreams) {
            if (stream == null) {
                continue;
            }
            // Codec family (not raw prefix, so vp9/vp09 collide) and trimmed
            // resolution; fps-variant granularity is preserved so the download
            // path loses no rows — resolution bucketing merges variants later.
            // MediaFormat deliberately stays in the key: distinct containers are
            // distinct download choices, and null-format HLS twins survive as
            // separate delivery options (bucketing still merges them for menus).
            final String key = stream.getFormat() + "|"
                    + Objects.toString(
                    VideoStreamSelector.codecFamilyOf(stream.getCodec()), "")
                    + "|" + Objects.toString(stream.getResolution(), "")
                            .trim().toLowerCase(Locale.ROOT)
                    + "|" + stream.isVideoOnly();
            final VideoStream existing = result.get(key);
            // Legacy preference first (non-HLS over HLS, original track over dub);
            // otherwise keep the comparator-best copy so a higher-bitrate/HFR
            // duplicate is never lost before resolution bucketing runs.
            if (existing == null
                    || shouldPreferCollapsedVariant(existing, stream)
                    || (!shouldPreferCollapsedVariant(stream, existing)
                    && VideoStreamSelector.compareVideoStreamResolution(stream, existing)
                    > 0)) {
                result.put(key, stream);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean shouldPreferCollapsedVariant(final VideoStream existing,
                                                        final VideoStream candidate) {
        // Delivery decides first: a non-HLS candidate always displaces HLS, and an
        // HLS candidate never displaces non-HLS, regardless of track originality.
        final boolean existingHls =
                existing.getDeliveryMethod() == DeliveryMethod.HLS;
        final boolean candidateHls =
                candidate.getDeliveryMethod() == DeliveryMethod.HLS;
        if (existingHls != candidateHls) {
            return !candidateHls;
        }
        return isOriginalAudioTrack(candidate) && !isOriginalAudioTrack(existing);
    }

    private static boolean isOriginalAudioTrack(final VideoStream stream) {
        final String trackName = stream.getAudioTrackName();
        return trackName != null && trackName.toLowerCase(Locale.ROOT).contains("original");
    }
}
