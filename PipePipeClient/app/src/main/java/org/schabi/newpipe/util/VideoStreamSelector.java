package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class VideoStreamSelector {
    private VideoStreamSelector() { }

    // Video format in order of quality. 0=lowest quality, n=highest quality
    private static final List<MediaFormat> VIDEO_FORMAT_QUALITY_RANKING =
            Arrays.asList(MediaFormat.v3GPP, MediaFormat.WEBM, MediaFormat.MPEG_4);

    /** advanced_formats toggle tokens for the VP9 and AV1 codec families. */
    private static final String FORMAT_VP9_TOGGLE = "VP9";
    private static final String FORMAT_AV1_TOGGLE = "AV01";

    /** Lowercase codec families returned by {@link #codecFamilyOf}. */
    private static final String CODEC_FAMILY_AV1 = "av1";
    private static final String CODEC_FAMILY_VP9 = "vp9";
    private static final String CODEC_FAMILY_AVC = "avc";

    /** Codec-family ranking weights for {@link #getCodecPriority}. */
    private static final int CODEC_PRIORITY_AV1 = 4;
    private static final int CODEC_PRIORITY_VP9 = 3;
    private static final int CODEC_PRIORITY_AVC = 1;
    private static final int CODEC_PRIORITY_UNKNOWN = 0;

    /** Frame-rate suffixes at or below this are standard frame rate, above is HFR. */
    private static final int STANDARD_FPS = 30;

    /** Placeholder row label when both resolution and codec are unknown. */
    static final String UNKNOWN_RESOLUTION_LABEL = "?";

    private static final Pattern NUMERIC_HEIGHT_PATTERN =
            Pattern.compile("(\\d+)p", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLUTION_SUFFIX_PATTERN =
            Pattern.compile("(?i)p.*");
    private static final Pattern RESOLUTION_VARIANT_SUFFIX_PATTERN =
            Pattern.compile("(?i)(p\\d*).*");
    private static final Pattern FPS_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    /**
     * @see #getDefaultResolutionIndex(String, String, List)
     * @param context      Android app context
     * @param videoStreams list of the video streams to check
     * @return index of the video stream with the default index
     */
    public static int getDefaultResolutionIndex(final Context context,
                                                final List<VideoStream> videoStreams) {
        final String defaultResolution = computeDefaultResolution(context,
                R.string.default_resolution_key, R.string.default_resolution_value);
        return getDefaultResolutionWithDefaultFormat(context, defaultResolution, videoStreams);
    }

    /**
     * @see #getDefaultResolutionIndex(String, String, List)
     * @param context           Android app context
     * @param videoStreams      list of the video streams to check
     * @return index of the video stream with the default index
     */
    public static int getPopupDefaultResolutionIndex(final Context context,
                                                     final List<VideoStream> videoStreams) {
        final String defaultResolution = computeDefaultResolution(context,
                R.string.default_popup_resolution_key, R.string.default_popup_resolution_value);
        return getDefaultResolutionWithDefaultFormat(context, defaultResolution, videoStreams);
    }

    /**
     * Finds the stream matching a quality selected on a previous video.
     *
     * <p>Stream list positions are not stable between videos. Match the selected resolution and
     * codec family instead, preferring an exact frame-rate variant when available, then the same
     * effective resolution, and finally the closest lower resolution.</p>
     *
     * @param targetResolution resolution selected by the user
     * @param targetCodec codec selected by the user, or {@code null} if it is unknown
     * @param videoStreams streams available for the new video
     * @return a valid stream index, or {@code -1} if the stream list is empty
     */
    public static int getResolutionAndCodecIndex(
            @NonNull final String targetResolution,
            @Nullable final String targetCodec,
            @Nullable final List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return -1;
        }

        final String normalizedTarget = normalizeResolutionKey(targetResolution);
        final String codecFamily = codecFamilyOf(targetCodec);
        int index;

        if (codecFamily != null) {
            index = findBestVideoStreamIndex(videoStreams, targetResolution, null, codecFamily);
            if (index >= 0) {
                return index;
            }
            index = findBestVideoStreamIndex(videoStreams, null, normalizedTarget, codecFamily);
            if (index >= 0) {
                return index;
            }
        }

        index = findBestVideoStreamIndex(videoStreams, targetResolution, null, null);
        if (index >= 0) {
            return index;
        }
        index = findBestVideoStreamIndex(videoStreams, null, normalizedTarget, null);
        if (index >= 0) {
            return index;
        }

        final String fallbackResolution =
                findFallbackResolution(videoStreams, normalizedTarget);

        if (codecFamily != null) {
            index = findBestVideoStreamIndex(
                    videoStreams, null, fallbackResolution, codecFamily);
            if (index >= 0) {
                return index;
            }
        }
        index = findBestVideoStreamIndex(videoStreams, null, fallbackResolution, null);
        return index >= 0 ? index : 0;
    }

    /**
     * Join the two lists of video streams (video_only and normal videos),
     * and sort them according with default format chosen by the user.
     *
     * @param context                the context to search for the format to give preference
     * @param videoStreams           the normal videos list
     * @param videoOnlyStreams       the video-only stream list
     * @param ascendingOrder         true -> smallest to greatest | false -> greatest to smallest
     * @param preferVideoOnlyStreams if video-only streams should preferred when both video-only
     *                               streams and normal video streams are available
     * @return the sorted list
     */
    @NonNull
    public static List<VideoStream> getSortedStreamVideosList(
            @NonNull final Context context,
            @Nullable final List<VideoStream> videoStreams,
            @Nullable final List<VideoStream> videoOnlyStreams,
            final boolean ascendingOrder,
            final boolean preferVideoOnlyStreams) {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        Set<String> advancedFormats = sharedPreferences.getStringSet(
                context.getString(R.string.advanced_formats_key), new HashSet<>());
        if (advancedFormats == null) {
            // getStringSet() still returns null for a corrupt entry despite the default.
            advancedFormats = new HashSet<>();
        }
        return getSortedStreamVideosList(advancedFormats, videoStreams,
                videoOnlyStreams, ascendingOrder, preferVideoOnlyStreams);
    }

    @NonNull
    private static String computeDefaultResolution(final Context context, final int key,
                                                    final int value) {
        final SharedPreferences preferences
                = PreferenceManager.getDefaultSharedPreferences(context);

        // Load the preferred resolution otherwise the best available. getString() only
        // returns null for a corrupt/mistyped entry (the default above is non-null),
        // but coalesce defensively: every downstream helper requires non-null.
        String resolution = preferences != null
                ? preferences.getString(context.getString(key), context.getString(value))
                : context.getString(R.string.best_resolution_key);
        if (resolution == null) {
            resolution = context.getString(R.string.best_resolution_key);
        }

        final String maxResolution = StreamDataLimit.getResolutionLimit(context);
        if (maxResolution != null
                && (resolution.equals(context.getString(R.string.best_resolution_key))
                || compareVideoStreamResolution(maxResolution, resolution) < 1)) {
            resolution = maxResolution;
        }
        return resolution;
    }

    /**
     * Convert any 720p60/HFR/HDR/… string into a pure numeric key so they compare equal:
     * 1080p60 → 1080p, 1440p HDR → 1440p, etc.
     */
    private static String normalizeResolutionKey(@NonNull final String raw) {
        // CASE-insensitive removal after the "p"
        return RESOLUTION_SUFFIX_PATTERN.matcher(raw).replaceAll("p");
    }

    /**
     * Removes resolution labels while preserving the frame rate variant.
     * For example, 1080p HDR becomes 1080p while 1080p60 HDR becomes 1080p60.
     */
    private static String normalizeResolutionVariantKey(@NonNull final String raw) {
        // Lowercased so variant matching is case-insensitive (720P60 == 720p60).
        return RESOLUTION_VARIANT_SUFFIX_PATTERN.matcher(raw).replaceAll("$1")
                .toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String codecFamilyOf(@Nullable final String codec) {
        if (codec == null || codec.isEmpty()) {
            return null;
        }
        final String normalized = codec.toLowerCase(Locale.ROOT);
        // No hev1/hvc1 branch: YouTube serves no HEVC, so such codecs (if any)
        // intentionally fall through and score lowest via the default branch.
        if (normalized.startsWith("av01")) {
            return CODEC_FAMILY_AV1;
        }
        if (normalized.startsWith("vp09") || normalized.startsWith("vp9")) {
            return CODEC_FAMILY_VP9;
        }
        if (normalized.startsWith("avc")) {
            return CODEC_FAMILY_AVC;
        }
        final int separator = normalized.indexOf('.');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static int findBestVideoStreamIndex(
            @NonNull final List<VideoStream> streams,
            @Nullable final String exactResolution,
            @Nullable final String normalizedResolution,
            @Nullable final String codecFamily) {
        int bestIndex = -1;
        for (int i = 0; i < streams.size(); i++) {
            final VideoStream stream = streams.get(i);
            if (stream == null) {
                continue;
            }
            final String resolution = Objects.toString(stream.getResolution(), "");
            final boolean resolutionMatches = exactResolution != null
                    ? exactResolution.equals(resolution)
                    : normalizedResolution != null && normalizedResolution.equals(
                    normalizeResolutionKey(resolution));
            if (!resolutionMatches || (codecFamily != null
                    && !codecFamily.equals(codecFamilyOf(stream.getCodec())))) {
                continue;
            }
            if (bestIndex < 0 || compareVideoStreamResolution(
                    stream, streams.get(bestIndex)) > 0) {
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * Core selection logic that prefers an exact frame rate variant, then falls back to another
     * variant at the same effective resolution and finally to the closest lower resolution.
     * Package-private for tests.
     */
    static int getDefaultResolutionIndex(@NonNull final String targetRes,
                                          @NonNull final String bestResolutionKey,
                                          @Nullable final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return -1;
        }

        // User picked the best resolution key -> simply return the highest actual stream
        if (bestResolutionKey.equals(targetRes)) {
            return streams.indexOf(
                    Collections.max(streams, VideoStreamSelector::compareVideoStreamResolution));
        }

        final String normalizedTarget = normalizeResolutionKey(targetRes);
        final String variantKey = normalizeResolutionVariantKey(targetRes);
        // A plain target (fresh-start default like "720p") carries no fps variant: match by
        // resolution so every fps variant enters the bucket and the comparator picks the HFR
        // encode. Matching by variant first would leave only the 30fps streams in play.
        // An explicit variant target (sticky "1080p60") keeps variant-first matching.
        List<VideoStream> bucket = findMatchingStreams(streams, variantKey, normalizedTarget,
                !variantKey.equals(normalizedTarget));

        if (bucket.isEmpty()) {
            // No match: use the closest lower resolution, or the lowest one available.
            final String fallbackResolution =
                    findFallbackResolution(streams, normalizedTarget);
            bucket = findMatchingStreams(streams, fallbackResolution, fallbackResolution,
                    false);
        }

        bucket.sort(VideoStreamSelector::compareVideoStreamResolution);
        return streams.indexOf(bucket.get(bucket.size() - 1));
    }

    /**
     * Closest lower normalized resolution below the target, or the lowest one available
     * when nothing is lower. {@code null} only when {@code streams} is empty.
     */
    @Nullable
    private static String findFallbackResolution(@NonNull final List<VideoStream> streams,
                                                 @NonNull final String normalizedTarget) {
        String fallbackResolution = null;
        for (final VideoStream stream : streams) {
            if (stream == null) {
                continue;
            }
            final String resolution = normalizeResolutionKey(
                    Objects.toString(stream.getResolution(), ""));
            if (compareVideoStreamResolution(resolution, normalizedTarget) < 0
                    && (fallbackResolution == null || compareVideoStreamResolution(
                    resolution, fallbackResolution) > 0)) {
                fallbackResolution = resolution;
            }
        }
        if (fallbackResolution == null) {
            for (final VideoStream stream : streams) {
                if (stream == null) {
                    continue;
                }
                final String resolution = normalizeResolutionKey(
                        Objects.toString(stream.getResolution(), ""));
                if (fallbackResolution == null || compareVideoStreamResolution(
                        resolution, fallbackResolution) < 0) {
                    fallbackResolution = resolution;
                }
            }
        }
        return fallbackResolution;
    }

    /**
     * Streams matching the exact frame rate variant, or else the same resolution ignoring
     * the frame rate suffix. Empty if neither matches. When the target carries no fps
     * variant, callers pass {@code matchVariantFirst = false} so the fps-blind resolution
     * match runs directly and every variant enters the bucket.
     */
    private static List<VideoStream> findMatchingStreams(@NonNull final List<VideoStream> streams,
                                                         @Nullable final String variantKey,
                                                         @Nullable final String resolutionKey,
                                                         final boolean matchVariantFirst) {
        final List<VideoStream> result = new ArrayList<>();
        if (matchVariantFirst && variantKey != null) {
            for (final VideoStream stream : streams) {
                if (stream != null && variantKey.equals(normalizeResolutionVariantKey(
                        Objects.toString(stream.getResolution(), "")))) {
                    result.add(stream);
                }
            }
        }
        if (result.isEmpty() && resolutionKey != null) {
            for (final VideoStream stream : streams) {
                if (stream != null && resolutionKey.equals(normalizeResolutionKey(
                        Objects.toString(stream.getResolution(), "")))) {
                    result.add(stream);
                }
            }
        }
        return result;
    }

    /**
     * Join the two lists of video streams (video_only and normal videos),
     * and sort them according with default format chosen by the user.
     *
     * @param advancedFormats        enabled advanced formats (VP9/AV01 toggles)
     * @param videoStreams           normal videos list
     * @param videoOnlyStreams       video only stream list
     * @param ascendingOrder         true -> smallest to greatest | false -> greatest to smallest
     * @param preferVideoOnlyStreams if video-only streams should preferred when both video-only
     *                               streams and normal video streams are available
     * @return the sorted list
     */
    @NonNull
    // Package-private for tests.
    static List<VideoStream> getSortedStreamVideosList(
            @Nullable final Set<String> advancedFormats,
            @Nullable final List<VideoStream> videoStreams,
            @Nullable final List<VideoStream> videoOnlyStreams,
            final boolean ascendingOrder,
            final boolean preferVideoOnlyStreams
    ) {
        final Set<String> formats =
                advancedFormats != null ? advancedFormats : Collections.emptySet();
        final boolean useWebM = formats.contains(FORMAT_VP9_TOGGLE);
        final boolean useAV1 = formats.contains(FORMAT_AV1_TOGGLE);
        // Determine order of streams
        // The last added list is preferred
        final List<List<VideoStream>> videoStreamsOrdered =
                preferVideoOnlyStreams
                        ? Arrays.asList(videoStreams, videoOnlyStreams)
                        : Arrays.asList(videoOnlyStreams, videoStreams);

        final List<VideoStream> allInitialStreams = videoStreamsOrdered.stream()
                // Ignore lists that are null
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                // Keep only streams whose container/codec survives the user's
                // advanced-format toggles.
                .filter(stream -> {
                    // Gate by codec family, not container: a VP9 stream in an MP4
                    // container answers to the VP9 toggle, and AV1-in-WEBM to AV1.
                    final String family = codecFamilyOf(stream.getCodec());
                    if (CODEC_FAMILY_VP9.equals(family)) {
                        return useWebM;
                    } else if (CODEC_FAMILY_AV1.equals(family)) {
                        return useAV1;
                    } else {
                        return true;
                    }
                })
                .collect(Collectors.toList());

        // Return the sorted list. On key collision (same codec family, resolution
        // variant and track) keep the comparator-best copy so a higher-bitrate/HFR
        // duplicate is never lost to iteration luck before bucketing. The key keeps
        // fps-variant granularity (1080p vs 1080p60 stay distinct) so the download
        // path, which lists this output directly, loses no rows; menu bucketing
        // merges variants later.
        final HashMap<String, VideoStream> hashMap = new HashMap<>();
        for (final VideoStream videoStream : allInitialStreams) {
            final String trackSuffix = videoStream.getAudioTrackId() != null
                    ? "|" + videoStream.getAudioTrackId() : "";
            final String family = codecFamilyOf(videoStream.getCodec());
            final String key = Objects.toString(family, "") + "|"
                    + Objects.toString(videoStream.getResolution(), "")
                            .trim().toLowerCase(Locale.ROOT)
                    + trackSuffix;
            final VideoStream incumbent = hashMap.get(key);
            if (incumbent == null
                    || compareVideoStreamResolution(videoStream, incumbent) > 0) {
                hashMap.put(key, videoStream);
            }
        }

        return sortStreamList(new ArrayList<>(hashMap.values()), ascendingOrder);
    }

    /**
     * Sort the streams list depending on the parameter ascendingOrder;
     * <p>
     * It works like that:<br>
     * - Take the numeric height, adding 1 for the high-frame-rate class
     * (60fps videos, {@code HFR}, {@code HFR High}) and sort by the greatest:<br>
     * <blockquote><pre>
     *      720p        ->  720
     *      720p60      ->  721
     *      360p        ->  360
     *      1080p       ->  1080
     *      1080p60     ->  1081
     *      1080p HFR   ->  1081
     * <br>
     * ascendingOrder  ? 360 < 720 < 721 < 1080 < 1081
     * !ascendingOrder ? 1081 < 1080 < 721 < 720 < 360</pre></blockquote>
     *
     * @param videoStreams   list that the sorting will be applied
     * @param ascendingOrder true -> smallest to greatest | false -> greatest to smallest
     * @return The sorted list (same reference as parameter videoStreams)
     */
    private static List<VideoStream> sortStreamList(@NonNull final List<VideoStream> videoStreams,
                                                    final boolean ascendingOrder) {
        final Comparator<VideoStream> comparator = VideoStreamSelector::compareVideoStreamResolution;
        Collections.sort(videoStreams, ascendingOrder ? comparator : comparator.reversed());
        return videoStreams;
    }

    /**
     * Plain-height label for a resolution string ({@code 1080p HFR} → {@code 1080p}),
     * null-safe. Bucketing key component; not a display label on its own (see
     * {@link #qualityLabelOf}).
     */
    @NonNull
    public static String heightLabelOf(@Nullable final String resolution) {
        final String trimmed = Objects.toString(resolution, "").trim();
        // Bare 4K/8K labels bucket with their numeric heights so a mixed
        // 2160p + 4K source still yields one row per height.
        if (trimmed.equalsIgnoreCase("4K")) {
            return "2160p";
        }
        if (trimmed.equalsIgnoreCase("8K")) {
            return "4320p";
        }
        return normalizeResolutionKey(trimmed);
    }

    /**
     * Display label for one bucket row: height plus the representative's fps class
     * and codec family ({@code 720p60 AVC}), null-safe. Unknown resolutions fall back
     * to the raw string ({@code ?} when that is empty too); a missing codec is
     * omitted. Streams carrying an audio track (multi-audio dubs) append the track
     * name so same-height buckets never render identical rows.
     */
    @NonNull
    public static String qualityLabelOf(@NonNull final VideoStream stream) {
        final String resolution = Objects.toString(stream.getResolution(), "").trim();
        final String height = heightLabelOf(resolution);
        final String base = height.isEmpty() ? resolution
                : height + (isHfrResolution(resolution) ? "60" : "");
        final String codec = codecLabelOf(stream.getCodec());
        final String row = base.isEmpty() ? codec
                : (codec.isEmpty() ? base : base + " " + codec);
        if (row.isEmpty()) {
            return UNKNOWN_RESOLUTION_LABEL;
        }
        if (stream.getAudioTrackId() != null) {
            final String trackName = stream.getAudioTrackName();
            final String trackLabel = trackName != null && !trackName.trim().isEmpty()
                    ? trackName.trim() : stream.getAudioTrackId();
            return row + " \u00B7 " + trackLabel;
        }
        return row;
    }

    /**
     * Uppercase codec family for display ({@code avc1.64001f} → {@code AVC}), or empty
     * when the codec is missing or unrecognized.
     */
    @NonNull
    public static String codecLabelOf(@Nullable final String codec) {
        final String family = codecFamilyOf(codec);
        return family == null ? "" : family.toUpperCase(Locale.ROOT);
    }

    /**
     * Whether a resolution string denotes the high-frame-rate class: an explicit
     * {@code HFR} marker or any numeric frame-rate suffix above 30fps
     * ({@code 60}, {@code 50}, {@code 120}, …).
     */
    private static boolean isHfrResolution(@NonNull final String resolution) {
        final Matcher matcher = NUMERIC_HEIGHT_PATTERN.matcher(resolution);
        if (!matcher.find()) {
            return false;
        }
        final String affix = (resolution.substring(0, matcher.start())
                + resolution.substring(matcher.end())).toUpperCase(Locale.ROOT);
        if (affix.contains("HFR")) {
            return true;
        }
        final Matcher fps = FPS_NUMBER_PATTERN.matcher(affix);
        while (fps.find()) {
            try {
                if (Integer.parseInt(fps.group(1)) > STANDARD_FPS) {
                    return true;
                }
            } catch (final NumberFormatException ignored) {
                // Non-numeric affix fragment: keep scanning.
            }
        }
        return false;
    }

    /**
     * Bucket key for one-pick-per-resolution dedup: numeric height plus audio track,
     * null-safe. Fps ({@code 60}) and bitrate-tier ({@code HFR}, {@code HFR High})
     * suffixes are not part of the key; the winner inside a bucket is picked by
     * {@link #compareVideoStreamResolution(VideoStream, VideoStream)} (HFR preferred,
     * then higher bitrate) and the caller must dedup after audio-track filtering.
     */
    @NonNull
    public static String bucketKeyOf(@NonNull final VideoStream stream) {
        final String trackSuffix = stream.getAudioTrackId() != null
                ? "|" + stream.getAudioTrackId() : "";
        return heightLabelOf(stream.getResolution()) + trackSuffix;
    }

    /**
     * Collapse an already-sorted stream list to one representative per resolution bucket
     * (see {@link #bucketKeyOf}). Input order is preserved, so pass a pre-sorted list
     * to keep descending menu order. Empty only if the input is empty or holds no
     * non-null streams.
     *
     * @param streams sorted stream list (e.g. from {@link #getSortedStreamVideosList})
     * @return one best stream per bucket, in input order
     */
    @NonNull
    public static List<VideoStream> getDistinctResolutionStreams(
            @NonNull final List<VideoStream> streams) {
        final LinkedHashMap<String, VideoStream> buckets = new LinkedHashMap<>();
        for (final VideoStream stream : streams) {
            if (stream == null) {
                continue;
            }
            final String key = bucketKeyOf(stream);
            final VideoStream current = buckets.get(key);
            if (current == null
                    || compareVideoStreamResolution(stream, current) > 0) {
                buckets.put(key, stream);
            }
        }
        return new ArrayList<>(buckets.values());
    }

    /**
     * For external playback (which needs audio in the stream itself), drop video-only
     * streams at heights that also offer a muxed (non-video-only) stream — otherwise the
     * dialog would show a silent-video row under an identical height label. Heights with
     * only video-only streams are kept untouched. Track-blind by design: a dubbed muxed
     * stream shadows a trackless video-only one, since the caller filters by preferred
     * language first.
     *
     * @param streams language-filtered stream list
     * @return the input minus shadowed video-only streams, in input order
     */
    @NonNull
    public static List<VideoStream> dropVideoOnlyWhenMuxedExists(
            @NonNull final List<VideoStream> streams) {
        final Set<String> muxedHeights = new HashSet<>();
        for (final VideoStream stream : streams) {
            if (stream != null && !stream.isVideoOnly()) {
                muxedHeights.add(heightLabelOf(stream.getResolution()));
            }
        }
        if (muxedHeights.isEmpty()) {
            return new ArrayList<>(streams);
        }
        final List<VideoStream> result = new ArrayList<>(streams.size());
        for (final VideoStream stream : streams) {
            if (stream != null && stream.isVideoOnly()
                    && muxedHeights.contains(heightLabelOf(stream.getResolution()))) {
                continue;
            }
            result.add(stream);
        }
        return result;
    }

    /**
     * Fetches the desired resolution or returns the default if it is not found.
     * The resolution will be reduced if video chocking is active.
     *
     * @param context           Android app context
     * @param defaultResolution the default resolution
     * @param videoStreams      the list of video streams to check
     * @return the index of the preferred video stream
     */
    private static int getDefaultResolutionWithDefaultFormat(@NonNull final Context context,
                                                             @NonNull final String defaultResolution,
                                                             @Nullable final List<VideoStream> videoStreams) {
        return getDefaultResolutionIndex(defaultResolution,
                context.getString(R.string.best_resolution_key), videoStreams);
    }

    /**
     * Score a YouTube resolution string as {@code height} for standard frame rate or
     * {@code height + 1} for the high-frame-rate class ({@code 60}, {@code HFR},
     * {@code HFR High}), so the in-bucket winner prefers the HFR encode.
     * Unknown resolutions rank below every numeric height (instead of throwing)
     * so {@code Collections.max()} can never crown them as "best".
     */
    private static int calculateResolution(@NonNull final String x) {
        // Bare 4K/8K labels from non-YouTube services (YouTube itself always yields
        // numeric heights); keep their historical ranking so they never score below 144p.
        final String trimmed = x.trim();
        if (trimmed.equalsIgnoreCase("8K")) {
            return 4320;
        }
        if (trimmed.equalsIgnoreCase("4K")) {
            return 2160;
        }
        final Matcher matcher = NUMERIC_HEIGHT_PATTERN.matcher(trimmed);
        if (!matcher.find()) {
            return -1;
        }
        final int height;
        try {
            height = Integer.parseInt(matcher.group(1));
        } catch (final NumberFormatException e) {
            return -1;
        }
        return isHfrResolution(trimmed) ? height + 1 : height;
    }

    private static int compareVideoStreamResolution(@NonNull final String r1,
                                                    @NonNull final String r2) {
        // calculateResolution never throws (unknown resolutions score -1),
        // so no fallback is needed here.
        return Integer.compare(calculateResolution(r1), calculateResolution(r2));
    }

    private static int getCodecPriority(@NonNull final VideoStream stream) {
        final String codecFamily = codecFamilyOf(stream.getCodec());
        if (codecFamily == null) {
            return CODEC_PRIORITY_UNKNOWN;
        }
        switch (codecFamily) {
            case CODEC_FAMILY_AV1:
                return CODEC_PRIORITY_AV1;
            case CODEC_FAMILY_VP9:
                return CODEC_PRIORITY_VP9;
            case CODEC_FAMILY_AVC:
                return CODEC_PRIORITY_AVC;
            default:
                return CODEC_PRIORITY_UNKNOWN;
        }
    }

    // Compares the quality of two video streams.
    // VOD-side ranking only; the live manifest path ranks separately
    // (see LiveQualityController.codecPriority).
    // Package-private for tests and StreamFilters.collapseAudioTrackVariants.
    static int compareVideoStreamResolution(@Nullable final VideoStream streamA,
                                             @Nullable final VideoStream streamB) {
        if (streamA == null) {
            return streamB == null ? 0 : -1;
        }
        if (streamB == null) {
            return 1;
        }

        final int resComp = compareVideoStreamResolution(
                Objects.toString(streamA.getResolution(), ""),
                Objects.toString(streamB.getResolution(), ""));
        if (resComp != 0) {
            return resComp;
        }

        final int codecComp = getCodecPriority(streamA) - getCodecPriority(streamB);
        if (codecComp != 0) {
            return codecComp;
        }

        if (streamA.getBitrate() != streamB.getBitrate()) {
            // Prefer the higher bitrate once codec and height are equal (e.g. AV1
            // `HFR High` over `HFR`): quality over data saving inside a bucket.
            return Integer.compare(streamA.getBitrate(), streamB.getBitrate());
        }

        // Same bitrate: fall back to container. List.indexOf(null) is -1 by contract,
        // so a stream with an unset format (e.g. HLS AV1, which never sets MediaFormat)
        // sorts below any ranked container, and two unset formats compare equal here.
        return Integer.compare(
                VideoStreamSelector.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamA.getFormat()),
                VideoStreamSelector.VIDEO_FORMAT_QUALITY_RANKING.indexOf(streamB.getFormat()));
    }
}
