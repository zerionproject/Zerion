package com.professor.zerion.android.channel;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.AttachmentBlob;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.event.EventBus;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelAttachmentHandoffTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final byte[] PDF = ("%PDF-1.7\n1 0 obj << /Type /Catalog"
			+ " /OpenAction << /S /URI /URI (http://198.51.100.7/t.png) >>"
			+ " >> endobj\n").getBytes(StandardCharsets.US_ASCII);

	public static class HandoffFeed extends ChannelFeedActivity {

		public static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = mock(ChannelManager.class);
			eventBus = mock(EventBus.class);
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = io::add;
		}
	}

	private Context ctx;
	private MockedStatic<FileProvider> provider;

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		HandoffFeed.io.clear();
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("pdf", "application/pdf");
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
		HandoffFeed.io.clear();
	}

	private static void runBackgroundWork() {
		for (int round = 0; round < 6; round++) {
			shadowOf(Looper.getMainLooper()).idle();
			if (HandoffFeed.io.isEmpty()) break;
			List<Runnable> pending = new ArrayList<>(HandoffFeed.io);
			HandoffFeed.io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	private ActivityController<HandoffFeed> openFeed() {
		ActivityController<HandoffFeed> c = Robolectric.buildActivity(
				HandoffFeed.class, new Intent(ctx, HandoffFeed.class)).setup();
		shadowOf(Looper.getMainLooper()).idle();
		HandoffFeed.io.clear();
		while (shadowOf(c.get()).getNextStartedActivity() != null) {
			shadowOf(Looper.getMainLooper()).idle();
		}
		return c;
	}

	private static byte[] hash(int seed) {
		byte[] h = new byte[32];
		for (int i = 0; i < h.length; i++) h[i] = (byte) (seed * 17 + i);
		return h;
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	private static byte[] mp4(int seed) {
		byte[] d = new byte[400];
		byte[] head = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
				0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '1'};
		System.arraycopy(head, 0, d, 0, head.length);
		byte[] mdat = {0, 0, 1, 0x78, 'm', 'd', 'a', 't'};
		System.arraycopy(mdat, 0, d, 24, mdat.length);
		for (int i = 32; i < d.length; i++) d[i] = (byte) (i * 3 + seed);
		return d;
	}

	private void present(HandoffFeed feed, byte[] blobHash, byte[] content,
			String mime) throws Exception {
		ChannelPost.ChannelAttachment att = new ChannelPost.ChannelAttachment(
				blobHash, content.length, mime, new byte[32], null);
		Method m = ChannelFeedActivity.class.getDeclaredMethod(
				"presentAttachment", ChannelPost.ChannelAttachment.class,
				AttachmentBlob.class, ImageView.class);
		m.setAccessible(true);
		m.invoke(feed, att, new AttachmentBlob(content, mime),
				new ImageView(feed));
		runBackgroundWork();
	}

	private static Intent nextView(HandoffFeed feed) {
		Intent i;
		while ((i = shadowOf(feed).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) return i;
		}
		return null;
	}

	private List<File> copiesOf(byte[] content) throws Exception {
		List<File> found = new ArrayList<>();
		for (File root : new File[] {ctx.getCacheDir(), ctx.getFilesDir(),
				ctx.getNoBackupFilesDir()}) {
			collect(root, content, found);
		}
		return found;
	}

	private static void collect(File dir, byte[] content, List<File> found)
			throws Exception {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) {
				collect(f, content, found);
			} else if (f.length() == content.length && holds(f, content)) {
				found.add(f);
			}
		}
	}

	private static boolean holds(File f, byte[] content) throws Exception {
		try {
			return Arrays.equals(content, Files.readAllBytes(f.toPath()));
		} catch (NoSuchFileException wiped) {
			return false;
		}
	}

	private File stagedFile(Intent view) {
		List<String> seg = view.getData().getPathSegments();
		return new File(new File(ctx.getCacheDir(), seg.get(0)), seg.get(1));
	}

	@Test
	public void aDocumentIsWrittenAndOpenedOnlyAfterTheReaderConfirms()
			throws Exception {
		HandoffFeed feed = openFeed().get();
		present(feed, hash(1), PDF, "application/pdf");
		assertNull("a document was opened in another app without a warning",
				nextView(feed));
		assertTrue("the document was written before the reader confirmed: "
				+ copiesOf(PDF), copiesOf(PDF).isEmpty());
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no warning was shown", dialog);
		assertTrue(dialog.isShowing());
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runBackgroundWork();
		Intent view = nextView(feed);
		assertNotNull("the confirmed document was not opened", view);
		assertEquals("application/pdf", view.getType());
		assertArrayEquals(PDF, Files.readAllBytes(stagedFile(view).toPath()));
	}

	@Test
	public void cancellingTheWarningWritesAndOpensNothing() throws Exception {
		HandoffFeed feed = openFeed().get();
		present(feed, hash(2), PDF, "application/octet-stream");
		assertNull(nextView(feed));
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull("no warning was shown", dialog);
		((androidx.appcompat.app.AlertDialog) dialog)
				.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		runBackgroundWork();
		assertNull(nextView(feed));
		assertTrue(copiesOf(PDF).isEmpty());
	}

	@Test
	public void theCopyNamesNeitherTheAttachmentNorItsHash() throws Exception {
		HandoffFeed feed = openFeed().get();
		byte[] blobHash = hash(3);
		byte[] video = mp4(3);
		present(feed, blobHash, video, "video/mp4");
		Intent view = nextView(feed);
		assertNotNull("a checked video still opens without a warning", view);
		assertEquals("video/mp4", view.getType());
		String name = view.getData().getLastPathSegment();
		String hashHex = hex(blobHash);
		for (int len = 6; len <= 16; len += 2) {
			assertFalse("the copy is named after the attachment hash: "
					+ name, name.contains(hashHex.substring(0, len)));
		}
		assertTrue(name, name.endsWith(".mp4"));
		assertNotEquals(0, view.getFlags()
				& Intent.FLAG_GRANT_READ_URI_PERMISSION);
		assertEquals("the other app could write the copy", 0,
				view.getFlags() & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
	}

	@Test
	public void onlyTheNewestCopyIsKept() throws Exception {
		HandoffFeed feed = openFeed().get();
		byte[] first = mp4(4);
		byte[] second = mp4(5);
		present(feed, hash(4), first, "video/mp4");
		Intent one = nextView(feed);
		assertNotNull(one);
		present(feed, hash(5), second, "video/mp4");
		Intent two = nextView(feed);
		assertNotNull(two);
		assertEquals(1, copiesOf(second).size());
		long deadline = System.currentTimeMillis() + 15_000;
		while (!copiesOf(first).isEmpty()
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertTrue("an earlier copy is still on disk: " + copiesOf(first),
				copiesOf(first).isEmpty());
	}

	@Test
	public void theCopyStaysWhileTheOtherAppIsInFrontAndGoesAfter()
			throws Exception {
		ActivityController<HandoffFeed> c = openFeed();
		byte[] video = mp4(6);
		present(c.get(), hash(6), video, "video/mp4");
		Intent view = nextView(c.get());
		assertNotNull(view);
		File staged = stagedFile(view);
		c.pause().stop();
		runBackgroundWork();
		assertArrayEquals("the other app would read a removed copy", video,
				Files.readAllBytes(staged.toPath()));
		c.restart().start().resume();
		runBackgroundWork();
		long deadline = System.currentTimeMillis() + 15_000;
		while (staged.exists() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertFalse("the copy outlives the hand-off", staged.exists());
	}
}
