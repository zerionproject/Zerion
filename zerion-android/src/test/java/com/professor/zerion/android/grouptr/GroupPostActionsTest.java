package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenu;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupPostActionsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static GroupTrManager manager;
		static IdentityManager identity;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] memberKey = key((byte) 3);
	private final byte[] mineId = key((byte) 10);
	private final byte[] theirsId = key((byte) 11);
	private byte[] groupId;
	private GroupTrManager manager;
	private GroupTrPost mine;
	private GroupTrPost theirs;
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		IdentityManager identity = mock(IdentityManager.class);
		when(identity.getLocalAuthor()).thenReturn(new LocalAuthor(
				new AuthorId(key((byte) 9)), 1, "Local",
				new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32])));
		ProbeConversation.identity = identity;
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
		mine = post(localKey, GroupTrBody.encodeText("my post"), 1_000L,
				mineId);
		theirs = post(memberKey, GroupTrBody.encodeText("their post"),
				2_000L, theirsId);
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs));
		when(manager.countMembersWithPostActions(any())).thenReturn(2);
		when(manager.countMembersWithoutPostActions(any())).thenReturn(0);
		ProbeConversation.manager = manager;
		activity = open();
	}

	@Test
	public void aLongPressSelectsAndClosingTheMenuClearsTheSelection()
			throws Exception {
		longPress(0);
		assertNotNull(mode());
		assertEquals(1, selected());
		assertTrue(holder(0).itemView.isActivated());

		mode().finish();
		idle();

		assertNull(mode());
		assertEquals(0, selected());
		assertFalse(holder(0).itemView.isActivated());
		longPress(1);
		assertEquals("only the post pressed now is selected", 1, selected());
	}

	@Test
	public void aTapWhileSelectingTogglesThePost() throws Exception {
		longPress(0);
		holder(1).itemView.performClick();
		idle();
		assertEquals(2, selected());

		holder(1).itemView.performClick();
		idle();
		assertEquals(1, selected());

		holder(0).itemView.performClick();
		idle();
		assertNull("unselecting the last post closes the menu", mode());
	}

	@Test
	public void replyIsOfferedForASinglePostAndCopyForText()
			throws Exception {
		longPress(0);
		Menu menu = menu();
		assertTrue(menu.findItem(R.id.action_reply).isVisible());
		assertTrue(menu.findItem(R.id.action_copy).isVisible());
		assertTrue(menu.findItem(R.id.action_delete).isVisible());

		holder(1).itemView.performClick();
		idle();
		menu = menu();
		assertFalse(menu.findItem(R.id.action_reply).isVisible());
		assertTrue(menu.findItem(R.id.action_copy).isVisible());
	}

	@Test
	public void copyIsNotOfferedForAVoicePost() throws Exception {
		GroupTrPost voice = post(memberKey,
				GroupTrBody.encodeVoice(new byte[8], 1000L), 3_000L,
				key((byte) 12));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, voice));
		activity = open();

		longPress(2);

		assertFalse(menu().findItem(R.id.action_copy).isVisible());
	}

	@Test
	public void deletingOwnPostsOffersEveryoneAndMe() throws Exception {
		when(manager.countMembersWithoutPostActions(any())).thenReturn(1);
		when(manager.deletePostsForEveryone(any(), any())).thenReturn(1);
		longPress(0);

		AlertDialog dialog = deleteDialog();

		assertEquals(activity.getString(R.string.delete_for_everyone),
				dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText()
						.toString());
		assertEquals(activity.getString(R.string.delete_for_me),
				dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getText()
						.toString());
		String message = message(dialog);
		assertTrue(message.contains(activity.getString(
				R.string.grouptr_delete_for_everyone_explained)));
		assertTrue("the dialog says who keeps it", message.contains(
				activity.getResources().getQuantityString(
						R.plurals.grouptr_delete_kept_older, 1, 1)));

		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals("the post leaves the screen at once", 1,
				adapter().getItemCount());
		assertNull(mode());
		when(manager.getRecentPosts(any()))
				.thenReturn(Collections.singletonList(theirs));
		runIo();

		verify(manager).deletePostsForEveryone(eq(groupId),
				argThatHolds(mineId));
		verify(manager, never()).deletePostsForMe(any(), any());
		assertEquals(1, adapter().getItemCount());
	}

	@Test
	public void deletingForMeFromTheSameDialog() throws Exception {
		longPress(0);

		AlertDialog dialog = deleteDialog();
		assertFalse(message(dialog).contains(activity.getResources()
				.getQuantityString(R.plurals.grouptr_delete_kept_older, 1,
						1)));
		dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
		runIo();

		verify(manager).deletePostsForMe(eq(groupId), argThatHolds(mineId));
		verify(manager, never()).deletePostsForEveryone(any(), any());
	}

	@Test
	public void anotherMembersPostCanOnlyBeDeletedForMe() throws Exception {
		longPress(1);

		AlertDialog dialog = deleteDialog();

		assertEquals(activity.getString(R.string.conversation_delete_action),
				dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText()
						.toString());
		assertEquals(View.GONE, dialog.getButton(
				DialogInterface.BUTTON_NEUTRAL).getVisibility());
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		runIo();
		verify(manager).deletePostsForMe(eq(groupId), argThatHolds(theirsId));
	}

	@Test
	public void withoutAMemberThatCanApplyItOnlyDeleteForMeIsOffered()
			throws Exception {
		when(manager.countMembersWithPostActions(any())).thenReturn(0);
		longPress(0);

		AlertDialog dialog = deleteDialog();

		assertEquals(activity.getString(R.string.conversation_delete_action),
				dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText()
						.toString());
		assertEquals(View.GONE, dialog.getButton(
				DialogInterface.BUTTON_NEUTRAL).getVisibility());
	}

	@Test
	public void cancellingTheDeleteKeepsTheSelection() throws Exception {
		longPress(0);

		AlertDialog dialog = deleteDialog();
		dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		idle();

		assertNotNull(mode());
		assertEquals(1, selected());
		verify(manager, never()).deletePostsForMe(any(), any());
	}

	@Test
	public void replyShowsAPreviewAndSendsAReply() throws Exception {
		longPress(1);
		activity.onActionItemClicked(mode(), new RoboMenuItem(
				R.id.action_reply));
		idle();

		View preview = activity.findViewById(R.id.groupReplyPreview);
		assertEquals(View.VISIBLE, preview.getVisibility());
		assertNull(mode());
		assertEquals("their post", ((TextView) preview.findViewById(
				R.id.reply_text)).getText().toString());
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("an answer");
		activity.findViewById(R.id.sendButton).performClick();
		runIo();

		verify(manager).sendGroupReply(eq(groupId), eq(theirsId),
				eq("an answer"), eq(0L));
		verify(manager, never()).sendGroupPost(any(), any(), anyLong());
		assertEquals(View.GONE, preview.getVisibility());
	}

	@Test
	public void cancellingTheReplySendsAPlainPost() throws Exception {
		longPress(1);
		activity.onActionItemClicked(mode(), new RoboMenuItem(
				R.id.action_reply));
		idle();
		View preview = activity.findViewById(R.id.groupReplyPreview);
		View cancel = preview.findViewById(R.id.cancel_reply);
		assertNotNull(cancel.getContentDescription());

		cancel.performClick();
		idle();
		assertEquals(View.GONE, preview.getVisibility());
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("not a reply");
		activity.findViewById(R.id.sendButton).performClick();
		runIo();

		verify(manager).sendGroupPost(eq(groupId), any(), eq(0L));
		verify(manager, never()).sendGroupReply(any(), any(), anyString(),
				anyLong());
	}

	@Test
	public void aReplyShowsItsQuoteAndAMissingOriginalSaysSo()
			throws Exception {
		GroupTrPost reply = post(memberKey,
				GroupTrBody.encodeReply(mineId, "agreed"), 3_000L,
				key((byte) 13));
		GroupTrPost orphan = post(memberKey,
				GroupTrBody.encodeReply(key((byte) 99), "about what"),
				4_000L, key((byte) 14));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, reply, orphan));
		activity = open();

		View quote = holder(2).itemView.findViewById(R.id.quoteBlock);
		assertEquals(View.VISIBLE, quote.getVisibility());
		assertEquals("my post", ((TextView) holder(2).itemView.findViewById(
				R.id.quoteText)).getText().toString());
		assertEquals("agreed", ((TextView) holder(2).itemView.findViewById(
				R.id.postText)).getText().toString());
		assertNotNull(quote.getContentDescription());
		View missing = holder(3).itemView.findViewById(R.id.quoteBlock);
		assertEquals(View.VISIBLE, missing.getVisibility());
		assertEquals(activity.getString(R.string.reply_original_unavailable),
				((TextView) holder(3).itemView.findViewById(R.id.quoteText))
						.getText().toString());
		assertEquals(View.GONE, holder(0).itemView.findViewById(
				R.id.quoteBlock).getVisibility());
	}

	@Test
	public void aPostDeletedElsewhereLeavesTheScreenAtOnce()
			throws Exception {
		activity.eventOccurred(new GroupTrPostsRemovedEvent(groupId,
				Collections.singletonList(theirsId), 0));
		idle();

		assertEquals(1, adapter().getItemCount());
	}

	private AlertDialog deleteDialog() throws Exception {
		activity.onActionItemClicked(mode(), new RoboMenuItem(
				R.id.action_delete));
		runIo();
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull(d);
		assertTrue(d.isShowing());
		return (AlertDialog) d;
	}

	private static String message(AlertDialog d) {
		TextView m = d.findViewById(android.R.id.message);
		assertNotNull(m);
		return m.getText().toString();
	}

	@SuppressWarnings("unchecked")
	private static Collection<byte[]> argThatHolds(byte[] id) {
		return org.mockito.ArgumentMatchers.argThat(c -> {
			if (c == null || c.size() != 1) return false;
			return Arrays.equals(((Collection<byte[]>) c).iterator().next(),
					id);
		});
	}

	private Menu menu() throws Exception {
		Menu menu = new RoboMenu(activity);
		activity.getMenuInflater().inflate(R.menu.grouptr_post_actions, menu);
		activity.onPrepareActionMode(mode(), menu);
		return menu;
	}

	private void longPress(int position) throws Exception {
		holder(position).itemView.performLongClick();
		idle();
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

	private RecyclerView.Adapter<?> adapter() {
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		return list.getAdapter();
	}

	private ActionMode mode() throws Exception {
		return (ActionMode) field("actionMode");
	}

	private int selected() throws Exception {
		return ((Collection<?>) field("selected")).size();
	}

	private Object field(String name) throws Exception {
		Field f = GroupTrConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(activity);
	}

	private GroupTrPost post(byte[] sender, byte[] body, long timestamp,
			byte[] id) {
		return new GroupTrPost(groupId, sender, "Name", body, timestamp, 1L,
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
