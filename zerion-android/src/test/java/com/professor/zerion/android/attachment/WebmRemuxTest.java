package com.professor.zerion.android.attachment;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WebmRemuxTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 50L * 1024 * 1024;
	private static final String VP8 = "video/x-vnd.on2.vp8";
	private static final String VP9 = "video/x-vnd.on2.vp9";
	private static final String VORBIS = "audio/vorbis";
	private static final String OPUS = "audio/opus";
	private static final String AVC = "video/avc";
	private static final String AAC = "audio/mp4a-latm";
	private static final Set<String> WEBM_CODECS =
			new HashSet<>(Arrays.asList(VP8, VP9, VORBIS, OPUS));

	private static final byte[] MINIMAL_MP4 = minimalMp4();

	private Context ctx;
	private final List<File> temp = new ArrayList<>();

	private List<String> inputTracks;
	private List<Sample> inputSamples;
	private boolean appendsPageSamples;
	private Set<String> mp4Refuses;
	private final Map<String, Recording> recordings =
			new LinkedHashMap<>();

	private static final class Sample {
		final int track;
		final byte[] data;
		final long time;

		Sample(int track, byte[] data, long time) {
			this.track = track;
			this.data = data;
			this.time = time;
		}
	}

	private static final class Recording {
		final int format;
		final List<String> tracks = new ArrayList<>();
		final List<Sample> written = new ArrayList<>();

		Recording(int format) {
			this.format = format;
		}
	}

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		appendsPageSamples = true;
		mp4Refuses = new HashSet<>(Arrays.asList(VP8, VORBIS));
		recordings.clear();
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private static byte[] u32(long v) {
		return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8),
				(byte) v};
	}

	private static byte[] box(String type, byte[] body) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(u32(body.length + 8), 0, 4);
		out.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
		out.write(body, 0, body.length);
		return out.toByteArray();
	}

	private static byte[] minimalMp4() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[][] boxes = {box("ftyp", "isom\0\0\0\0isom".getBytes(
				StandardCharsets.US_ASCII)), box("moov", box("mvhd",
				new byte[100])), box("mdat", new byte[32])};
		for (byte[] b : boxes) out.write(b, 0, b.length);
		return out.toByteArray();
	}

	private static byte[] packet(int track, int index) {
		byte[] p = new byte[30 + (index * 17 + track * 5) % 90];
		for (int i = 0; i < p.length; i++) {
			p[i] = (byte) (i * 7 + index * 3 + track);
		}
		return p;
	}

	private void media(String... tracks) {
		inputTracks = Arrays.asList(tracks);
		inputSamples = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			for (int t = 0; t < tracks.length; t++) {
				inputSamples.add(new Sample(t, packet(t, i),
						i * 40_000L + t * 1000L));
			}
		}
	}

	private static MediaFormat format(String mime) {
		return mime.startsWith("video/")
				? MediaFormat.createVideoFormat(mime, 320, 240)
				: MediaFormat.createAudioFormat(mime, 48000, 2);
	}

	private final class FakeSource {
		final List<String> tracks;
		final List<Sample> samples;
		final Set<Integer> selected = new HashSet<>();
		final Map<Integer, Integer> next = new HashMap<>();

		FakeSource(List<String> tracks, List<Sample> samples) {
			this.tracks = tracks;
			this.samples = samples;
		}

		int current() {
			int best = -1;
			for (int t : selected) {
				Integer from = next.get(t);
				for (int i = from == null ? 0 : from; i < samples.size(); i++) {
					if (samples.get(i).track == t) {
						if (best < 0 || i < best) best = i;
						break;
					}
				}
			}
			return best;
		}

		boolean pageCount(int track) {
			return appendsPageSamples && VORBIS.equals(tracks.get(track));
		}

		long size() {
			int i = current();
			if (i < 0) return -1;
			Sample s = samples.get(i);
			return s.data.length + (pageCount(s.track) ? 4 : 0);
		}

		int read(ByteBuffer b) {
			int i = current();
			if (i < 0) return -1;
			Sample s = samples.get(i);
			int size = s.data.length + (pageCount(s.track) ? 4 : 0);
			if (b.capacity() < size) throw new IllegalArgumentException();
			b.clear();
			b.put(s.data);
			if (pageCount(s.track)) b.put(new byte[] {-1, -1, -1, -1});
			b.position(0);
			b.limit(size);
			return size;
		}

		void advance() {
			int i = current();
			if (i >= 0) next.put(samples.get(i).track, i + 1);
		}
	}

	private Uri stage() throws IOException {
		File f = File.createTempFile("zt_webm_", ".webm", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), new byte[] {0x1A, 0x45, (byte) 0xDF,
				(byte) 0xA3, 0, 0, 0, 0});
		return Uri.fromFile(f);
	}

	private SharedMediaSanitizer.Cleaned share(String declared)
			throws IOException {
		Uri uri = stage();
		byte[] head = Files.readAllBytes(new File(uri.getPath()).toPath());
		Map<MediaExtractor, FakeSource[]> sources = new HashMap<>();
		try (MockedConstruction<MediaExtractor> ignored =
				mockConstruction(MediaExtractor.class, (m, c) -> {
					FakeSource[] src = {null};
					sources.put(m, src);
					doAnswer(inv -> {
						src[0] = new FakeSource(inputTracks, inputSamples);
						return null;
					}).when(m).setDataSource(any(FileDescriptor.class));
					doAnswer(inv -> {
						Recording r = recordings.get(
								(String) inv.getArgument(0));
						src[0] = r == null
								? new FakeSource(inputTracks, inputSamples)
								: new FakeSource(r.tracks, r.written);
						return null;
					}).when(m).setDataSource(anyString());
					when(m.getTrackCount()).thenAnswer(inv ->
							src[0].tracks.size());
					when(m.getTrackFormat(anyInt())).thenAnswer(inv ->
							format(src[0].tracks.get(
									(Integer) inv.getArgument(0))));
					doAnswer(inv -> src[0].selected.add(
							(Integer) inv.getArgument(0)))
							.when(m).selectTrack(anyInt());
					doAnswer(inv -> src[0].selected.remove(
							(Integer) inv.getArgument(0)))
							.when(m).unselectTrack(anyInt());
					when(m.getSampleTrackIndex()).thenAnswer(inv -> {
						int i = src[0].current();
						return i < 0 ? -1 : src[0].samples.get(i).track;
					});
					when(m.getSampleSize()).thenAnswer(inv -> src[0].size());
					when(m.readSampleData(any(ByteBuffer.class), anyInt()))
							.thenAnswer(inv -> src[0].read(inv.getArgument(0)));
					when(m.getSampleTime()).thenAnswer(inv -> {
						int i = src[0].current();
						return i < 0 ? -1L : src[0].samples.get(i).time;
					});
					when(m.advance()).thenAnswer(inv -> {
						src[0].advance();
						return src[0].current() >= 0;
					});
				});
				MockedConstruction<MediaMuxer> ignoredToo =
						mockConstruction(MediaMuxer.class, (m, c) -> {
							String path = (String) c.arguments().get(0);
							int format = (Integer) c.arguments().get(1);
							Recording r = new Recording(format);
							recordings.put(path, r);
							when(m.addTrack(any())).thenAnswer(inv -> {
								String mime = ((MediaFormat) inv.getArgument(0))
										.getString(MediaFormat.KEY_MIME);
								boolean webm = format == MediaMuxer
										.OutputFormat.MUXER_OUTPUT_WEBM;
								if (webm ? !WEBM_CODECS.contains(mime)
										: mp4Refuses.contains(mime)) {
									throw new IllegalStateException(
											"unsupported " + mime);
								}
								r.tracks.add(mime);
								return r.tracks.size() - 1;
							});
							doAnswer(inv -> {
								ByteBuffer b = inv.getArgument(1);
								MediaCodec.BufferInfo info =
										inv.getArgument(2);
								byte[] got = new byte[info.size];
								ByteBuffer view = b.duplicate();
								view.position(info.offset);
								view.get(got);
								r.written.add(new Sample(
										(Integer) inv.getArgument(0), got,
										info.presentationTimeUs));
								return null;
							}).when(m).writeSampleData(anyInt(), any(),
									any());
							doAnswer(inv -> {
								Files.write(Paths.get(path), MINIMAL_MP4);
								return null;
							}).when(m).stop();
						})) {
			return new SharedMediaSanitizer(ctx).sanitize(uri, declared, head,
					MAX);
		}
	}

	private Recording sentRecording(int format) {
		Recording found = null;
		for (Recording r : recordings.values()) {
			if (r.format == format && !r.written.isEmpty()) found = r;
		}
		assertTrue("nothing was written in that container", found != null);
		return found;
	}

	private void assertEveryPacketKept(Recording r) {
		for (int t = 0; t < inputTracks.size(); t++) {
			int out = r.tracks.indexOf(inputTracks.get(t));
			assertTrue(inputTracks.get(t) + " was dropped", out >= 0);
			List<byte[]> want = new ArrayList<>();
			for (Sample s : inputSamples) if (s.track == t) want.add(s.data);
			List<byte[]> got = new ArrayList<>();
			for (Sample s : r.written) if (s.track == out) got.add(s.data);
			assertEquals(inputTracks.get(t) + " packets", want.size(),
					got.size());
			for (int i = 0; i < want.size(); i++) {
				assertArrayEquals(inputTracks.get(t) + " packet " + i,
						want.get(i), got.get(i));
			}
		}
	}

	@Test
	public void webmWithVp8AndVorbisIsSentAsWebm() throws Exception {
		media(VP8, VORBIS);
		SharedMediaSanitizer.Cleaned c = share("video/webm");
		assertEquals("video/webm", c.getMimeType());
		assertEveryPacketKept(sentRecording(
				MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM));
	}

	@Test
	public void vorbisIsKeptWhetherOrNotTheExtractorAppendsPageCounts()
			throws Exception {
		appendsPageSamples = false;
		media(VORBIS, VP8);
		SharedMediaSanitizer.Cleaned c =
				share("application/octet-stream");
		assertEquals("video/webm", c.getMimeType());
		assertEveryPacketKept(sentRecording(
				MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM));
	}

	@Test
	public void soundAloneInVorbisIsSentAsWebmAudio() throws Exception {
		media(VORBIS);
		SharedMediaSanitizer.Cleaned c = share("audio/webm");
		assertEquals("audio/webm", c.getMimeType());
		assertEveryPacketKept(sentRecording(
				MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM));
	}

	@Test
	public void vp9AndOpusGoIntoWebmWhenThisDeviceCannotPutThemInMp4()
			throws Exception {
		mp4Refuses.add(OPUS);
		media(VP9, OPUS);
		SharedMediaSanitizer.Cleaned c = share("video/webm");
		assertEquals("video/webm", c.getMimeType());
		assertEveryPacketKept(sentRecording(
				MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM));
	}

	@Test
	public void mediaMp4CanCarryStillGoesIntoMp4() throws Exception {
		media(AVC, AAC);
		SharedMediaSanitizer.Cleaned c = share("video/x-matroska");
		assertEquals("video/mp4", c.getMimeType());
		assertEveryPacketKept(sentRecording(
				MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4));
		for (Recording r : recordings.values()) {
			assertEquals("a WebM file was written for MP4 media",
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, r.format);
		}
	}

	@Test
	public void tracksNoContainerCanCarryAreRefused() throws Exception {
		media(AVC, VORBIS);
		try {
			SharedMediaSanitizer.Cleaned c = share("video/x-matroska");
			fail("H.264 with Vorbis was sent as " + c.getMimeType());
		} catch (IOException expected) {
		}
		String[] left = ctx.getCacheDir().list((d, n) ->
				n.startsWith("vid_clean_"));
		assertFalse("a partial output was left in the cache: "
				+ Arrays.toString(left), left != null && left.length > 0);
	}
}
