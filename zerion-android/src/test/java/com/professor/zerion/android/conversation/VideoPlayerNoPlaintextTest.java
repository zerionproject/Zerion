package com.professor.zerion.android.conversation;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoPlayerNoPlaintextTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String MARKER = "decrypted video bytes 0123456789";

	private final Application app = RuntimeEnvironment.getApplication();

	private static void filesHolding(File f, byte[] needle, List<String> out)
			throws Exception {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children == null) return;
			for (File c : children) filesHolding(c, needle, out);
		} else if (f.isFile() && f.length() < 64 * 1024 * 1024) {
			String content = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.ISO_8859_1);
			if (content.contains(new String(needle,
					StandardCharsets.ISO_8859_1))) {
				out.add(f.getName());
			}
		}
	}

	private List<String> storageHolding(byte[] needle) throws Exception {
		List<String> out = new ArrayList<>();
		filesHolding(new File(app.getApplicationInfo().dataDir), needle, out);
		filesHolding(app.getCacheDir(), needle, out);
		return out;
	}

	@Test
	public void aPlayedVideoLeavesNoCopyInStorage() throws Exception {
		byte[] video = new byte[200_000];
		byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(marker, 0, video, 1000, marker.length);
		File stale = new File(app.getCacheDir(), "zerion_video_1.mp4");
		Files.write(stale.toPath(), video);

		AttachmentHeader header = new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"video/mp4");
		Intent i = new Intent(app, VideoPlayerActivity.class);
		i.putExtra(VideoPlayerActivity.ATTACHMENT,
				AttachmentItemsForTests.available(header, "mp4"));
		i.putExtra(VideoPlayerActivity.ITEM_ID, getRandomId());
		ActivityController<VideoPlayerActivity> c =
				Robolectric.buildActivity(VideoPlayerActivity.class, i)
						.create();
		VideoPlayerActivity a = c.get();
		AttachmentReader reader = mock(AttachmentReader.class);
		when(reader.getAttachment(any(AttachmentHeader.class))).thenAnswer(
				inv -> new Attachment(header,
						new ByteArrayInputStream(video.clone())));
		a.attachmentReader = reader;
		a.dbExecutor = Runnable::run;

		c.start().resume();
		shadowOf(Looper.getMainLooper()).idle();
		List<String> whilePlaying = storageHolding(marker);
		c.pause().stop().destroy();
		List<String> afterClosing = storageHolding(marker);

		assertEquals("while playing [], after closing []",
				"while playing " + whilePlaying + ", after closing "
						+ afterClosing);
	}
}
