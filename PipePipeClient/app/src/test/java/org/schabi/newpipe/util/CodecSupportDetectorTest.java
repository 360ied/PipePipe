package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Mapping of queried decoders onto the VP9/AV01 toggles: only hardware decoders passing
 * the 2160p gate check the box, software-only is reported distinctly but unchecked,
 * and ExoPlayer wins disagreements with the framework list.
 */
public class CodecSupportDetectorTest {

    private static CodecSupportDetector.DecoderInfo decoder(final String name,
                                                           final boolean hardwareAccelerated,
                                                           final boolean softwareOnly,
                                                           final boolean supports2160p,
                                                           final boolean supports2160p60) {
        return new CodecSupportDetector.DecoderInfo(
                name, hardwareAccelerated, softwareOnly, supports2160p, supports2160p60);
    }

    private static CodecSupportDetector.DecoderInfo hw(final String name) {
        return decoder(name, true, false, true, true);
    }

    private static CodecSupportDetector.DecoderInfo hwNo60(final String name) {
        return decoder(name, true, false, true, false);
    }

    private static CodecSupportDetector.DecoderInfo sw(final String name) {
        return decoder(name, false, true, true, true);
    }

    @Test
    public void hwVp9AndSwOnlyAv1MapsToCheckedVp9Only() {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> mime.equals(CodecSupportDetector.MIME_VP9)
                                ? Collections.singletonList(hw("c2.vp9.dec"))
                                : Collections.singletonList(sw("c2.av1.sw.dec")),
                        mime -> {
                            throw new AssertionError("framework must not be queried");
                        });

        assertEquals(CodecSupportDetector.Support.SUPPORTED_HW,
                results.get(CodecSupportDetector.TOGGLE_VP9).getSupport());
        assertEquals(CodecSupportDetector.Support.SUPPORTED_SW_ONLY,
                results.get(CodecSupportDetector.TOGGLE_AV1).getSupport());
        assertTrue(CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_VP9)));
        assertFalse(CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_AV1)));
    }

    @Test
    public void emptyListIsUnsupported() {
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(Collections.emptyList());
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED, result.getSupport());
        assertNull(result.getDecoderName());
        assertFalse(CodecSupportDetector.isChecked(result));

        assertEquals(Collections.emptySet(),
                CodecSupportDetector.applyToStoredSet(false, false));
    }

    @Test
    public void exoPreferredOverFrameworkOnDisagreement() {
        // Framework-only truth (HW for both MIMEs) must not leak in: a non-empty
        // ExoPlayer answer wins per MIME, even when it gate-fails.
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> Collections.singletonList(
                                decoder("c2.dec", true, false, false, false)),
                        mime -> Collections.singletonList(hw("c2.fw.dec")));

        assertEquals(CodecSupportDetector.Support.UNSUPPORTED,
                results.get(CodecSupportDetector.TOGGLE_VP9).getSupport());
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED,
                results.get(CodecSupportDetector.TOGGLE_AV1).getSupport());
        assertFalse(CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_VP9)));
    }

    @Test
    public void exoEmptyFallsBackToFramework() {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> Collections.emptyList(),
                        mime -> mime.equals(CodecSupportDetector.MIME_AV1)
                                ? Collections.singletonList(hw("c2.av1.dec"))
                                : Collections.emptyList());

        assertEquals(CodecSupportDetector.Support.UNSUPPORTED,
                results.get(CodecSupportDetector.TOGGLE_VP9).getSupport());
        assertEquals(CodecSupportDetector.Support.SUPPORTED_HW,
                results.get(CodecSupportDetector.TOGGLE_AV1).getSupport());
        assertEquals("c2.av1.dec", results.get(
                CodecSupportDetector.TOGGLE_AV1).getDecoderName());
    }

    @Test
    public void exoThrowingFallsBackToFramework() {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> {
                            throw new RuntimeException("DecoderQueryException");
                        },
                        mime -> Collections.singletonList(hw("OMX.qcom.vp9.dec")));

        assertEquals(CodecSupportDetector.Support.SUPPORTED_HW,
                results.get(CodecSupportDetector.TOGGLE_VP9).getSupport());
    }

    @Test
    public void hardwareWithout2160pStaysUnchecked() {
        final List<CodecSupportDetector.DecoderInfo> decoders =
                Collections.singletonList(decoder("c2.vp9.dec", true, false, false, false));
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(decoders);
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED, result.getSupport());
        assertFalse(CodecSupportDetector.isChecked(result));
        // The backing decoder is still recorded for dialog/debug use.
        assertEquals("c2.vp9.dec", result.getDecoderName());
    }

    @Test
    public void nullAndNullEntriesAreUnsupported() {
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED,
                CodecSupportDetector.evaluate(null).getSupport());
        assertFalse(CodecSupportDetector.isChecked(null));
        assertFalse(CodecSupportDetector.isChecked(
                CodecSupportDetector.evaluate(null)));

        final List<CodecSupportDetector.DecoderInfo> holes =
                Arrays.asList(null, decoder(null, false, false, false, false));
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(holes);
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED, result.getSupport());
        assertNull(result.getDecoderName());
    }

    @Test
    public void unknownNatureDecoderStaysUnchecked() {
        // Neither hardware nor software flagged: safe bucket, unchecked.
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(Collections.singletonList(
                        decoder("c2.mystery.dec", false, false, true, true)));
        assertEquals(CodecSupportDetector.Support.SUPPORTED_SW_ONLY, result.getSupport());
        assertFalse(CodecSupportDetector.isChecked(result));
    }

    @Test
    public void hardwareWithout60FpsStillCheckedWithAdvisory() {
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(
                        Collections.singletonList(hwNo60("c2.av1.dec")));
        assertEquals(CodecSupportDetector.Support.SUPPORTED_HW, result.getSupport());
        assertTrue(CodecSupportDetector.isChecked(result));
        assertFalse(result.getSupports2160p60());
        assertTrue(CodecSupportDetector.shows60FpsAdvisory(result));
    }

    @Test
    public void hardwareWith60FpsHasNoAdvisory() {
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(
                        Collections.singletonList(hw("c2.av1.dec")));
        assertTrue(result.getSupports2160p60());
        assertTrue(CodecSupportDetector.isChecked(result));
        assertFalse(CodecSupportDetector.shows60FpsAdvisory(result));
    }

    @Test
    public void advisoryOnlyOnCheckedHardware() {
        assertFalse(CodecSupportDetector.shows60FpsAdvisory(null));
        assertFalse(CodecSupportDetector.shows60FpsAdvisory(
                CodecSupportDetector.evaluate(Collections.emptyList())));
        assertFalse(CodecSupportDetector.shows60FpsAdvisory(
                CodecSupportDetector.evaluate(
                        Collections.singletonList(sw("c2.av1.sw.dec")))));
    }

    @Test
    public void detectSyncCarries60FpsAdvisory() {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> Collections.singletonList(hwNo60("c2.vp9.dec")),
                        mime -> {
                            throw new AssertionError("framework must not be queried");
                        });
        assertTrue(CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_VP9)));
        assertTrue(CodecSupportDetector.shows60FpsAdvisory(
                results.get(CodecSupportDetector.TOGGLE_VP9)));
        assertEquals("c2.vp9.dec", results.get(
                CodecSupportDetector.TOGGLE_VP9).getDecoderName());
    }

    @Test
    public void detectionResultRecordsTestedSize() {
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(
                        Collections.singletonList(hw("c2.vp9.dec")));
        assertEquals(CodecSupportDetector.PROBE_WIDTH, result.getTestedWidth());
        assertEquals(CodecSupportDetector.PROBE_HEIGHT, result.getTestedHeight());
        assertEquals(CodecSupportDetector.PROBE_FPS, result.getTestedFrameRate(), 0.0);
    }

    @Test
    public void hardwareWinsOverEarlierSoftwareEntry() {
        final CodecSupportDetector.DetectionResult result =
                CodecSupportDetector.evaluate(
                        Arrays.asList(sw("c2.sw.dec"), hw("c2.hw.dec")));
        assertEquals(CodecSupportDetector.Support.SUPPORTED_HW, result.getSupport());
        assertEquals("c2.hw.dec", result.getDecoderName());
        assertTrue(CodecSupportDetector.isChecked(result));
    }

    @Test
    public void bothQueriersThrowingIsUnsupported() {
        final Map<String, CodecSupportDetector.DetectionResult> results =
                CodecSupportDetector.detectSync(
                        mime -> {
                            throw new RuntimeException("exo");
                        },
                        mime -> {
                            throw new RuntimeException("framework");
                        });
        assertEquals(CodecSupportDetector.Support.UNSUPPORTED,
                results.get(CodecSupportDetector.TOGGLE_VP9).getSupport());
        assertNull(results.get(CodecSupportDetector.TOGGLE_VP9).getDecoderName());
        assertFalse(CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_AV1)));
    }

    @Test
    public void applySetsToggles() {
        assertEquals(new HashSet<>(Arrays.asList(
                        CodecSupportDetector.TOGGLE_VP9,
                        CodecSupportDetector.TOGGLE_AV1)),
                CodecSupportDetector.applyToStoredSet(true, true));
        assertEquals(new HashSet<>(Collections.singletonList(
                        CodecSupportDetector.TOGGLE_VP9)),
                CodecSupportDetector.applyToStoredSet(true, false));
        assertEquals(new HashSet<>(Collections.singletonList(
                        CodecSupportDetector.TOGGLE_AV1)),
                CodecSupportDetector.applyToStoredSet(false, true));
    }
}
