package com.professor.zerion.android.util;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
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

	private Intent openAndConfirm(ChannelAttachmentHandoffTest.HandoffFeed screen,
			byte[] content, String type) {
		ExternalHandoff.open(screen, Runnable::run, content, type,
				() -> failures.add(type));
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(d);
		d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
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

	private static byte[] zip(String... names) throws Exception {
		java.io.ByteArrayOutputStream out =
				new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream z =
				new java.util.zip.ZipOutputStream(out)) {
			for (String name : names) {
				z.putNextEntry(new java.util.zip.ZipEntry(name));
				z.write(("content of " + name).getBytes("US-ASCII"));
				z.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] jpeg() {
		byte[] j = new byte[64];
		byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0,
				0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
		System.arraycopy(head, 0, j, 0, head.length);
		return j;
	}

	private static byte[] dex() {
		byte[] d = new byte[128];
		byte[] head = {'d', 'e', 'x', '\n', '0', '3', '5', 0};
		System.arraycopy(head, 0, d, 0, head.length);
		return d;
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
	public void theOtherAppOnlyEverGetsTheCleanedCopy() throws Exception {
		byte[] clean = video();
		clean[clean.length - 1] = 9;
		byte[] expected = clean.clone();
		List<String> asked = new ArrayList<>();
		ExternalHandoff.cleaner = (c, content, type) -> {
			asked.add(type);
			return new ExternalHandoff.Prepared(clean, "video/mp4");
		};
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		Intent view = open(s, video(), "video/quicktime");
		assertNotNull(view);
		assertEquals("video/mp4", view.getType());
		File copy = ExternalHandoff.current();
		assertNotNull(copy);
		assertArrayEquals(expected, Files.readAllBytes(copy.toPath()));
		assertArrayEquals("the cleaned copy stays in memory after staging",
				new byte[clean.length], clean);
		assertEquals(Arrays.asList("video/quicktime"), asked);
	}

	@Test
	public void nothingLeavesWhenTheFileCannotBeCleaned() throws Exception {
		ExternalHandoff.cleaner = (c, content, type) -> {
			throw new java.io.IOException("cannot clean");
		};
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		assertNull(open(s, video(), "video/mp4"));
		assertEquals(Arrays.asList("video/mp4"), failures);
		assertNull(ExternalHandoff.current());
		String[] left = dir().list();
		assertTrue(left == null || left.length == 0);
	}

	@Test
	public void filesThatCanBeCleanedAreCleanedBeforeTheyLeave()
			throws Exception {
		byte[] pdf = "%PDF-1.7 author".getBytes("US-ASCII");
		byte[] drawn = "%PDF-1.4\nclean\n%%EOF\n".getBytes("US-ASCII");
		try (org.mockito.MockedConstruction<com.professor.zerion.android
				.attachment.PdfCleaner> cleaners =
				org.mockito.Mockito.mockConstruction(com.professor.zerion
						.android.attachment.PdfCleaner.class, (m, c) ->
						org.mockito.Mockito.when(m.clean(any(),
								org.mockito.ArgumentMatchers.anyLong()))
								.thenReturn(drawn))) {
			ExternalHandoff.Prepared ready = ExternalHandoff.CLEAN_FIRST
					.prepare(ctx, pdf, "application/pdf");
			assertArrayEquals(drawn, ready.content);
			assertEquals("application/pdf", ready.type);
			assertEquals(1, cleaners.constructed().size());
		}
		byte[] zip = {'P', 'K', 3, 4, 1, 2};
		ExternalHandoff.Prepared kept = ExternalHandoff.CLEAN_FIRST
				.prepare(ctx, zip, "application/zip");
		assertArrayEquals(zip, kept.content);
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

	@Test
	public void anInstallableNeverLeavesWhateverTypeWasDeclared()
			throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		byte[] apk = zip("META-INF/MANIFEST.MF", "AndroidManifest.xml",
				"classes.dex");
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		assertNull(open(s, apk, "video/mp4"));
		assertNull(open(s, dex(), "audio/mpeg"));
		assertNull(open(s, apk, "image/jpeg"));
		assertNull(openAndConfirm(s, apk, "application/zip"));
		assertNull(openAndConfirm(s, dex(), ExternalViewerTypes.OPAQUE));
		assertEquals(Arrays.asList("video/mp4", "audio/mpeg", "image/jpeg",
				"application/zip", ExternalViewerTypes.OPAQUE), failures);
		assertNull(ExternalHandoff.current());
		String[] left = dir().list();
		assertTrue("a copy was left: " + Arrays.toString(left),
				left == null || left.length == 0);
	}

	@Test
	public void checkedPicturesAndDocumentsStillOpen() throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		Intent picture = open(s, jpeg(), "image/jpeg");
		assertNotNull(picture);
		assertEquals("image/jpeg", picture.getType());
		Intent document = openAndConfirm(s,
				"%PDF-1.4\nclean\n%%EOF\n".getBytes("US-ASCII"),
				"application/pdf");
		assertNotNull(document);
		assertEquals("application/pdf", document.getType());
		assertTrue(failures.isEmpty());
	}

	@Test
	public void theTypeHandedOverIsTheOneTheContentChecksAs()
			throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\">"
				+ "<script>1</script></svg>").getBytes("US-ASCII");
		Intent view = open(s, svg, "image/svg+xml");
		assertNotNull(view);
		assertEquals(ExternalViewerTypes.TEXT, view.getType());
	}

	@Test
	public void aCopyLeavesOnlyUnderTheTypeItWasCheckedAs() throws Exception {
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		File f = ExternalHandoff.stage(ctx, jpeg(), "image/jpeg");
		assertFalse(ExternalHandoff.launch(s, f,
				"application/vnd.android.package-archive"));
		assertNull(shadowOf(s).getNextStartedActivity());
		assertTrue(ExternalHandoff.launch(s, f, "image/jpeg"));
		Intent view = shadowOf(s).getNextStartedActivity();
		assertNotNull(view);
		assertEquals("image/jpeg", view.getType());
	}

	private void assertNotStaged(byte[] content, String type) {
		assertThrows(type, IOException.class,
				() -> ExternalHandoff.stage(ctx, content, type));
	}

	@Test
	public void nothingIsStagedUnlessTheContentChecksAsTheGivenType()
			throws Exception {
		byte[] apk = zip("META-INF/MANIFEST.MF", "AndroidManifest.xml",
				"classes.dex");
		byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\">"
				+ "<script>1</script></svg>").getBytes("US-ASCII");
		assertNotStaged(apk, "application/zip");
		assertNotStaged(apk, "image/jpeg");
		assertNotStaged(dex(), ExternalViewerTypes.OPAQUE);
		assertNotStaged(dex(), "audio/mpeg");
		assertNotStaged(jpeg(), "application/vnd.android.package-archive");
		assertNotStaged(svg, "image/svg+xml");
		assertNotStaged(video(), "image/jpeg");
		assertNotStaged(jpeg(), "IMAGE/JPEG");
		assertNull(ExternalHandoff.current());
		String[] left = dir().list();
		assertTrue("a copy was left: " + Arrays.toString(left),
				left == null || left.length == 0);

		File staged = ExternalHandoff.stage(ctx, jpeg(), "image/jpeg");
		assertEquals(staged, ExternalHandoff.current());
		assertArrayEquals(jpeg(), Files.readAllBytes(staged.toPath()));
		File text = ExternalHandoff.stage(ctx, svg, ExternalViewerTypes.TEXT);
		assertEquals(text, ExternalHandoff.current());
	}

	@Test
	public void aStagedCopyLeavesOnlyAfterItsContentWasChecked()
			throws Exception {
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		byte[] apk = zip("AndroidManifest.xml", "classes.dex");
		assertNotStaged(apk, "application/zip");
		File stray = new File(dir(), "stray.zip");
		assertTrue(dir().isDirectory() || dir().mkdirs());
		Files.write(stray.toPath(), apk);
		assertFalse(ExternalHandoff.launch(s, stray, "application/zip"));
		assertNull(shadowOf(s).getNextStartedActivity());
	}

	@Test
	public void aRefusedCopyAndAMissingAppAreReportedApart() throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		List<String> refused = new ArrayList<>();
		List<String> noApp = new ArrayList<>();

		ExternalHandoff.open(s, Runnable::run, dex(), "audio/mpeg",
				() -> refused.add("program"), () -> noApp.add("program"));
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(Arrays.asList("program"), refused);
		assertTrue(noApp.isEmpty());

		shadowOf((Application) ctx).checkActivities(true);
		ExternalHandoff.openCopy(s, Runnable::run, video(), "video/mp4",
				() -> refused.add("video"), () -> noApp.add("video"));
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(Arrays.asList("program"), refused);
		assertEquals(Arrays.asList("video"), noApp);
		assertNull(ExternalHandoff.current());
	}

	private static final Executor OFF_THE_SCREEN_THREAD = task -> {
		Thread worker = new Thread(task);
		worker.start();
		try {
			worker.join();
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	};

	private static List<Intent> viewsStartedBy(
			ChannelAttachmentHandoffTest.HandoffFeed screen) {
		List<Intent> views = new ArrayList<>();
		Intent i;
		while ((i = shadowOf(screen).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) views.add(i);
		}
		return views;
	}

	@Test
	public void aCopyReplacedBeforeItOpensEndsWithoutAMessage()
			throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		List<String> refused = new ArrayList<>();
		List<String> noApp = new ArrayList<>();

		ExternalHandoff.open(s, OFF_THE_SCREEN_THREAD, video(), "video/mp4",
				() -> refused.add("first"), () -> noApp.add("first"));
		File first = ExternalHandoff.current();
		ExternalHandoff.open(s, OFF_THE_SCREEN_THREAD, jpeg(), "image/jpeg",
				() -> refused.add("second"), () -> noApp.add("second"));
		File second = ExternalHandoff.current();
		assertNotNull(first);
		assertNotNull(second);
		assertNotEquals(first, second);
		shadowOf(Looper.getMainLooper()).idle();

		assertTrue("the replaced copy was reported as a failure: " + noApp,
				noApp.isEmpty());
		assertTrue(refused.isEmpty());
		List<Intent> views = viewsStartedBy(s);
		assertEquals("the copy that replaced it did not open", 1,
				views.size());
		assertEquals("image/jpeg", views.get(0).getType());
		assertEquals(second, ExternalHandoff.current());
		assertTrue(second.exists());
		assertFalse(first.exists());
	}

	@Test
	public void aReplacedCopyStaysSilentWhenItsReplacementFindsNoApp()
			throws Exception {
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		ChannelAttachmentHandoffTest.HandoffFeed s = screen().get();
		shadowOf((Application) ctx).checkActivities(true);
		List<String> refused = new ArrayList<>();
		List<String> noApp = new ArrayList<>();

		ExternalHandoff.open(s, OFF_THE_SCREEN_THREAD, video(), "video/mp4",
				() -> refused.add("first"), () -> noApp.add("first"));
		ExternalHandoff.open(s, OFF_THE_SCREEN_THREAD, jpeg(), "image/jpeg",
				() -> refused.add("second"), () -> noApp.add("second"));
		ExternalHandoff.open(s, OFF_THE_SCREEN_THREAD, dex(), "audio/mpeg",
				() -> refused.add("third"), () -> noApp.add("third"));
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(Arrays.asList("second"), noApp);
		assertEquals(Arrays.asList("third"), refused);
		assertTrue(viewsStartedBy(s).isEmpty());
		assertNull(ExternalHandoff.current());
		String[] left = dir().list();
		assertTrue("a copy was left: " + Arrays.toString(left),
				left == null || left.length == 0);
	}
}
