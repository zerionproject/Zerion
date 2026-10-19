package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostDelivery;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrOwnPostStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;
import org.zerionproject.core.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupOwnPostStateTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static IdentityManager identity;
		static ContactManager contacts;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			contactManager = contacts;
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] memberKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);
	private final byte[] mineId = key((byte) 10);
	private final byte[] theirsId = key((byte) 11);
	private final ContactId memberContact = new ContactId(31);
	private final ContactId strangerContact = new ContactId(32);
	private byte[] groupId;
	private GroupTrManager manager;
	private final Map<String, GroupTrPostDelivery> deliveries =
			new HashMap<>();
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		IdentityManager identity = mock(IdentityManager.class);
		LocalAuthor local = new LocalAuthor(new AuthorId(key((byte) 9)), 1,
				"Local", new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32]));
		when(identity.getLocalAuthor()).thenReturn(local);
		ProbeConversation.identity = identity;
		ContactManager contacts = mock(ContactManager.class);
		when(contacts.getContacts()).thenReturn(Arrays.asList(
				contact(memberContact, memberKey, local),
				contact(strangerContact, strangerKey, local)));
		ProbeConversation.contacts = contacts;
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(localKey, "Local", 0L, 0L));
		members.add(new GroupTrMember(memberKey, "Member", 0L, 0L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				members));
		when(manager.getMembersOutOfReach(any()))
				.thenReturn(Collections.emptyList());
		GroupTrPost mine = post(localKey, "my post", 1_000L, mineId);
		GroupTrPost theirs = post(memberKey, "their post", 2_000L, theirsId);
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs));
		when(manager.getOwnPostDeliveries(any()))
				.thenAnswer(inv -> new HashMap<>(deliveries));
		when(manager.getOwnPostDeliveries(any(), any())).thenAnswer(inv -> {
			Map<String, GroupTrPostDelivery> named = new HashMap<>();
			for (Object id : (java.util.Collection<?>) inv.getArgument(1)) {
				String key = StringUtils.toHexString((byte[]) id);
				if (deliveries.containsKey(key)) {
					named.put(key, deliveries.get(key));
				}
			}
			return named;
		});
		ProbeConversation.manager = manager;
	}

	private GroupTrOwnPostStateChangedEvent changed(byte[] group,
			byte[] post) {
		return new GroupTrOwnPostStateChangedEvent(group,
				Collections.singletonList(post));
	}

	private static Contact contact(ContactId id, byte[] pub,
			LocalAuthor local) {
		return new Contact(id, new Author(new AuthorId(pub), 1, "c",
				new SignaturePublicKey(pub)), local.getId(), null, null, true);
	}

	private String state(int res) {
		return activity.getString(res);
	}

	@Test
	public void aStoredOwnPostSaysWhatItsCopiesSay() throws Exception {
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.SENT);
		activity = open();

		assertTrue(meta(0), meta(0).endsWith(
				state(R.string.message_state_sent)));
		assertFalse("a post by someone else carries no state",
				meta(1).contains("·"));
	}

	@Test
	public void eachStateReadsLikeAOneToOneMessage() throws Exception {
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.WAITING);
		activity = open();
		assertTrue(meta(0), meta(0).endsWith(
				state(R.string.message_state_waiting)));

		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.DELIVERED);
		activity.eventOccurred(changed(groupId, mineId));
		runIo();

		assertTrue(meta(0), meta(0).endsWith(
				state(R.string.message_state_delivered)));
	}

	@Test
	public void aCopyLeavingForAMemberRefreshesTheState() throws Exception {
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.WAITING);
		activity = open();
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.SENT);
		clearInvocations(manager);

		activity.eventOccurred(changed(groupId, mineId));
		runIo();

		verify(manager, atLeastOnce()).getOwnPostDeliveries(any(),
				org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 1
						&& Arrays.equals((byte[]) ids.iterator().next(),
						mineId)));
		verify(manager, never()).getOwnPostDeliveries(any());
		verify(manager, never()).getRecentPosts(any());
		assertTrue(meta(0), meta(0).endsWith(
				state(R.string.message_state_sent)));
	}

	@Test
	public void syncTrafficThatIsNoOwnCopyChangesNothing() throws Exception {
		activity = open();
		clearInvocations(manager);

		activity.eventOccurred(new MessagesAckedEvent(memberContact,
				Collections.singletonList(new MessageId(new byte[32]))));
		activity.eventOccurred(new MessagesSentEvent(memberContact,
				Collections.singletonList(new MessageId(new byte[32])), 10L));
		activity.eventOccurred(new MessagesAckedEvent(strangerContact,
				Collections.singletonList(new MessageId(new byte[32]))));
		activity.eventOccurred(changed(key((byte) 77), mineId));
		runIo();

		verify(manager, never()).getOwnPostDeliveries(any());
		verify(manager, never()).getOwnPostDeliveries(any(), any());
		verify(manager, never()).getRecentPosts(any());
	}

	@Test
	public void anOlderReadNeverReplacesANewerOne() throws Exception {
		String mine = StringUtils.toHexString(mineId);
		deliveries.put(mine, GroupTrPostDelivery.WAITING);
		activity = open();
		Map<String, GroupTrPostDelivery> older = new HashMap<>();
		older.put(mine, GroupTrPostDelivery.SENT);
		when(manager.getOwnPostDeliveries(any())).thenAnswer(inv -> {
			deliveries.put(mine, GroupTrPostDelivery.DELIVERED);
			activity.eventOccurred(changed(groupId, mineId));
			ProbeConversation.io.remove(0).run();
			return older;
		});

		activity.eventOccurred(new GroupTrPostAcceptedEvent(groupId, true));
		runIo();

		assertTrue("a slower earlier read put back an older state: "
						+ meta(0),
				meta(0).endsWith(state(R.string.message_state_delivered)));
	}

	@Test
	public void aFailedReadOfSomePostsAsksForThemAgainWithTheNext()
			throws Exception {
		String mine = StringUtils.toHexString(mineId);
		deliveries.put(mine, GroupTrPostDelivery.WAITING);
		activity = open();
		org.mockito.stubbing.Answer<Object> named = inv -> {
			Map<String, GroupTrPostDelivery> out = new HashMap<>();
			for (Object id : (java.util.Collection<?>) inv.getArgument(1)) {
				String key = StringUtils.toHexString((byte[]) id);
				if (deliveries.containsKey(key)) {
					out.put(key, deliveries.get(key));
				}
			}
			return out;
		};
		org.mockito.Mockito.doThrow(new DbException()).doAnswer(named)
				.when(manager).getOwnPostDeliveries(any(), any());
		deliveries.put(mine, GroupTrPostDelivery.DELIVERED);

		activity.eventOccurred(changed(groupId, mineId));
		runIo();
		assertTrue("a failed read takes no state away: " + meta(0),
				meta(0).endsWith(state(R.string.message_state_waiting)));

		activity.eventOccurred(changed(groupId, key((byte) 50)));
		runIo();

		verify(manager, atLeastOnce()).getOwnPostDeliveries(any(),
				org.mockito.ArgumentMatchers.argThat(ids -> {
					for (Object id : ids) {
						if (Arrays.equals((byte[]) id, mineId)) return true;
					}
					return false;
				}));
		assertTrue("the post whose read failed is read again: " + meta(0),
				meta(0).endsWith(state(R.string.message_state_delivered)));
	}

	@Test
	public void aFailedFullReadLeavesTheStatesShown() throws Exception {
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.DELIVERED);
		activity = open();
		assertTrue(meta(0), meta(0).endsWith(
				state(R.string.message_state_delivered)));
		org.mockito.Mockito.doThrow(new DbException()).when(manager)
				.getOwnPostDeliveries(any());

		activity.eventOccurred(new GroupTrPostAcceptedEvent(groupId, true));
		runIo();

		assertTrue("a failed read takes no state away: " + meta(0),
				meta(0).endsWith(state(R.string.message_state_delivered)));
	}

	@Test
	public void withoutAKnownStateTheTimeStandsAlone() throws Exception {
		activity = open();

		assertFalse(meta(0), meta(0).contains("·"));
	}

	@Test
	public void messageInfoNamesTheState() throws Exception {
		deliveries.put(StringUtils.toHexString(mineId),
				GroupTrPostDelivery.DELIVERED);
		activity = open();
		holder(0).itemView.performLongClick();
		idle();

		assertTrue(bar().perform(SelectionAction.INFO));
		idle();

		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		TextView m = dialog.findViewById(android.R.id.message);
		assertNotNull(m);
		String text = m.getText().toString();
		assertTrue(text, text.contains(state(R.string.message_info_status)));
		assertTrue(text, text.contains(
				state(R.string.message_state_delivered)));
	}

	private String meta(int position) {
		return text(position, R.id.postTime);
	}

	private String text(int position, int id) {
		TextView v = holder(position).itemView.findViewById(id);
		assertNotNull("no view at " + position, v);
		return v.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private SelectionBar bar() throws Exception {
		Field f = GroupTrConversationActivity.class.getDeclaredField(
				"selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(activity);
	}

	private GroupTrPost post(byte[] sender, String text, long timestamp,
			byte[] id) {
		return new GroupTrPost(groupId, sender, "Name",
				GroupTrBody.encodeText(text), timestamp, 1L,
				Arrays.equals(sender, localKey), 0L, timestamp, id);
	}

	private ProbeConversation open() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a =
				Robolectric.buildActivity(ProbeConversation.class, i)
						.setup().get();
		activity = a;
		runIo();
		return a;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		while (!ProbeConversation.io.isEmpty()) {
			Runnable r = ProbeConversation.io.remove(0);
			r.run();
			idle();
		}
		idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
