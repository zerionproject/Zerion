package com.professor.zerion.android.util;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.webkit.MimeTypeMap;

import com.professor.zerion.android.channel.ChannelAttachmentHandoffTest;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ExternalHandoffTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context ctx;
	private MockedStatic<FileProvider> provider;
	private Executor savedWiper;
	private ExternalHandoff.Revoker savedRevoker;
	private final List<Uri> revoked = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		savedWiper = ExternalHandoff.wiper;
		savedRevoker = ExternalHandoff.revoker;
		ExternalHandoff.wiper = Runnable::run;
		ExternalHandoff.revoker = (c, uri) -> revoked.add(uri);
		ExternalHandoff.end(ctx);
		revoked.clear();
		failures.clear();
		ChannelAttachmentHandoffTest.HandoffFeed.io.clear();
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
		provider = mockStatic(FileProvider.class);
		provider.when(() -> FileProvider.getUriForFile(any(), anyString(),
				any(File.class))).thenAnswer(inv -> {
					File f = inv.getArgument(2);
					return Uri.parse("content://" + inv.getArgument(1) + "/"
							+ f.getParentFile().getName() + "/" + f.getName());
				});
	}

	@After
	public void tearDown() {
		provider.close();
		ExternalHandoff.end(ctx);
		ExternalHandoff.wiper = savedWiper;
		ExternalHandoff.revoker = savedRevoker;
	}

	private static byte[] video() {
		byte[] d = new byte[300];
		byte[] head = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
				0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '1'};
		System.arraycopy(head, 0, d, 0, head.length);
		for (int i = 24; i < d.length; i++) d[i] = (byte) (i * 5);
		return d;
	}

	private ActivityController<ChannelAttachmentHandoffTest.HandoffFeed>
			screen() {
		ActivityController<ChannelAttachmentHandoffTest.HandoffFeed> c =
				Robolectric.buildActivity(
						ChannelAttachmentHandoffTest.HandoffFeed.class,
						new Intent(ctx,
								ChannelAttachmentHandoffTest.HandoffFeed.class))
						.setup();
		shadowOf(Looper.getMainLooper()).idle();
		ChannelAttachmentHandoffTest.HandoffFeed.io.clear();
		while (shadowOf(c.get()).getNextStartedActivity() != null) {
			shadowOf(Looper.getMainLooper()).idle();
		}
		return c;
	}

	private Intent open(ChannelAttachmentHandoffTest.HandoffFeed screen,
			byte[] content, String type) {
		ExternalHandoff.open(screen, Runnable::run, content, type,
				() -> failures.add(type));
		shadowOf(Looper.getMainLooper()).idle();
		Intent i;
		while ((i = shadowOf(screen).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) return i;
		}
		return null;
	}

	private File dir() {
		return new File(ctx.getCacheDir(), ExternalHandoff.DIR);
	}

	@Test
	public void onlyContentThatIsNotCheckedMediaOrPlainTextAsksFirst() {
		for (String type : new String[] {"application/pdf",
				"application/vnd.openxmlformats-officedocument"
						+ ".wordprocessingml.document",
				"application/msword", "application/vnd.oasis.opendocument.text",
				"application/vnd.google-earth.kmz", "application/rtf",
				"application/zip", ExternalViewerTypes.OPAQUE}) {
			assertTrue(type, ExternalHandoff.asksFirst(type));
		}
		for (String type : new String[] {ExternalViewerTypes.TEXT,
				"image/jpeg", "video/mp4", "audio/mpeg", "audio/ogg"}) {
			assertFalse(type, ExternalHandoff.asksFirst(type));
		}
	}

	@Test
	public void theOtherAppReadsOneCopyUntilAnyZerionScreenIsBack()
			throws Exception {
		ActivityController<ChannelAttachmentHandoffTest.HandoffFeed> c =
				screen();
		Intent view = open(c.get(), video(), "video/mp4");
		assertNotNull(view);
		File copy = ExternalHandoff.current();
		assertNotNull(copy);
		assertEquals(dir(), copy.getParentFile());
		assertEquals(Arrays.asList(copy.getName()),
				Arrays.asList(dir().list()));
		assertTrue(copy.getName().matches("[0-9a-f]{32}\\.mp4"));
		assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, view.getFlags()
				& (Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
		c.pause().stop();
		assertArrayEquals(video(), Files.readAllBytes(copy.toPath()));
		assertTrue(revoked.isEmpty());
		screen();
		assertEquals(Arrays.asList(view.getData()), revoked);
		assertFalse("the copy outlives the hand-off", copy.exists());
		assertNull(ExternalHandoff.current());
	}

	@Test
	public void lockingOrSigningOutEndsTheHandOff() throws Exception {
		ActivityController<ChannelAttachmentHandoffTest.HandoffFeed> c =
				screen();
		Intent view = open(c.get(), video(), "video/mp4");
		assertNotNull(view);
		File copy = ExternalHandoff.current();
		c.pause().stop();
		CacheSweeper.sweep(ctx);
		assertEquals(Arrays.asList(view.getData()), revoked);
		assertFalse(copy.exists());
		assertFalse(dir().exists());
	}

	@Test
	public void aCopyLeftBehindIsRemovedBeforeTheNextIsWritten()
			throws Exception {
		assertTrue(dir().mkdirs() || dir().isDirectory());
		File stray = new File(dir(), "0123456789abcdef0123456789abcdef.pdf");
		Files.write(stray.toPath(), "left by a crash".getBytes("US-ASCII"));
		Intent view = open(screen().get(), video(), "video/mp4");
		assertNotNull(view);
		assertFalse(stray.exists());
		assertEquals(1, dir().list().length);
	}

	@Test
	public void nothingIsLeftWhenNoAppCanOpenTheCopy() throws Exception {
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		shadowOf((Application) ctx).checkActivities(true);
		Intent view = open(s, video(), "video/mp4");
		assertNull(view);
		assertEquals(Arrays.asList("video/mp4"), failures);
		assertNull(ExternalHandoff.current());
		String[] left = dir().list();
		assertTrue("a copy was left: " + Arrays.toString(left),
				left == null || left.length == 0);
		assertEquals(1, revoked.size());
	}
}
