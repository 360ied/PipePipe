package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
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
                                      final int bitrate, final MediaFormat format) {
        return new VideoStream.Builder()
                .setId(resolution + "|" + codec + "|" + bitrate)
                .setContent("https://example.invalid/" + resolution + codec + bitrate,
                        true)
                .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                .setIsVideoOnly(true)
                .setMediaFormat(format)
                .setResolution(resolution)
                .setCodec(codec)
                .setBitrate(bitrate)
                .build();
    }

    private static VideoStream stream(final String resolution, final String codec,
                                      final int bitrate) {
        return stream(resolution, codec, bitrate, MediaFormat.MPEG_4);
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
    public void unknownResolutionSurvivesAsOwnFallbackBucket() {
        final List<VideoStream> distinct = VideoStreamSelector.getDistinctResolutionStreams(
                Arrays.asList(
                        stream("", "avc1.64001f", 500000),
                        stream("720p", "avc1.64001f", 4000000)));
        assertEquals(2, distinct.size());
    }
}
