package org.schabi.newpipe.util;

import android.media.MediaCodecList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects which advanced video formats (VP9 / AV01) the device can actually decode.
 *
 * <p>
 * The {@code advanced_formats} preference is a pure stored set checked by
 * {@code VideoStreamSelector} / {@code StreamFilters} with no hardware query of any kind.
 * This helper answers "what would the actual renderer pick" by asking ExoPlayer's
 * {@link MediaCodecSelector} first (consistent with {@code CustomRenderersFactory}) and
 * falling back to the framework {@link MediaCodecList}.
 * </p>
 *
 * <p>
 * The mapping is deliberately conservative: only hardware decoders passing the 2160p30
 * size/rate gate recommend enabling a toggle. Software-only decoders are reported
 * distinctly ({@link Support#SUPPORTED_SW_ONLY}) but map to unchecked, since the bundled
 * software AV1 decoder cannot render 4K smoothly. EC-3 is out of scope and never touched.
 * </p>
 *
 * <p>
 * The evaluation itself ({@link #evaluate(List)}) is pure and unit-testable; only the two
 * {@link DecoderQuerier}s touch Android/ExoPlayer APIs and must run off the UI thread.
 * </p>
 */
public final class CodecSupportDetector {
    /** MIME probed for the {@code VP9} toggle. */
    public static final String MIME_VP9 = "video/x-vnd.on2.vp9";
    /** MIME probed for the {@code AV01} toggle. */
    public static final String MIME_AV1 = "video/av01";

    /** Stored-set token for the VP9 family (see {@code VideoStreamSelector}). */
    public static final String TOGGLE_VP9 = "VP9";
    /** Stored-set token for the AV1 family (see {@code VideoStreamSelector}). */
    public static final String TOGGLE_AV1 = "AV01";
    /** Stored-set token preserved but never probed. */
    public static final String TOGGLE_EC3 = "EC-3";

    /** Probe width: 2160p, since advanced formats exist largely to unlock 2K/4K. */
    public static final int PROBE_WIDTH = 3840;
    /** Probe height: 2160p. */
    public static final int PROBE_HEIGHT = 2160;
    /** Probe frame rate. Profiles, 60 fps and HDR are intentionally not probed. */
    public static final double PROBE_FPS = 30.0;

    /** Detection outcome for one toggle. */
    public enum Support {
        /** Hardware decoder passes the 2160p30 gate: recommend enabling. */
        SUPPORTED_HW,
        /** Only a software decoder passes the gate: report, but leave unchecked. */
        SUPPORTED_SW_ONLY,
        /** No decoder, or none passes the gate: leave unchecked. */
        UNSUPPORTED
    }

    /**
     * Plain decoder description used by {@link #evaluate(List)}.
     * Kept free of Android/ExoPlayer types so the mapping is JVM-testable.
     */
    public static final class DecoderInfo {
        public final String name;
        public final boolean hardwareAccelerated;
        public final boolean softwareOnly;
        public final boolean supports2160p;

        public DecoderInfo(final String name,
                           final boolean hardwareAccelerated,
                           final boolean softwareOnly,
                           final boolean supports2160p) {
            this.name = name;
            this.hardwareAccelerated = hardwareAccelerated;
            this.softwareOnly = softwareOnly;
            this.supports2160p = supports2160p;
        }
    }

    /** Detection outcome for one toggle, plus the backing decoder for dialog/debug use. */
    public static final class DetectionResult {
        private final Support support;
        @Nullable
        private final String decoderName;
        private final int testedWidth;
        private final int testedHeight;
        private final double testedFrameRate;

        public DetectionResult(@NonNull final Support support,
                               @Nullable final String decoderName) {
            this.support = support;
            this.decoderName = decoderName;
            this.testedWidth = PROBE_WIDTH;
            this.testedHeight = PROBE_HEIGHT;
            this.testedFrameRate = PROBE_FPS;
        }

        public Support getSupport() {
            return support;
        }

        @Nullable
        public String getDecoderName() {
            return decoderName;
        }

        public int getTestedWidth() {
            return testedWidth;
        }

        public int getTestedHeight() {
            return testedHeight;
        }

        public double getTestedFrameRate() {
            return testedFrameRate;
        }
    }

    /**
     * Source of decoder descriptions for one MIME type.
     * Production instances wrap ExoPlayer / the framework list; tests inject fakes.
     */
    public interface DecoderQuerier {
        @NonNull
        List<DecoderInfo> queryInfos(String mime) throws Exception;
    }

    private CodecSupportDetector() {
    }

    /**
     * Pure mapping from queried decoders to a result. First hardware decoder passing the
     * size gate wins; otherwise the first passing software/unknown decoder is reported as
     * {@link Support#SUPPORTED_SW_ONLY}; otherwise {@link Support#UNSUPPORTED} (keeping the
     * first decoder name for debugging when any decoder exists at all).
     */
    @NonNull
    public static DetectionResult evaluate(@Nullable final List<DecoderInfo> decoders) {
        if (decoders != null) {
            DecoderInfo softwareCandidate = null;
            for (final DecoderInfo decoder : decoders) {
                if (decoder == null || !decoder.supports2160p) {
                    continue;
                }
                if (decoder.hardwareAccelerated) {
                    return new DetectionResult(Support.SUPPORTED_HW, decoder.name);
                }
                if (softwareCandidate == null) {
                    softwareCandidate = decoder;
                }
            }
            if (softwareCandidate != null) {
                return new DetectionResult(Support.SUPPORTED_SW_ONLY, softwareCandidate.name);
            }
            return new DetectionResult(Support.UNSUPPORTED, firstDecoderName(decoders));
        }
        return new DetectionResult(Support.UNSUPPORTED, null);
    }

    @Nullable
    private static String firstDecoderName(final List<DecoderInfo> decoders) {
        for (final DecoderInfo decoder : decoders) {
            if (decoder != null && decoder.name != null) {
                return decoder.name;
            }
        }
        return null;
    }

    /**
     * Whether the toggle should be checked given its detection result.
     * Only {@link Support#SUPPORTED_HW} checks the box; software-only maps to unchecked.
     */
    public static boolean isChecked(@Nullable final DetectionResult result) {
        return result != null && result.getSupport() == Support.SUPPORTED_HW;
    }

    /**
     * Builds the stored set for the confirm-dialog Apply action / first-run seed:
     * VP9/AV01 follow the passed (recommended or user-confirmed) states, any stored EC-3
     * value is preserved.
     */
    @NonNull
    public static Set<String> applyToStoredSet(@Nullable final Set<String> stored,
                                               final boolean vp9Checked,
                                               final boolean av1Checked) {
        final Set<String> result = new HashSet<>();
        if (vp9Checked) {
            result.add(TOGGLE_VP9);
        }
        if (av1Checked) {
            result.add(TOGGLE_AV1);
        }
        if (stored != null && stored.contains(TOGGLE_EC3)) {
            result.add(TOGGLE_EC3);
        }
        return result;
    }

    /**
     * Probes both video toggles. Tries the ExoPlayer querier first per MIME (renderer
     * consistency: a framework decoder ExoPlayer would not pick must not enable a toggle);
     * falls back to the framework querier when ExoPlayer throws or reports nothing.
     * Must run off the UI thread; never throws.
     */
    @NonNull
    public static Map<String, DetectionResult> detectSync(
            @NonNull final DecoderQuerier exoPlayerQuerier,
            @NonNull final DecoderQuerier frameworkQuerier) {
        final Map<String, DetectionResult> results = new LinkedHashMap<>();
        results.put(TOGGLE_VP9, detectOne(MIME_VP9, exoPlayerQuerier, frameworkQuerier));
        results.put(TOGGLE_AV1, detectOne(MIME_AV1, exoPlayerQuerier, frameworkQuerier));
        return results;
    }

    /**
     * Production probe: ExoPlayer primary, framework fallback. Must run off the UI thread;
     * never throws.
     */
    @NonNull
    public static Map<String, DetectionResult> detectSync() {
        return detectSync(CodecSupportDetector::queryExoPlayer,
                CodecSupportDetector::queryFramework);
    }

    @NonNull
    private static DetectionResult detectOne(@NonNull final String mime,
                                             @NonNull final DecoderQuerier exoPlayerQuerier,
                                             @NonNull final DecoderQuerier frameworkQuerier) {
        try {
            final List<DecoderInfo> exoInfos = exoPlayerQuerier.queryInfos(mime);
            if (exoInfos != null && !exoInfos.isEmpty()) {
                return evaluate(exoInfos);
            }
        } catch (final Exception ignored) {
            // Fall through to the framework list below. No logging here: this path
            // is JVM-tested (android.util.Log stubs throw on unit tests).
        }
        try {
            final List<DecoderInfo> frameworkInfos = frameworkQuerier.queryInfos(mime);
            return evaluate(frameworkInfos != null ? frameworkInfos
                    : Collections.emptyList());
        } catch (final Exception ignored) {
            // Same testability note as above: no android.util.Log on this path.
            return new DetectionResult(Support.UNSUPPORTED, null);
        }
    }

    /**
     * Queries ExoPlayer's decoder selector, answering "what would the renderer pick".
     */
    @NonNull
    static List<DecoderInfo> queryExoPlayer(final String mime)
            throws MediaCodecUtil.DecoderQueryException {
        final List<MediaCodecInfo> infos =
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false);
        final List<DecoderInfo> result = new ArrayList<>(infos.size());
        for (final MediaCodecInfo info : infos) {
            result.add(new DecoderInfo(info.name, info.hardwareAccelerated, info.softwareOnly,
                    info.isVideoSizeAndRateSupportedV21(PROBE_WIDTH, PROBE_HEIGHT, PROBE_FPS)));
        }
        return result;
    }

    /**
     * Queries the framework codec list. Fallback and unit-test seam for devices where the
     * ExoPlayer query throws or is empty.
     */
    @NonNull
    static List<DecoderInfo> queryFramework(final String mime) {
        final List<DecoderInfo> result = new ArrayList<>();
        final MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (final android.media.MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.isEncoder() || !supportsType(info, mime)) {
                continue;
            }
            result.add(new DecoderInfo(info.getName(), info.isHardwareAccelerated(),
                    info.isSoftwareOnly(), supportsSize(info, mime)));
        }
        return result;
    }

    private static boolean supportsType(final android.media.MediaCodecInfo info,
                                        final String mime) {
        for (final String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsSize(final android.media.MediaCodecInfo info,
                                        final String mime) {
        try {
            return info.getCapabilitiesForType(mime).getVideoCapabilities()
                    .areSizeAndRateSupported(PROBE_WIDTH, PROBE_HEIGHT, PROBE_FPS);
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
