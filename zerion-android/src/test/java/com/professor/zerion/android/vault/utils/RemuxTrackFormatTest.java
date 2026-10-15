package com.professor.zerion.android.vault.utils;

import android.media.MediaFormat;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RemuxTrackFormatTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final Set<String> KEPT = new HashSet<>(Arrays.asList(
			MetadataStripper.MUXED_KEYS));

	private static void assertOnlyKeptKeys(MediaFormat out) {
		for (String key : out.getKeys()) {
			assertTrue(key + " was passed to the muxer", KEPT.contains(key));
		}
	}

	@Test
	public void aVideoTrackKeepsOnlyWhatTheMuxerNeeds() {
		ByteBuffer sps = ByteBuffer.wrap(new byte[] {0, 0, 0, 1, 0x67});
		ByteBuffer pps = ByteBuffer.wrap(new byte[] {0, 0, 0, 1, 0x68});
		MediaFormat in = MediaFormat.createVideoFormat("video/avc", 1920,
				1080);
		in.setByteBuffer("csd-0", sps);
		in.setByteBuffer("csd-1", pps);
		in.setInteger(MediaFormat.KEY_ROTATION, 90);
		in.setLong(MediaFormat.KEY_DURATION, 4_000_000L);
		in.setInteger(MediaFormat.KEY_PROFILE, 8);
		in.setInteger(MediaFormat.KEY_LEVEL, 512);
		in.setString(MediaFormat.KEY_LANGUAGE, "nld");
		in.setInteger(MediaFormat.KEY_BIT_RATE, 17_000_000);
		in.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
		in.setInteger(MediaFormat.KEY_COLOR_STANDARD,
				MediaFormat.COLOR_STANDARD_BT2020);
		in.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO,
				ByteBuffer.wrap(new byte[25]));
		in.setInteger(MediaFormat.KEY_TRACK_ID, 7);
		in.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
		in.setString("ZtPlantedKey", "ZtPlantedValue");

		MediaFormat out = MetadataStripper.muxedFormat(in);

		assertOnlyKeptKeys(out);
		assertEquals("video/avc", out.getString(MediaFormat.KEY_MIME));
		assertEquals(1920, out.getInteger(MediaFormat.KEY_WIDTH));
		assertEquals(1080, out.getInteger(MediaFormat.KEY_HEIGHT));
		assertEquals(90, out.getInteger(MediaFormat.KEY_ROTATION));
		assertEquals(4_000_000L, out.getLong(MediaFormat.KEY_DURATION));
		assertEquals(8, out.getInteger(MediaFormat.KEY_PROFILE));
		assertEquals(512, out.getInteger(MediaFormat.KEY_LEVEL));
		assertEquals(sps, out.getByteBuffer("csd-0"));
		assertEquals(pps, out.getByteBuffer("csd-1"));
		for (String dropped : new String[] {MediaFormat.KEY_LANGUAGE,
				MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_FRAME_RATE,
				MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_HDR_STATIC_INFO,
				MediaFormat.KEY_TRACK_ID, MediaFormat.KEY_MAX_INPUT_SIZE,
				"ZtPlantedKey"}) {
			assertFalse(dropped + " was passed to the muxer",
					out.containsKey(dropped));
		}
	}

	@Test
	public void hevcSetupLosesItsEncoderUserData() {
		byte[] vps = SeiUserDataFilterTest.concat(
				SeiUserDataFilterTest.bytes(0, 0, 0, 1, 0x40, 0x01, 0x0C, 0x01));
		byte[] userData = SeiUserDataFilterTest.concat(
				SeiUserDataFilterTest.bytes(0, 0, 0, 1),
				SeiUserDataFilterTest.sei(SeiUserDataFilterTest.bytes(0x4E,
						0x01), SeiUserDataFilterTest.x264UserData()));
		MediaFormat in = MediaFormat.createVideoFormat("video/hevc", 640, 480);
		in.setByteBuffer("csd-0", ByteBuffer.wrap(
				SeiUserDataFilterTest.concat(vps, userData)));
		MediaFormat out = MetadataStripper.muxedFormat(in);
		ByteBuffer setup = out.getByteBuffer("csd-0");
		byte[] kept = new byte[setup.remaining()];
		setup.duplicate().get(kept);
		assertTrue(Arrays.equals(vps, kept));
	}

	@Test
	public void anAudioTrackKeepsItsRateChannelsAndSetup() {
		MediaFormat in = MediaFormat.createAudioFormat("audio/opus", 48000, 2);
		ByteBuffer head = ByteBuffer.wrap(new byte[19]);
		in.setByteBuffer("csd-0", head);
		in.setByteBuffer("csd-1", ByteBuffer.wrap(new byte[8]));
		in.setByteBuffer("csd-2", ByteBuffer.wrap(new byte[8]));
		in.setString(MediaFormat.KEY_LANGUAGE, "eng");
		in.setInteger("encoder-delay", 312);

		MediaFormat out = MetadataStripper.muxedFormat(in);

		assertOnlyKeptKeys(out);
		assertEquals(48000, out.getInteger(MediaFormat.KEY_SAMPLE_RATE));
		assertEquals(2, out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
		assertEquals(head, out.getByteBuffer("csd-0"));
		assertTrue(out.containsKey("csd-1"));
		assertTrue(out.containsKey("csd-2"));
		assertFalse(out.containsKey(MediaFormat.KEY_LANGUAGE));
		assertFalse(out.containsKey("encoder-delay"));
	}
}
