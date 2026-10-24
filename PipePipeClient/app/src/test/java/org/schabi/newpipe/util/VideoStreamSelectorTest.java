package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.VideoStream;

/**
 * One-pick-per-resolution bucketing: each numeric height collapses to a single row,
 * HFR preferred inside a bucket, higher bitrate wins ties.
 */
public class VideoStreamSelectorTest {

    private static VideoStream stream(final String resolution, final String codec,
                                      final int bitrate) {
        return stream(resolution, codec, bitrate, MediaFormat.MPEG_4, true);
    }

    private static VideoStream stream(final String resolution, final String codec,
                                      final int bitrate, final MediaFormat format) {
        return stream(resolution, codec, bitrate, format, true);
    }

    private static VideoStream stream(final String resolution, final String codec,
                                      final int bitrate, final MediaFormat format,
                                      final boolean isVideoOnly) {
        return new VideoStream.Builder()
                .setId(resolution + "|" + codec + "|" + bitrate + "|" + isVideoOnly)
                .setContent("https://example.invalid/" + resolution + codec + bitrate
                        + isVideoOnly, true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(isVideoOnly)
                .setMediaFormat(format)
                .setResolution(resolution)
                .setCodec(codec)
                .setBitrate(bitrate)
                .build();
    }

    @Test
    public void heightLabelOfStripsFpsAndTierSuffixes() {
        assertEquals("1080p", VideoStreamSelector.heightLabelOf("1080p"));
        assertEquals("1080p", VideoStreamSelector.heightLabelOf("1080p60"));
        assertEquals("1080p", VideoStreamSelector.heightLabelOf("1080p HFR"));
        assertEquals("1080p", VideoStreamSelector.heightLabelOf("1080p HFR High"));
        assertEquals("720p", VideoStreamSelector.heightLabelOf("720p60"));
        assertEquals("", VideoStreamSelector.heightLabelOf(null));
        assertEquals("", VideoStreamSelector.heightLabelOf(""));
    }

    @Test
    public void bucketKeyOfIgnoresFpsAndTierButKeepsAudioTrack() {
        final VideoStream hfr = stream("1080p HFR", "av01.0.08M.08", 1);
        final VideoStream sixty = stream("1080p60", "avc1.640028", 1);
        assertEquals(VideoStreamSelector.bucketKeyOf(hfr),
                VideoStreamSelector.bucketKeyOf(sixty));

        final VideoStream dubbed = new VideoStream.Builder()
                .setId("dubbed")
                .setContent("https://example.invalid/dubbed", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("dubbed-track")
                .build();
        final VideoStream original = stream("720p", "avc1.64001f", 1);
        assertEquals(2, VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(dubbed, original)).size());
    }

    @Test
    public void representativeYouTubeSetCollapsesToOneRowPerHeight() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("1080p", "avc1.640028", 8000000),
                        stream("1080p", "vp09.00.40.08", 9000000,
                                MediaFormat.WEBM),
                        stream("1080p HFR", "av01.0.08M.08", 10000000),
                        stream("1080p HFR High", "av01.0.09M.10.0.110.09.16.09.0",
                                16000000),
                        stream("1080p60", "avc1.640028", 12000000),
                        stream("1080p60", "vp09.00.40.08", 11000000,
                                MediaFormat.WEBM),
                        stream("720p", "avc1.64001f", 4000000)));
        assertEquals(2, distinct.size());
        assertEquals("1080p", VideoStreamSelector.heightLabelOf(
                distinct.get(0).getResolution()));
        // AV1 outranks VP9/AVC and HFR High outranks HFR by bitrate.
        assertEquals("1080p HFR High", distinct.get(0).getResolution());
        assertEquals("720p", distinct.get(1).getResolution());
    }

    @Test
    public void hfrBeatsHigherBitrate30fpsInsideBucket() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("1080p", "avc1.640028", 8000000),
                        stream("1080p60", "avc1.640028", 6000000)));
        assertEquals(1, distinct.size());
        assertEquals("1080p60", distinct.get(0).getResolution());
    }

    @Test
    public void higherBitrateWinsSameCodecSameHeight() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("480p", "avc1.64001f", 1000000),
                        stream("480p", "avc1.64001f", 2000000)));
        assertEquals(1, distinct.size());
        assertEquals(2000000, distinct.get(0).getBitrate());
    }

    @Test
    public void dropsVideoOnlyShadowedByMuxedAtSameHeight() {
        final List<VideoStream> result =
                VideoStreamSelector.dropVideoOnlyWhenMuxedExists(Arrays.asList(
                        stream("720p", "avc1.64001f", 4000000, MediaFormat.MPEG_4, true),
                        stream("720p", "avc1.64001f", 3000000, MediaFormat.MPEG_4,
                                false),
                        stream("2160p", "avc1.640028", 15000000, MediaFormat.MPEG_4,
                                true)));
        // Shadowed 720p video-only is dropped; lone 2160p video-only survives.
        assertEquals(2, result.size());
        assertEquals(false, result.get(0).isVideoOnly());
        assertEquals("720p", result.get(0).getResolution());
        assertEquals("2160p", result.get(1).getResolution());
    }

    @Test
    public void bare4k8kLabelsKeepHistoricalRanking() {
        // Non-YouTube services may emit bare "4K"/"8K": they must outrank FHD,
        // never sink below 144p.
        final VideoStream uhd = stream("4K", "avc1.640028", 15000000);
        final VideoStream fhd = stream("1080p", "avc1.640028", 8000000);
        assertEquals(0, VideoStreamSelector.getDefaultResolutionIndex(
                "Best", "Best", Arrays.asList(uhd, fhd)));
    }

    @Test
    public void dedupKeepsBestCopyOnCollision() {
        // Same codec, resolution and track with different bitrates: the survivor
        // must be the comparator-best, not iteration luck.
        final List<VideoStream> sorted = VideoStreamSelector.getSortedStreamVideosList(
                new HashSet<>(),
                Arrays.asList(
                        stream("1080p", "avc1.640028", 3000000),
                        stream("1080p", "avc1.640028", 8000000)),
                null, true, false);
        assertEquals(1, sorted.size());
        assertEquals(8000000, sorted.get(0).getBitrate());
    }

    @Test
    public void qualityLabelShowsHeightFpsAndCodec() {
        assertEquals("720p60 · AVC",
                VideoStreamSelector.qualityLabelOf(
                        stream("720p60", "avc1.64001f", 5000000)));
        assertEquals("1080p · VP9",
                VideoStreamSelector.qualityLabelOf(
                        stream("1080p", "vp9", 8000000)));
        assertEquals("1080p60 · AV1",
                VideoStreamSelector.qualityLabelOf(
                        stream("1080p HFR High", "av01.0.05M.08", 12000000)));
        assertEquals("720p",
                VideoStreamSelector.qualityLabelOf(
                        stream("720p", null, 2500000)));
        assertEquals("",
                VideoStreamSelector.qualityLabelOf(stream("", null, 0)));
    }

    @Test
    public void plainTargetPrefersHfrInsideBucket() {
        // Fresh-start default "720p": the 60fps variant must enter the bucket even though
        // the 30fps stream carries a far higher bitrate.
        final VideoStream hfr = stream("720p60", "avc1.64001f", 5000000);
        final VideoStream plain = stream("720p", "avc1.64001f", 9000000);
        assertEquals(0, VideoStreamSelector.getDefaultResolutionIndex(
                "720p", "Best", Arrays.asList(hfr, plain)));
    }

    @Test
    public void explicitVariantTargetKeepsVariantPreference() {
        // Sticky "720p60" from a previous session still prefers the 60fps encode.
        final VideoStream hfr = stream("720p60", "avc1.64001f", 5000000);
        final VideoStream plain = stream("720p", "avc1.64001f", 9000000);
        assertEquals(1, VideoStreamSelector.getDefaultResolutionIndex(
                "720p60", "Best", Arrays.asList(plain, hfr)));
    }

    @Test
    public void unknownResolutionSurvivesAsOwnFallbackBucket() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("", "avc1.64001f", 500000),
                        stream("720p", "avc1.64001f", 4000000)));
        assertEquals(2, distinct.size());
    }
}
