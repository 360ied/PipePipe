package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        assertEquals("720p60 AVC",
                VideoStreamSelector.qualityLabelOf(
                        stream("720p60", "avc1.64001f", 5000000)));
        assertEquals("1080p VP9",
                VideoStreamSelector.qualityLabelOf(
                        stream("1080p", "vp9", 8000000)));
        assertEquals("1080p60 AV1",
                VideoStreamSelector.qualityLabelOf(
                        stream("1080p HFR High", "av01.0.05M.08", 12000000)));
        assertEquals("720p",
                VideoStreamSelector.qualityLabelOf(
                        stream("720p", null, 2500000)));
        assertEquals(VideoStreamSelector.UNKNOWN_RESOLUTION_LABEL,
                VideoStreamSelector.qualityLabelOf(stream("", null, 0)));
    }

    @Test
    public void unknownResolutionAndCodecFallsBackToPlaceholder() {
        assertEquals(VideoStreamSelector.UNKNOWN_RESOLUTION_LABEL,
                VideoStreamSelector.qualityLabelOf(stream("", null, 0)));
        assertEquals(VideoStreamSelector.UNKNOWN_RESOLUTION_LABEL,
                VideoStreamSelector.qualityLabelOf(stream("", "", 0)));
    }

    @Test
    public void bare4kBucketsWithNumericHeight() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("4K", "avc1.640028", 15000000),
                        stream("2160p", "avc1.640028", 14000000)));
        assertEquals(1, distinct.size());
    }

    @Test
    public void hfrFpsVariantsAbove30WinBucket() {
        // The label keeps the stream's own rate; digit-less HFR renders as 60.
        assertEquals("720p50 AVC", VideoStreamSelector.qualityLabelOf(
                stream("720p50", "avc1.64001f", 1000000)));
        assertEquals("1080p60 AV1", VideoStreamSelector.qualityLabelOf(
                stream("1080p HFR", "av01.0.05M.08", 12000000)));
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("1080p", "avc1.640028", 9000000),
                        stream("1080p120", "avc1.640028", 5000000)));
        assertEquals(1, distinct.size());
        assertEquals("1080p120", distinct.get(0).getResolution());
        assertEquals("1080p120 AVC", VideoStreamSelector.qualityLabelOf(
                distinct.get(0)));
    }

    @Test
    public void dubbedTrackDisambiguatesLabel() {
        final VideoStream dubbed = new VideoStream.Builder()
                .setId("dubbed-label")
                .setContent("https://example.invalid/dubbed-label", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("dubbed-track")
                .setAudioTrackName("Deutsch")
                .build();
        assertEquals("720p AVC \u00B7 Deutsch",
                VideoStreamSelector.qualityLabelOf(dubbed));
    }

    @Test
    public void stickyPathPrefersExactVariantThenNormalized() {
        final List<VideoStream> streams = Arrays.asList(
                stream("1080p", "avc1.640028", 8000000),
                stream("1080p60", "avc1.640028", 9000000),
                stream("720p", "avc1.64001f", 4000000));
        // Exact sticky variant wins.
        assertEquals(1, VideoStreamSelector.getResolutionAndCodecIndex(
                "1080p60", "avc1.640028", streams));
        // Same codec, normalized resolution: best in bucket.
        assertEquals(0, VideoStreamSelector.getResolutionAndCodecIndex(
                "1080p HFR", "avc1.640028",
                Arrays.asList(streams.get(0), streams.get(2))));
        // Codec mismatch falls back to the resolution match.
        assertEquals(0, VideoStreamSelector.getResolutionAndCodecIndex(
                "1080p", "av01.0.08M.08",
                Arrays.asList(streams.get(0), streams.get(2))));
        // Nothing close: closest lower, then lowest, then empty.
        assertEquals(0, VideoStreamSelector.getResolutionAndCodecIndex(
                "2160p", "avc1.640028",
                Arrays.asList(streams.get(0), streams.get(2))));
        assertEquals(-1, VideoStreamSelector.getResolutionAndCodecIndex(
                "1080p", "avc1.640028", Arrays.asList()));
    }

    @Test
    public void nullAdvancedFormatsFallsBackToDefaults() {
        final List<VideoStream> sorted = VideoStreamSelector.getSortedStreamVideosList(
                (Set<String>) null,
                Arrays.asList(stream("720p", "avc1.64001f", 4000000)),
                null, true, false);
        assertEquals(1, sorted.size());
    }

    @Test
    public void vp9CodecSpellingsShareDedupKey() {
        final List<VideoStream> sorted = VideoStreamSelector.getSortedStreamVideosList(
                new HashSet<>(Arrays.asList("VP9")),
                Arrays.asList(
                        stream("1080p", "vp09.00.40.08", 8000000, MediaFormat.WEBM),
                        stream("1080p", "vp9", 9000000, MediaFormat.WEBM)),
                null, true, false);
        assertEquals(1, sorted.size());
        assertEquals(9000000, sorted.get(0).getBitrate());
    }

    @Test
    public void vp9InMp4AnswersToVp9Toggle() {
        final VideoStream vp9Mp4 =
                stream("720p", "vp09.00.40.08", 4000000, MediaFormat.MPEG_4);
        assertEquals(0, VideoStreamSelector.getSortedStreamVideosList(
                new HashSet<>(), Arrays.asList(vp9Mp4), null, true, false).size());
        assertEquals(1, VideoStreamSelector.getSortedStreamVideosList(
                new HashSet<>(Arrays.asList("VP9")),
                Arrays.asList(vp9Mp4), null, true, false).size());
    }

    @Test
    public void muxedAndVideoOnlySurviveDedupSeparately() {
        // Same codec, height and track but different delivery shape: both must
        // survive sorting so downloads and dropVideoOnlyWhenMuxedExists see them.
        final List<VideoStream> sorted = VideoStreamSelector.getSortedStreamVideosList(
                new HashSet<>(),
                Arrays.asList(
                        stream("720p", "avc1.64001f", 3000000, MediaFormat.MPEG_4,
                                false),
                        stream("720p", "avc1.64001f", 5000000, MediaFormat.MPEG_4,
                                true)),
                null, true, false);
        assertEquals(2, sorted.size());
    }

    @Test
    public void uhdLabelsUnifyWithNumericHeights() {
        // 2160p target matches a 4K stream exactly, not via closest-lower.
        assertEquals(0, VideoStreamSelector.getDefaultResolutionIndex(
                "2160p", "Best", Arrays.asList(
                        stream("4K", "avc1.640028", 15000000),
                        stream("1080p", "avc1.640028", 8000000))));
        // 4K60 buckets with 2160p, wins by HFR, and labels honestly.
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("2160p", "avc1.640028", 15000000),
                        stream("4K60", "avc1.640028", 9000000)));
        assertEquals(1, distinct.size());
        assertEquals("4K60", distinct.get(0).getResolution());
        assertEquals("2160p60 AVC", VideoStreamSelector.qualityLabelOf(
                distinct.get(0)));
        assertEquals("2160p", VideoStreamSelector.heightLabelOf("4K60"));
    }

    @Test
    public void allNullListsResolveToMissing() {
        assertEquals(-1, VideoStreamSelector.getDefaultResolutionIndex(
                "720p", "Best", Arrays.asList((VideoStream) null)));
        assertEquals(-1, VideoStreamSelector.getResolutionAndCodecIndex(
                "720p", "avc1.64001f", Arrays.asList((VideoStream) null)));
    }

    @Test
    public void unknownCodecFamilyOmitsCodecSuffix() {
        assertEquals("720p", VideoStreamSelector.qualityLabelOf(
                stream("720p", "hev1.1.6.L93", 4000000)));
        assertEquals("", VideoStreamSelector.codecLabelOf("opus"));
    }

    @Test
    public void blankAudioTrackBehavesAsTrackless() {
        final VideoStream blankTrack = new VideoStream.Builder()
                .setId("blank-track")
                .setContent("https://example.invalid/blank-track", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("")
                .build();
        final VideoStream trackless = stream("720p", "avc1.64001f", 4000000);
        assertEquals(VideoStreamSelector.bucketKeyOf(trackless),
                VideoStreamSelector.bucketKeyOf(blankTrack));
        assertEquals("720p AVC", VideoStreamSelector.qualityLabelOf(blankTrack));
    }

    @Test
    public void collapsePrefersNonHlsAndComparatorBest() {
        // HLS original must not displace a progressive dub; within one delivery
        // the higher bitrate wins.
        final VideoStream progressiveDub = new VideoStream.Builder()
                .setId("prog-dub")
                .setContent("https://example.invalid/prog-dub", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("dub")
                .build();
        final VideoStream hlsOriginal = new VideoStream.Builder()
                .setId("hls-orig")
                .setContent("https://example.invalid/hls-orig", true)
                .setDeliveryMethod(DeliveryMethod.HLS)
                .setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("orig")
                .setAudioTrackName("Original")
                .build();
        final VideoStream thinProgressive = new VideoStream.Builder()
                .setId("prog-thin")
                .setContent("https://example.invalid/prog-thin", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("other-dub")
                .setBitrate(1000000)
                .build();
        final VideoStream fatProgressive = new VideoStream.Builder()
                .setId("prog-fat")
                .setContent("https://example.invalid/prog-fat", true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(false)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setResolution("720p")
                .setCodec("avc1.64001f")
                .setAudioTrackId("another-dub")
                .setBitrate(5000000)
                .build();
        final List<VideoStream> collapsed = StreamFilters.collapseAudioTrackVariants(
                Arrays.asList(hlsOriginal, progressiveDub));
        assertEquals(1, collapsed.size());
        assertEquals("prog-dub", collapsed.get(0).getId());
        final List<VideoStream> best = StreamFilters.collapseAudioTrackVariants(
                Arrays.asList(thinProgressive, fatProgressive));
        assertEquals(1, best.size());
        assertEquals("prog-fat", best.get(0).getId());
    }

    @Test
    public void vp9SpellingsCollapseToBest() {
        final VideoStream vp09 = stream("480p", "vp09.00.40.08", 1000000,
                MediaFormat.WEBM);
        final VideoStream vp9 = stream("480p", "vp9", 2000000, MediaFormat.WEBM);
        final List<VideoStream> collapsed = StreamFilters.collapseAudioTrackVariants(
                Arrays.asList(vp09, vp9));
        assertEquals(1, collapsed.size());
        assertEquals(2000000, collapsed.get(0).getBitrate());
    }

    @Test
    public void nullStreamsCompareEqual() {
        assertEquals(0, VideoStreamSelector.compareVideoStreamResolution(
                (VideoStream) null, (VideoStream) null));
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
