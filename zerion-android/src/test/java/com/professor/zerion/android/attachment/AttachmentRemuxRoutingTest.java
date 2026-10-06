package com.professor.zerion.android.attachment;

import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AttachmentRemuxRoutingTest {

	@Test
	public void isoMediaAudioTypesAreRemuxedLikeVideo() {
		for (String t : new String[] {"audio/mp4", "audio/3gpp",
				"audio/3gp", "video/mp4", "video/webm", "video/x-matroska"}) {
			assertTrue(t, AttachmentCreationTask.remuxes(t));
		}
	}

	@Test
	public void otherAudioIsCleanedInPlace() {
		for (String t : new String[] {"audio/mpeg", "audio/ogg",
				"audio/flac", "audio/x-wav", "audio/aac", "audio/amr",
				"image/jpeg", "application/pdf"}) {
			assertFalse(t, AttachmentCreationTask.remuxes(t));
		}
	}

	@Test
	public void remuxedAudioIsSentAsMp4Audio() throws Exception {
		assertEquals("audio/mp4", AttachmentCreationTask.sentType(
				remuxed(new File("never-read.mp4"), false, false)));
	}

	@Test
	public void remuxedVideoKeepsTheContainerType() throws Exception {
		MetadataStripper.Remuxed video =
				remuxed(new File("never-read.mp4"), false, true);
		assertEquals(video.getMimeType(),
				AttachmentCreationTask.sentType(video));
	}

	@Test
	public void webmAudioIsRefusedAndItsFileRemoved() throws Exception {
		File f = File.createTempFile("remux-routing", ".webm");
		assertTrue(f.exists());
		try {
			AttachmentCreationTask.sentType(remuxed(f, true, false));
			throw new AssertionError("WebM audio was labelled for sending");
		} catch (MediaRefusedException expected) {
		}
		assertFalse("the refused remux output is left behind", f.exists());
	}

	private static MetadataStripper.Remuxed remuxed(File f, boolean webm,
			boolean video) throws Exception {
		Constructor<MetadataStripper.Remuxed> c = MetadataStripper.Remuxed.class
				.getDeclaredConstructor(File.class, boolean.class,
						boolean.class);
		c.setAccessible(true);
		return c.newInstance(f, webm, video);
	}
}
