package com.professor.zerion.android.attachment;

import com.professor.zerion.android.grouptr.voice.GroupTrVoiceRecorder;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.OggTestFiles.EMPTY_OPUS_TAGS;
import static com.professor.zerion.android.attachment.OggTestFiles.ascii;
import static com.professor.zerion.android.attachment.OggTestFiles.audioPackets;
import static com.professor.zerion.android.attachment.OggTestFiles.concat;
import static com.professor.zerion.android.attachment.OggTestFiles.opus;
import static com.professor.zerion.android.attachment.OggTestFiles.opusHead;
import static com.professor.zerion.android.attachment.OggTestFiles.opusTags;
import static com.professor.zerion.android.attachment.OggTestFiles.packets;
import static com.professor.zerion.android.attachment.OggTestFiles.readPages;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupVoiceCleanTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long RECORDER_SERIAL = 0x6B8B4567L;
	private static final byte[] MARKER = ascii("ZtPlantedEncoder");

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static byte[] recording(List<byte[]> audio) {
		return opus(RECORDER_SERIAL, opusTags("ENCODER="
				+ new String(MARKER, java.nio.charset.StandardCharsets
				.US_ASCII)), audio, 255, 40);
	}

	private static Set<Long> serials(List<OggTestFiles.Page> pages) {
		Set<Long> serials = new HashSet<>();
		for (OggTestFiles.Page p : pages) {
			assertTrue("a page checksum is wrong", p.checksumValid);
			serials.add(p.serial);
		}
		return serials;
	}

	@Test
	public void aGroupVoiceNoteLosesItsTagsAndItsRecorderSerial()
			throws Exception {
		List<byte[]> audio = audioPackets(30, 5);
		byte[] recorded = recording(audio);
		assertTrue(contains(recorded, MARKER));
		assertTrue(contains(recorded, ascii("libopus")));

		byte[] sent = GroupTrVoiceRecorder.cleanRecording(recorded,
				new Random(11));

		assertFalse("the encoder tag was sent", contains(sent, MARKER));
		assertFalse("the vendor string was sent",
				contains(sent, ascii("libopus")));
		List<OggTestFiles.Page> pages = readPages(sent);
		Set<Long> serials = serials(pages);
		assertEquals(1, serials.size());
		long serial = serials.iterator().next();
		assertNotEquals("the recorder's serial was kept", RECORDER_SERIAL,
				serial);
		List<byte[]> expected = new ArrayList<>();
		expected.add(opusHead());
		expected.add(EMPTY_OPUS_TAGS);
		expected.addAll(audio);
		List<byte[]> got = packets(pages, serial);
		assertEquals(expected.size(), got.size());
		for (int i = 0; i < expected.size(); i++) {
			assertArrayEquals("packet " + i, expected.get(i), got.get(i));
		}
		List<OggTestFiles.Page> before = readPages(
				OggCommentStripper.withoutComments(recorded));
		assertEquals(before.size(), pages.size());
		for (int i = 0; i < pages.size(); i++) {
			assertEquals(before.get(i).granule, pages.get(i).granule);
			assertEquals(before.get(i).seq, pages.get(i).seq);
			assertEquals(before.get(i).flags, pages.get(i).flags);
		}
	}

	@Test
	public void everyNoteGetsAFreshSerial() throws Exception {
		byte[] recorded = recording(audioPackets(6, 2));
		long first = readPages(GroupTrVoiceRecorder.cleanRecording(recorded,
				new Random(1))).get(0).serial;
		long second = readPages(GroupTrVoiceRecorder.cleanRecording(recorded,
				new Random(2))).get(0).serial;
		assertNotEquals(first, second);
	}

	@Test
	public void streamsInOneFileKeepDistinctSerials() throws Exception {
		byte[] chained = concat(opus(1, opusTags(), audioPackets(4, 1), 255,
				40), opus(2, opusTags(), audioPackets(4, 9), 255, 40));
		Random sameEveryTime = new Random() {
			private int calls;

			@Override
			public int nextInt() {
				return calls++ < 3 ? 77 : 78;
			}
		};
		byte[] out = OggCommentStripper.withFreshSerials(chained,
				sameEveryTime);
		List<OggTestFiles.Page> pages = readPages(out);
		Set<Long> serials = serials(pages);
		assertEquals(2, serials.size());
		assertTrue(serials.contains(77L));
		assertTrue(serials.contains(78L));
		assertEquals(chained.length, out.length);
	}

	@Test
	public void aRecordingThatCannotBeCleanedIsNotReturned() {
		byte[] good = recording(audioPackets(6, 3));
		byte[] badChecksum = good.clone();
		badChecksum[good.length - 1] ^= 1;
		byte[][] broken = {new byte[0], ascii("RIFF0000WAVEfmt "),
				badChecksum, java.util.Arrays.copyOf(good, good.length - 9)};
		for (int i = 0; i < broken.length; i++) {
			try {
				GroupTrVoiceRecorder.cleanRecording(broken[i], new Random(3));
				fail("recording " + i + " was cleaned");
			} catch (IOException expected) {
			}
		}
		try {
			OggCommentStripper.withFreshSerials(badChecksum, new Random(3));
			fail("a page with a wrong checksum was renumbered");
		} catch (IOException expected) {
		}
	}
}
