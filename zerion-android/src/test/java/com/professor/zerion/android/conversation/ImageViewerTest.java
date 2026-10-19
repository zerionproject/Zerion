package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.webkit.MimeTypeMap;
import android.widget.TextView;

import com.github.chrisbanes.photoview.PhotoView;
import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.attachment.SaveCleanCopyActivity;
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
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.viewpager2.widget.ViewPager2;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;
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
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ImageViewerTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeViewer extends ImageActivity {

		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			ioExecutor = io::add;
		}
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private Executor savedDecoder;

	@Before
	public void setUp() {
		MediaViewer.wiper = Runnable::run;
		ProbeViewer.io.clear();
		savedDecoder = ImageFragment.decoder;
		ImageFragment.decoder = Runnable::run;
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("png", "image/png");
	}

	@After
	public void tearDown() {
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
		ImageFragment.decoder = savedDecoder;
		uiPrefs().edit().remove(PREF_SCREENSHOT_PROTECTION).commit();
		ProbeViewer.io.clear();
		MediaViewer.clearAll();
	}

	private SharedPreferences uiPrefs() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		return app.getApplicationComponent().uiPreferences();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void layout(Activity a) {
		View root = a.getWindow().getDecorView();
		root.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		root.layout(0, 0, 1080, 1920);
		idle();
	}

	private static boolean secure(Activity a) {
		return (a.getWindow().getAttributes().flags
				& WindowManager.LayoutParams.FLAG_SECURE) != 0;
	}

	private int posts;
	private String post = "";

	private String shown(byte[] content, boolean secure) {
		Activity from = Robolectric.buildActivity(Activity.class).setup()
				.get();
		post = "group:aa/" + (++posts);
		assertTrue(MediaViewer.showImage(from, Runnable::run,
				new MediaViewer.Item(content, "image/png")
						.titled("Alice", 86_400_000L).secure(secure)
						.from("group:aa", post)));
		return shadowOf(from).getNextStartedActivity()
				.getStringExtra(MediaViewer.EXTRA_TOKEN);
	}

	private ActivityController<ProbeViewer> open(String token) {
		Intent i = new Intent(ctx, ProbeViewer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, token);
		ActivityController<ProbeViewer> c =
				Robolectric.buildActivity(ProbeViewer.class, i).setup();
		layout(c.get());
		return c;
	}

	private static PhotoView page(ImageActivity a, int position) {
		for (Fragment f : a.getSupportFragmentManager().getFragments()) {
			if (f instanceof ImageFragment && f.getView() != null
					&& ("f" + position).equals(f.getTag())) {
				return f.getView().findViewById(R.id.photoView);
			}
		}
		throw new AssertionError("page " + position + " is not shown");
	}

	private static void runIo() {
		for (int round = 0; round < 4; round++) {
			idle();
			List<Runnable> pending = new ArrayList<>(ProbeViewer.io);
			ProbeViewer.io.clear();
			for (Runnable r : pending) r.run();
		}
		idle();
	}

	@Test
	public void aGroupPictureIsShownFullScreenFromMemory() {
		ProbeViewer a = open(shown(MediaViewerTest.jpeg(), false)).get();
		PhotoView photo = page(a, 0);
		assertTrue("the picture is not shown",
				photo.getDrawable() instanceof BitmapDrawable);
		assertEquals("Image", String.valueOf(photo.getContentDescription()));
		TextView title = a.findViewById(R.id.contactName);
		assertEquals("Alice", title.getText().toString());
		assertEquals(View.VISIBLE,
				a.findViewById(R.id.dateView).getVisibility());
		assertTrue("pinch zoom is off", photo.isZoomable());
		assertTrue(photo.getMaximumScale() >= 4f);
	}

	@Test
	public void theGroupScreenshotBlockAppliesInTheViewer() {
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, false)
				.commit();
		assertTrue("the group's block is lost in the viewer",
				secure(open(shown(MediaViewerTest.jpeg(), true)).get()));
		assertFalse(secure(open(shown(MediaViewerTest.jpeg(), false)).get()));
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, true)
				.commit();
		assertTrue("the app-wide setting is lost in the viewer",
				secure(open(shown(MediaViewerTest.jpeg(), false)).get()));
	}

	@Test
	public void aViewerWithNothingLeftToShowClosesProtected() {
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, false)
				.commit();
		Intent i = new Intent(ctx, ProbeViewer.class)
				.putExtra(MediaViewer.EXTRA_TOKEN, "00");
		ProbeViewer a = Robolectric.buildActivity(ProbeViewer.class, i)
				.setup().get();
		assertTrue(a.isFinishing());
		assertTrue(secure(a));
	}

	@Test
	public void closingTheViewerWipesItsCopy() {
		String token = shown(MediaViewerTest.jpeg(), false);
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		ActivityController<ProbeViewer> c = open(token);
		c.get().finish();
		c.pause().stop().destroy();
		assertNull(MediaViewer.get(token));
		assertArrayEquals(new byte[held.data.length], held.data);
	}

	@Test
	public void afterALockTheViewerClosesInsteadOfShowingThePictureAgain() {
		ActivityController<ProbeViewer> c =
				open(shown(MediaViewerTest.jpeg(), false));
		PhotoView photo = page(c.get(), 0);
		c.pause().stop();
		MediaViewer.clearAll();
		idle();
		assertTrue("the viewer waits to be shown again before it closes",
				c.get().isFinishing());
		assertNull(photo.getDrawable());
	}

	@Test
	public void deletingThePostClosesTheOpenViewerAtOnce() {
		ProbeViewer a = open(shown(MediaViewerTest.jpeg(), false)).get();
		PhotoView photo = page(a, 0);
		assertNotNull(photo.getDrawable());
		MediaViewer.forgetPosts(java.util.Collections.singletonList(post));
		idle();
		assertTrue("the deleted picture is still on screen", a.isFinishing());
		assertNull(photo.getDrawable());
	}

	@Test
	public void deletingAnotherPostLeavesTheViewerOpen() {
		ProbeViewer a = open(shown(MediaViewerTest.jpeg(), false)).get();
		MediaViewer.forgetPosts(java.util.Collections.singletonList(
				"group:aa/2"));
		idle();
		assertFalse(a.isFinishing());
		assertNotNull(page(a, 0).getDrawable());
	}

	@Test
	public void aChatPictureClosesWhenItsMessageIsDeleted() {
		byte[] message = getRandomId();
		ArrayList<AttachmentItem> items = new ArrayList<>();
		items.add(waiting());
		Intent i = new Intent(ctx, ProbeViewer.class)
				.putParcelableArrayListExtra(ImageActivity.ATTACHMENTS, items)
				.putExtra(ImageActivity.ATTACHMENT_POSITION, 0)
				.putExtra(ImageActivity.NAME, "Bob")
				.putExtra(ImageActivity.DATE, 1000L)
				.putExtra(ImageActivity.ITEM_ID, message);
		ProbeViewer a = Robolectric.buildActivity(ProbeViewer.class, i)
				.setup().get();
		layout(a);
		ImageViewModel model = new androidx.lifecycle.ViewModelProvider(a)
				.get(ImageViewModel.class);
		model.eventOccurred(new org.zerionproject.app.api.autodelete.event
				.ConversationMessagesDeletedEvent(
				new org.zerionproject.core.api.contact.ContactId(1),
				java.util.Collections.singletonList(
						new MessageId(getRandomId()))));
		idle();
		assertFalse(a.isFinishing());
		model.eventOccurred(new org.zerionproject.app.api.autodelete.event
				.ConversationMessagesDeletedEvent(
				new org.zerionproject.core.api.contact.ContactId(1),
				java.util.Collections.singletonList(new MessageId(message))));
		idle();
		assertTrue("the deleted message's picture is still on screen",
				a.isFinishing());
	}

	@Test
	public void openingInAnotherAppStagesACopyOnlyWhenTheUserAsks()
			throws Exception {
		try (MockedStatic<FileProvider> provider =
				mockStatic(FileProvider.class)) {
			provider.when(() -> FileProvider.getUriForFile(any(), anyString(),
					any(File.class))).thenAnswer(inv -> {
						File f = inv.getArgument(2);
						return Uri.parse("content://" + inv.getArgument(1)
								+ "/" + f.getParentFile().getName() + "/"
								+ f.getName());
					});
			byte[] picture = MediaViewerTest.jpeg();
			ProbeViewer a = open(shown(picture, false)).get();
			runIo();
			assertNull("the picture went to another app by itself",
					nextView(a));
			assertEquals(new ArrayList<>(),
					MediaViewerTest.storageHolding(ctx, MediaViewerTest.MARKER));
			shadowOf(a).clickMenuItem(R.id.action_open_elsewhere);
			runIo();
			Intent view = nextView(a);
			assertNotNull("asking did not open it elsewhere", view);
			assertEquals("image/png", view.getType());
			assertTrue((view.getFlags()
					& Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
			assertEquals(0, view.getFlags()
					& Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			List<String> seg = view.getData().getPathSegments();
			File staged = new File(new File(ctx.getCacheDir(), seg.get(0)),
					seg.get(1));
			assertEquals("external_view", seg.get(0));
			assertArrayEquals(picture, Files.readAllBytes(staged.toPath()));
		}
	}

	private static Intent nextView(Activity a) {
		Intent i;
		while ((i = shadowOf(a).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) return i;
		}
		return null;
	}

	@Test
	public void savingAPictureAsksFirstAndWritesNothingItself()
			throws Exception {
		ProbeViewer a = open(shown(MediaViewerTest.jpeg(), false)).get();
		Toolbar toolbar = a.findViewById(R.id.toolbar);
		assertTrue(toolbar.getMenu().findItem(R.id.action_save_image)
				.isVisible());
		assertTrue(toolbar.getMenu().findItem(R.id.action_open_elsewhere)
				.isVisible());
		while (shadowOf(a).getNextStartedActivity() != null) idle();
		shadowOf(a).clickMenuItem(R.id.action_save_image);
		idle();
		assertNull("saving started without asking",
				shadowOf(a).getNextStartedActivity());
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE)
				.performClick();
		idle();
		Intent save = shadowOf(a).getNextStartedActivity();
		assertNotNull(save);
		assertEquals(SaveCleanCopyActivity.class.getName(),
				save.getComponent().getClassName());
		assertEquals(new ArrayList<>(),
				MediaViewerTest.storageHolding(ctx, MediaViewerTest.MARKER));
	}

	private static int themeColor(Activity a, int attr) {
		android.util.TypedValue v = new android.util.TypedValue();
		assertTrue(a.getTheme().resolveAttribute(attr, v, true));
		return v.data;
	}

	private void chromeUsesTheScrimColors() {
		ProbeViewer a = open(shown(MediaViewerTest.jpeg(), false)).get();
		int onScrim = themeColor(a, R.attr.zerionColorOnScrim);
		TextView title = a.findViewById(R.id.contactName);
		TextView date = a.findViewById(R.id.dateView);
		assertEquals(onScrim, title.getCurrentTextColor());
		assertEquals(onScrim, date.getCurrentTextColor());
		android.graphics.drawable.Drawable bar =
				a.findViewById(R.id.appBarLayout).getBackground();
		int barColor;
		if (bar instanceof com.google.android.material.shape
				.MaterialShapeDrawable) {
			barColor = ((com.google.android.material.shape
					.MaterialShapeDrawable) bar).getFillColor()
					.getDefaultColor();
		} else {
			barColor = ((android.graphics.drawable.ColorDrawable) bar)
					.getColor();
		}
		assertEquals(themeColor(a, R.attr.zerionColorScrim), barColor);
		assertFalse("dark icons over the dark viewer",
				androidx.core.view.WindowCompat.getInsetsController(
						a.getWindow(), a.getWindow().getDecorView())
						.isAppearanceLightStatusBars());
	}

	@Test
	public void theViewerChromeUsesTheThemeScrimInTheLightTheme() {
		chromeUsesTheScrimColors();
	}

	@Test
	@Config(qualifiers = "night")
	public void theViewerChromeUsesTheThemeScrimInTheDarkTheme() {
		chromeUsesTheScrimColors();
	}

	private static AttachmentItem waiting() {
		return AttachmentItemsForTests.missing(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"image/png"));
	}

	@Test
	public void eachPictureSaysWhereItIsAndTheNewOneIsAnnounced() {
		AccessibilityManager am = (AccessibilityManager)
				ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
		shadowOf(am).setEnabled(true);
		ArrayList<AttachmentItem> items = new ArrayList<>();
		for (int n = 0; n < 3; n++) items.add(waiting());
		Intent i = new Intent(ctx, ProbeViewer.class)
				.putParcelableArrayListExtra(ImageActivity.ATTACHMENTS, items)
				.putExtra(ImageActivity.ATTACHMENT_POSITION, 1)
				.putExtra(ImageActivity.NAME, "Bob")
				.putExtra(ImageActivity.DATE, 1000L)
				.putExtra(ImageActivity.ITEM_ID, getRandomId());
		ProbeViewer a = Robolectric.buildActivity(ProbeViewer.class, i)
				.setup().get();
		layout(a);
		assertEquals("Image 2 of 3",
				String.valueOf(page(a, 1).getContentDescription()));
		ViewPager2 pager = a.findViewById(R.id.viewPager);
		pager.setCurrentItem(2, false);
		layout(a);
		assertEquals("Image 3 of 3",
				String.valueOf(page(a, 2).getContentDescription()));
		boolean announced = false;
		for (AccessibilityEvent e : shadowOf(am).getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT
					&& String.valueOf(e.getText()).contains("Image 3 of 3")) {
				announced = true;
			}
		}
		assertTrue("the new position was not announced", announced);
	}
}
