package com.professor.zerion.android.chat;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.PinnedContactManager;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.homelist.HomeListProbes;
import com.professor.zerion.android.mesh.MeshPresenceTracker;
import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static com.professor.zerion.android.homelist.HomeListProbes.rows;
import static com.professor.zerion.android.homelist.HomeListProbes.text;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getContact;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChatListRowsTest {

	static {
		TestAndroidKeyStore.register();
	}

	static final List<Runnable> io = new ArrayList<>();
	static final MutableLiveData<List<ChatItem>> shown =
			new MutableLiveData<>();
	static ChatsViewModel model;

	private final Application app = ApplicationProvider.getApplicationContext();
	private final ContactManager contactManager = mock(ContactManager.class);
	private final ConversationManager conversationManager =
			mock(ConversationManager.class);
	private final AuthorManager authorManager = mock(AuthorManager.class);

	public static class ProbeChats extends ChatsFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			viewModel = model;
			ioExecutor = io::add;
		}
	}

	private ChatsViewModel realViewModel() {
		return new ChatsViewModel(app, Runnable::run,
				mock(LifecycleManager.class), mock(TransactionManager.class),
				mock(AndroidExecutor.class), contactManager,
				conversationManager, authorManager,
				mock(PinnedContactManager.class),
				mock(ConnectionRegistry.class),
				mock(MeshPresenceTracker.class), mock(EventBus.class));
	}

	private ChatsViewModel fixedViewModel() {
		return new ChatsViewModel(app, Runnable::run,
				mock(LifecycleManager.class), mock(TransactionManager.class),
				mock(AndroidExecutor.class), contactManager,
				conversationManager, authorManager,
				mock(PinnedContactManager.class),
				mock(ConnectionRegistry.class),
				mock(MeshPresenceTracker.class), mock(EventBus.class)) {
			@Override
			LiveData<List<ChatItem>> getItems() {
				return shown;
			}

			@Override
			void load() {
			}

			@Override
			void checkForPendingContacts() {
			}
		};
	}

	@Before
	public void setUp() {
		io.clear();
		shown.setValue(null);
		model = fixedViewModel();
	}

	private static ChatItem chat(int id, String name, long time, int unread,
			boolean pinned, boolean online, boolean muted) {
		return new ChatItem(ChatItem.Type.CONTACT, id, null, name, time,
				unread, pinned, online, muted, null);
	}

	private ProbeChats open(ChatItem... items) {
		shown.setValue(Arrays.asList(items));
		ProbeChats chats = new ProbeChats();
		HomeListProbes.host(chats, io);
		return chats;
	}

	private View onlyRow(ProbeChats chats) {
		List<View> rows = rows(chats);
		assertEquals(1, rows.size());
		return rows.get(0);
	}

	@Test
	public void aChatRowKeepsTheTimeApartFromTheName() {
		View row = onlyRow(open(chat(1, "Alex", System.currentTimeMillis(),
				2, false, true, false)));
		assertEquals("Alex", text(row, R.id.homeRowName));
		assertFalse(text(row, R.id.homeRowTime).isEmpty());
		assertEquals(app.getString(R.string.online),
				text(row, R.id.homeRowPreview));
		assertEquals(View.VISIBLE, row.findViewById(R.id.homeRowPresence)
				.getVisibility());
		assertEquals("2", text(row, R.id.homeRowUnread));
	}

	@Test
	public void pinnedAndMutedChatsShowTheirIcons() {
		View row = onlyRow(open(chat(1, "Maya", 1000L, 0, true, false,
				true)));
		assertEquals(View.VISIBLE, row.findViewById(R.id.homeRowPinned)
				.getVisibility());
		assertEquals(View.VISIBLE, row.findViewById(R.id.homeRowMuted)
				.getVisibility());
		assertEquals(app.getString(R.string.offline),
				text(row, R.id.homeRowPreview));
	}

	@Test
	public void theUnreadBadgeStopsAt99() {
		assertEquals("99+", text(onlyRow(open(chat(1, "Alex", 1000L, 300,
				false, false, false))), R.id.homeRowUnread));
	}

	@Test
	public void tappingAChatOpensTheConversation() {
		ProbeChats chats = open(chat(7, "Alex", 1000L, 0, false, false,
				false));
		onlyRow(chats).performClick();
		Intent next = shadowOf(chats.requireActivity())
				.getNextStartedActivity();
		assertNotNull(next);
		assertEquals(ConversationActivity.class.getName(),
				next.getComponent().getClassName());
		assertEquals(7, next.getIntExtra(CONTACT_ID, -1));
	}

	@Test
	public void holdingAChatStillOpensTheContactMenu() {
		ProbeChats chats = open(chat(7, "Alex", 1000L, 0, false, false,
				false));
		assertTrue(onlyRow(chats).performLongClick());
		shadowOf(Looper.getMainLooper()).idle();
		ShadowPopupMenu popup = shadowOf(ShadowPopupMenu.getLatestPopupMenu());
		assertNotNull(popup);
		assertEquals(3, ShadowPopupMenu.getLatestPopupMenu().getMenu()
				.size());
		assertEquals(app.getString(R.string.pin_conversation),
				String.valueOf(ShadowPopupMenu.getLatestPopupMenu().getMenu()
						.getItem(0).getTitle()));
	}

	@Test
	public void anEmptyListShowsTheSharedEmptyState() {
		ProbeChats chats = open();
		View empty = HomeListProbes.empty(chats);
		assertEquals(View.VISIBLE, empty.getVisibility());
		assertEquals(app.getString(R.string.no_chats),
				text(empty, R.id.homeEmptyTitle));
		assertEquals(app.getString(R.string.no_contacts_action),
				text(empty, R.id.homeEmptyBody));
	}

	@Test
	public void theListLoadsWhichChatsAreMuted() throws Exception {
		Contact c = getContact();
		Contact muted = getContact();
		when(contactManager.getContacts()).thenReturn(
				Arrays.asList(c, muted));
		when(conversationManager.getGroupCount(any())).thenReturn(
				new GroupCount(1, 0, 5L));
		when(authorManager.getAuthorInfo(any(Contact.class))).thenReturn(
				new AuthorInfo(AuthorInfo.Status.UNVERIFIED));
		assertTrue(TestProfileSession.signedInPreferences(app).edit()
				.putBoolean("mute_" + muted.getId().getInt(), true).commit());
		ChatsViewModel real = realViewModel();
		real.load();
		shadowOf(Looper.getMainLooper()).idle();
		List<ChatItem> items = real.getItems().getValue();
		assertNotNull(items);
		assertEquals(2, items.size());
		for (ChatItem item : items) {
			assertEquals(item.getContactId() == muted.getId().getInt(),
					item.isMuted());
		}
		TestProfileSession.signedInPreferences(app).edit()
				.remove("mute_" + muted.getId().getInt()).commit();
	}
}
