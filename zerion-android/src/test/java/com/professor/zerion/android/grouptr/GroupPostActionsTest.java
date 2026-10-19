package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.conversation.TextForwarder;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionBarTest;

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
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
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
import java.util.EnumSet;
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
		static TextForwarder forwarder;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			textForwarder = forwarder;
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
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(key((byte) 20), 2, 2, 0, 0));
		when(manager.sendGroupReplyReporting(any(), any(), anyString(),
				anyLong())).thenReturn(new GroupTrSendResult(key((byte) 21),
				2, 2, 0, 0));
		ProbeConversation.manager = manager;
		ProbeConversation.forwarder = mock(TextForwarder.class);
		activity = open();
	}

	@Test
	public void aLongPressSelectsAndClosingTheMenuClearsTheSelection()
			throws Exception {
		longPress(0);
		assertTrue(bar().isShowing());
		assertEquals(1, selected());
		assertTrue(holder(0).itemView.isActivated());

		bar().close();
		idle();

		assertFalse(bar().isShowing());
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
		assertFalse("unselecting the last post closes the bar",
				bar().isShowing());
	}

	@Test
	public void replyIsOfferedForASinglePostAndCopyForText()
			throws Exception {
		longPress(0);
		assertTrue(shown().contains(SelectionAction.REPLY));
		assertTrue(shown().contains(SelectionAction.COPY));
		assertTrue(shown().contains(SelectionAction.DELETE));

		holder(1).itemView.performClick();
		idle();
		assertFalse(shown().contains(SelectionAction.REPLY));
		assertTrue(shown().contains(SelectionAction.COPY));
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

		assertFalse(shown().contains(SelectionAction.COPY));
		assertFalse(shown().contains(SelectionAction.FORWARD));
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
		assertFalse(bar().isShowing());
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

		assertTrue(bar().isShowing());
		assertEquals(1, selected());
		verify(manager, never()).deletePostsForMe(any(), any());
	}

	@Test
	public void replyShowsAPreviewAndSendsAReply() throws Exception {
		longPress(1);
		assertTrue(bar().perform(SelectionAction.REPLY));
		idle();

		View preview = activity.findViewById(R.id.groupReplyPreview);
		assertEquals(View.VISIBLE, preview.getVisibility());
		assertFalse(bar().isShowing());
		assertEquals("their post", ((TextView) preview.findViewById(
				R.id.reply_text)).getText().toString());
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("an answer");
		activity.findViewById(R.id.sendButton).performClick();
		runIo();

		verify(manager).sendGroupReplyReporting(eq(groupId), eq(theirsId),
				eq("an answer"), eq(0L));
		verify(manager, never()).sendGroupPostReporting(any(), any(),
				anyLong());
		assertEquals(View.GONE, preview.getVisibility());
	}

	@Test
	public void cancellingTheReplySendsAPlainPost() throws Exception {
		longPress(1);
		assertTrue(bar().perform(SelectionAction.REPLY));
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

		verify(manager).sendGroupPostReporting(eq(groupId), any(), eq(0L));
		verify(manager, never()).sendGroupReplyReporting(any(), any(),
				anyString(), anyLong());
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

	@Test
	public void aPostStillBeingSentNeverJoinsTheSelection() throws Exception {
		longPress(1);
		assertTrue(bar().perform(SelectionAction.REPLY));
		idle();
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("an answer");
		activity.findViewById(R.id.sendButton).performClick();
		idle();
		assertEquals(3, adapter().getItemCount());

		longPress(2);
		assertFalse("a post still being sent is not selected",
				bar().isShowing());

		longPress(0);
		holder(2).itemView.findViewById(R.id.quoteBlock).performClick();
		idle();

		assertEquals("tapping the quote of a waiting post selects nothing",
				1, selected());
		assertTrue(holder(0).itemView.isActivated());
		assertFalse(holder(2).itemView.isActivated());
	}

	@Test
	public void aPostStillBeingSentCannotBeSwipedIntoAReply()
			throws Exception {
		EditText input = activity.findViewById(R.id.messageInput);
		input.setText("on its way");
		activity.findViewById(R.id.sendButton).performClick();
		idle();
		assertEquals(3, adapter().getItemCount());
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		Field f = GroupTrConversationActivity.class.getDeclaredField(
				"swipeCallback");
		f.setAccessible(true);
		androidx.recyclerview.widget.ItemTouchHelper.Callback swipe =
				(androidx.recyclerview.widget.ItemTouchHelper.Callback)
						f.get(activity);
		assertEquals(0, swipe.getMovementFlags(list, holder(2)));
		assertTrue(swipe.getMovementFlags(list, holder(1)) != 0);
	}

	@Test
	public void aGroupOffersOnlyWhatItsPostsSupport() throws Exception {
		longPress(0);

		assertEquals(EnumSet.of(SelectionAction.REPLY, SelectionAction.COPY,
				SelectionAction.DELETE, SelectionAction.FORWARD,
				SelectionAction.INFO), shown());
	}

	@Test
	public void severalPostsOfferOnlyActionsForSeveral() throws Exception {
		longPress(0);
		holder(1).itemView.performClick();
		idle();

		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.FORWARD), shown());
	}

	@Test
	public void aDisappearingPostIsNeverForwarded() throws Exception {
		long now = System.currentTimeMillis();
		GroupTrPost timed = new GroupTrPost(groupId, memberKey, "Name",
				GroupTrBody.encodeText("gone soon"), now, 1L, false,
				24L * 60L * 60L * 1000L, now, key((byte) 15));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, timed));
		activity = open();

		longPress(2);
		assertTrue(shown().contains(SelectionAction.COPY));
		assertFalse("its timer would not travel with it",
				shown().contains(SelectionAction.FORWARD));

		holder(1).itemView.performClick();
		idle();
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE),
				shown());
	}

	@Test
	public void forwardHandsTheTextToTheContactPicker() throws Exception {
		longPress(1);
		assertTrue(bar().perform(SelectionAction.FORWARD));
		idle();

		verify(ProbeConversation.forwarder).pickAndForward(activity,
				"their post", null);
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void aSelectedPostIsMarkedForTalkBack() throws Exception {
		longPress(1);

		assertTrue(holder(1).itemView.isSelected());
		assertTrue(SelectionBarTest.drawsHighlight(holder(1).itemView));
		assertFalse(holder(0).itemView.isSelected());
		assertFalse(SelectionBarTest.drawsHighlight(holder(0).itemView));
	}

	@Test
	public void threeSelectedPostsAreEachToldAsSelected() throws Exception {
		GroupTrPost third = post(memberKey, GroupTrBody.encodeText("third"),
				3_000L, key((byte) 12));
		GroupTrPost fourth = post(memberKey, GroupTrBody.encodeText("fourth"),
				4_000L, key((byte) 13));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, third, fourth));
		activity = open();

		longPress(0);
		holder(1).itemView.performClick();
		idle();
		holder(2).itemView.performClick();
		idle();

		assertEquals(3, selected());
		for (int i = 0; i < 3; i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.toldSelected(row));
			assertTrue(SelectedLook.toldSelected(SelectedLook.bubble(row)));
			assertTrue(SelectedLook.drawsSelected(row));
			assertNull(row.getBackground());
		}
		assertFalse(SelectedLook.toldSelected(holder(3).itemView));
		assertFalse(SelectedLook.drawsSelected(holder(3).itemView));

		holder(1).itemView.performClick();
		idle();
		assertEquals(2, selected());
		assertFalse(SelectedLook.toldSelected(holder(1).itemView));
		assertFalse(SelectedLook.drawsSelected(holder(1).itemView));
	}

	@Test
	public void messageInfoNamesWhoSentIt() throws Exception {
		longPress(1);
		assertTrue(bar().perform(SelectionAction.INFO));
		idle();

		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		String text = message(dialog);
		assertTrue(text, text.contains(activity.getString(
				R.string.message_info_from)));
		assertTrue(text, text.contains("Member"));
		assertTrue(text, text.contains(activity.getString(
				R.string.message_info_sent)));
		assertFalse(bar().isShowing());
	}

	@Test
	public void backAndATapBesideThePostsClearTheSelection()
			throws Exception {
		longPress(0);
		SelectionBarTest.pressBack(activity);
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
		assertFalse(activity.isFinishing());

		longPress(0);
		RecyclerView list = activity.findViewById(R.id.postsRecycler);
		long now = android.os.SystemClock.uptimeMillis();
		android.view.MotionEvent down = android.view.MotionEvent.obtain(now,
				now, android.view.MotionEvent.ACTION_DOWN, 500f, 5f, 0);
		android.view.MotionEvent up = android.view.MotionEvent.obtain(now,
				now + 50, android.view.MotionEvent.ACTION_UP, 500f, 5f, 0);
		list.dispatchTouchEvent(down);
		list.dispatchTouchEvent(up);
		idle();
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void aLongPressOnALinkInAPostSelectsItWithoutOpeningTheLink()
			throws Exception {
		GroupTrPost link = post(memberKey,
				GroupTrBody.encodeText("https://example.org/route"), 3_000L,
				key((byte) 12));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, link));
		activity = open();
		TextView body = holder(2).itemView.findViewById(R.id.postText);
		assertNotNull("the text is link aware", body.getMovementMethod());

		SelectionBarTest.longPress(body);

		assertTrue(bar().isShowing());
		assertEquals(1, selected());
		assertTrue(holder(2).itemView.isActivated());
		assertNull("the long press does not ask about the link",
				ShadowDialog.getLatestDialog());

		bar().close();
		idle();
		SelectionBarTest.tap(body);
		assertNotNull("a tap still asks about the link",
				ShadowDialog.getLatestDialog());
		assertFalse(bar().isShowing());
	}

	@Test
	public void aTapOnTheTextWhileSelectingTogglesThePost() throws Exception {
		longPress(0);
		TextView body = holder(1).itemView.findViewById(R.id.postText);

		SelectionBarTest.tap(body);
		assertEquals("the tapped post joins the selection", 2, selected());
		assertTrue(holder(1).itemView.isActivated());

		SelectionBarTest.tap(body);
		assertEquals("a second tap lets it go", 1, selected());
		assertTrue(bar().isShowing());
	}

	@Test
	public void aTapOnALinkWhileSelectingTogglesThePostInsteadOfOpeningIt()
			throws Exception {
		GroupTrPost link = post(memberKey,
				GroupTrBody.encodeText("https://example.org/route"), 3_000L,
				key((byte) 12));
		when(manager.getRecentPosts(any()))
				.thenReturn(Arrays.asList(mine, theirs, link));
		activity = open();
		longPress(0);
		TextView body = holder(2).itemView.findViewById(R.id.postText);

		SelectionBarTest.tap(body);

		assertEquals(2, selected());
		assertTrue(holder(2).itemView.isActivated());
		assertNull("the link does not open while selecting",
				ShadowDialog.getLatestDialog());
	}

	private AlertDialog deleteDialog() throws Exception {
		assertTrue(bar().perform(SelectionAction.DELETE));
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

	private java.util.Set<SelectionAction> shown() throws Exception {
		return bar().shownActions();
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

	private SelectionBar bar() throws Exception {
		return (SelectionBar) field("selectionBar");
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
