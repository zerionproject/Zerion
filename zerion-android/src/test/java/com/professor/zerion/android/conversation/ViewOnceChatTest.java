package com.professor.zerion.android.conversation;

import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.ViewOnceMedia;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@SuppressWarnings("unchecked")
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ViewOnceChatTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeChat extends ConversationActivity {

		static volatile boolean locked = false;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			LockManager lock = mock(LockManager.class);
			when(lock.isLocked()).thenAnswer(inv -> locked);
			when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
			lockManager = lock;
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = Runnable::run;
		}
	}

	private ProbeChat activity;
	private ActivityController<ProbeChat> controller;
	private ConversationAdapter adapter;
	private ConversationViewOnceItem tile;

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static ConversationViewOnceItem tile(boolean local) {
		GroupId g = new GroupId(getRandomId());
		AttachmentHeader a = new AttachmentHeader(g,
				new MessageId(getRandomId()), "image/jpeg");
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), g, 1_000L, local, true, true,
				false, false, Collections.singletonList(a), -1L, null, false,
				false, true, false);
		return new ConversationViewOnceItem(h, new MutableLiveData<>("Bob"));
	}

	@Before
	public void setUp() throws Exception {
		MediaViewer.wiper = Runnable::run;
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeChat.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		ActivityController<ProbeChat> c =
				Robolectric.buildActivity(ProbeChat.class, i);
		controller = c;
		activity = SelectedLook.start(c,
				AppCompatDelegate.MODE_NIGHT_UNSPECIFIED);
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		tile = tile(false);
		adapter.add(tile);
		idle();
	}

	@After
	public void tearDown() {
		ProbeChat.locked = false;
		MediaViewer.wiper = MediaViewer.DEFAULT_WIPER;
		MediaViewer.clearAll();
	}

	private void ready(ViewOnceMedia media) throws Exception {
		Method m = ConversationActivity.class.getDeclaredMethod(
				"onViewOnceReady", ConversationViewModel.ViewOnceResult.class);
		m.setAccessible(true);
		m.invoke(activity, new ConversationViewModel.ViewOnceResult(
				tile.getId(), media));
		idle();
	}

	private Intent viewerStarted() {
		Intent i;
		while ((i = shadowOf(activity).getNextStartedActivity()) != null) {
			String screen = i.getComponent() == null ? ""
					: i.getComponent().getClassName();
			if (screen.equals(ImageActivity.class.getName())
					|| screen.equals(VideoPlayerActivity.class.getName())) {
				return i;
			}
		}
		return null;
	}

	@Test
	public void aViewOnceTileCanOnlyBeDeleted() throws Exception {
		activity.onMessageLongClick(tile);
		idle();

		Set<SelectionAction> shown =
				((SelectionBar) field(activity, "selectionBar"))
						.shownActions();
		assertTrue(shown.contains(SelectionAction.DELETE));
		for (SelectionAction a : EnumSet.of(SelectionAction.COPY,
				SelectionAction.FORWARD, SelectionAction.REPLY,
				SelectionAction.REACT, SelectionAction.INFO,
				SelectionAction.SAVE, SelectionAction.EDIT)) {
			assertFalse(a.name(), shown.contains(a));
		}
	}

	@Test
	public void theOpenedMediaGoesToAProtectedViewerWithoutACopy()
			throws Exception {
		byte[] data = MediaViewerTest.jpeg();
		ViewOnceMedia media = new ViewOnceMedia("image/png", data);
		shadowOf(android.webkit.MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("png", "image/png");

		ready(media);

		Intent started = viewerStarted();
		assertNotNull(started);
		assertEquals(ImageActivity.class.getName(),
				started.getComponent().getClassName());
		String token = started.getStringExtra(MediaViewer.EXTRA_TOKEN);
		assertTrue(MediaViewer.once(token));
		assertTrue(MediaViewer.secure(token));
		MediaViewer.Shown held = MediaViewer.get(token);
		assertNotNull(held);
		assertSame("the media was copied", data, held.data);
		assertTrue("the tile still offers the media", tile.isOpened());
	}

	@Test
	public void aTileWhoseMediaCannotBeReadKeepsItsStateAndOpensNothing()
			throws Exception {
		ready(null);

		assertFalse("the tile says Viewed before the database does",
				tile.isOpened());
		assertNull(viewerStarted());
	}

	@Test
	public void theFirstOpeningAsksTheViewerToRespectTheSender() {
		activity.onViewOnceClicked(tile);
		idle();

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		TextView message = dialog.findViewById(android.R.id.message);
		assertEquals(activity.getString(R.string.view_once_notice),
				message.getText().toString());
		dialog.dismiss();
		assertFalse(tile.isOpened());
	}

	@Test
	public void mediaThatArrivesWhileTheChatIsAwayIsWipedNotShown()
			throws Exception {
		byte[] data = MediaViewerTest.jpeg();
		controller.pause().stop();

		ready(new ViewOnceMedia("image/png", data));

		assertNull(viewerStarted());
		assertTrue(tile.isOpened());
		assertArrayEquals(new byte[data.length], data);
		assertEquals(activity.getString(R.string.view_once_missed),
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aSecondTapWhileTheNoticeIsOpenShowsNoSecondNotice() {
		activity.onViewOnceClicked(tile);
		idle();
		int shown = ShadowDialog.getShownDialogs().size();

		activity.onViewOnceClicked(tile);
		idle();

		assertEquals(shown, ShadowDialog.getShownDialogs().size());
	}

	@Test
	public void theSenderCanTakeAViewOncePhotoBack() throws Exception {
		ConversationViewOnceItem mine = tile(true);
		adapter.add(mine);
		idle();
		Object model = field(activity, "viewModel");
		Field supported = ConversationViewModel.class.getDeclaredField(
				"editAndDeleteSupported");
		supported.setAccessible(true);
		((MutableLiveData<Boolean>) supported.get(model)).setValue(true);
		activity.onMessageLongClick(mine);
		idle();
		Method everyone = ConversationActivity.class.getDeclaredMethod(
				"canDeleteSelectionForEveryone");
		everyone.setAccessible(true);

		assertTrue((Boolean) everyone.invoke(activity));

		((SelectionBar) field(activity, "selectionBar")).close();
		idle();
		activity.onMessageLongClick(tile);
		idle();
		assertFalse("a received tile was offered for everyone",
				(Boolean) everyone.invoke(activity));
	}

	private void layout() throws Exception {
		androidx.recyclerview.widget.RecyclerView rv =
				((com.professor.zerion.android.view.ZerionRecyclerView)
						field(activity, "list")).getRecyclerView();
		rv.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,
				android.view.View.MeasureSpec.EXACTLY),
				android.view.View.MeasureSpec.makeMeasureSpec(1920,
						android.view.View.MeasureSpec.EXACTLY));
		rv.layout(0, 0, 1080, 1920);
		idle();
	}

	@Test
	public void aTileSeenWhileLockedIsMarkedReadWhenTheChatIsShown()
			throws Exception {
		GroupId g = new GroupId(getRandomId());
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), g, 2_000L, false, false, true,
				false, false, Collections.singletonList(new AttachmentHeader(g,
						new MessageId(getRandomId()), "image/jpeg")), -1L,
				null, false, false, true, false);
		ProbeChat.locked = true;
		ConversationViewOnceItem unread =
				new ConversationViewOnceItem(h, new MutableLiveData<>("Bob"));
		adapter.add(unread);
		layout();
		assertFalse("read while locked", unread.isRead());

		ProbeChat.locked = false;
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", java.util.Collection.class);
		display.setAccessible(true);
		display.invoke(activity, java.util.Arrays.asList(h,
				tileHeader(tile)));
		idle();
		layout();

		assertTrue("the tile is marked read once the chat is shown",
				unread.isRead());
	}

	@Test
	public void aTileIsNotMarkedReadWhileTheChatIsStopped()
			throws Exception {
		GroupId g = new GroupId(getRandomId());
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), g, 2_000L, false, false, true,
				false, false, Collections.singletonList(new AttachmentHeader(g,
						new MessageId(getRandomId()), "image/jpeg")), -1L,
				null, false, false, true, false);
		ConversationViewOnceItem unread =
				new ConversationViewOnceItem(h, new MutableLiveData<>("Bob"));
		adapter.add(unread);
		controller.pause().stop();
		Method display = ConversationActivity.class.getDeclaredMethod(
				"displayMessages", java.util.Collection.class);
		display.setAccessible(true);
		display.invoke(activity, java.util.Arrays.asList(h,
				tileHeader(tile)));
		idle();
		layout();

		assertFalse("a stopped chat marks nothing read", unread.isRead());
	}

	private static PrivateMessageHeader tileHeader(ConversationViewOnceItem t) {
		return new PrivateMessageHeader(t.getId(), t.getGroupId(),
				t.getTime(), false, true, true, false, false,
				Collections.singletonList(new AttachmentHeader(t.getGroupId(),
						new MessageId(getRandomId()), "image/jpeg")), -1L,
				null, false, false, true, false);
	}

	@Test
	public void theSendersOwnTileNeverOpens() {
		ConversationViewOnceItem mine = tile(true);
		adapter.add(mine);
		idle();

		Dialog before = ShadowDialog.getLatestDialog();
		activity.onViewOnceClicked(mine);
		idle();

		assertSame(before, ShadowDialog.getLatestDialog());
		assertNull(viewerStarted());
	}
}
