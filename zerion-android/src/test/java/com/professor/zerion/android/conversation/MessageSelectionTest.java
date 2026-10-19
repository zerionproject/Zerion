package com.professor.zerion.android.conversation;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionBarTest;
import com.professor.zerion.android.settings.ChatPreferences;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.view.TextInputView;
import com.professor.zerion.android.view.ZerionRecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.ContrastMath.over;
import static com.professor.zerion.android.ContrastMath.ratio;
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

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageSelectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeConversation extends ConversationActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			LockManager lock = mock(LockManager.class);
			when(lock.isLocked()).thenReturn(false);
			when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
			lockManager = lock;
			notificationManager = mock(AndroidNotificationManager.class);
		}
	}

	private ProbeConversation activity;
	private ConversationAdapter adapter;
	private ConversationMessageItem first;
	private ConversationMessageItem second;

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static PrivateMessageHeader header(long time, boolean local) {
		return header(time, local, -1L);
	}

	private static PrivateMessageHeader header(long time, boolean local,
			long timer) {
		return new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				time, local, true, true, false, true,
				Collections.emptyList(), timer);
	}

	private static ConversationMessageItem item(String text, long time) {
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(time, true),
				new MutableLiveData<>("Bob"));
		item.setText(text);
		return item;
	}

	@Before
	public void setUp() throws Exception {
		start(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED);
	}

	private void start(int night) throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		ActivityController<ProbeConversation> c =
				Robolectric.buildActivity(ProbeConversation.class, i);
		activity = SelectedLook.start(c, night);
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		first = item("first", 1_000L);
		second = item("second", 2_000L);
		adapter.add(first);
		adapter.add(second);
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@SuppressWarnings("unchecked")
	private int selected() throws Exception {
		SelectionTracker<String> tracker =
				(SelectionTracker<String>) field(activity, "tracker");
		return tracker == null ? 0 : tracker.getSelection().size();
	}

	private SelectionBar bar() throws Exception {
		return (SelectionBar) field(activity, "selectionBar");
	}

	private void select(ConversationItem item) {
		activity.onMessageLongClick(item);
		idle();
	}

	private RecyclerView.ViewHolder holder(int position) throws Exception {
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	@Test
	public void closingTheBarLeavesNothingSelected() throws Exception {
		select(first);
		assertEquals(1, selected());
		assertTrue(bar().isShowing());

		bar().close();
		idle();

		assertFalse(bar().isShowing());
		assertEquals(0, selected());
		select(second);
		assertEquals("only the message pressed now is selected", 1,
				selected());
	}

	@Test
	public void anActionThatClosesTheBarLeavesNothingSelected()
			throws Exception {
		for (SelectionAction action : new SelectionAction[] {
				SelectionAction.COPY, SelectionAction.REACT}) {
			select(first);
			assertTrue(action.name(), bar().perform(action));
			idle();
			dismissDialogs();
			assertFalse(bar().isShowing());
			assertEquals(0, selected());
		}
		select(second);
		assertEquals(1, selected());
	}

	@Test
	public void cancellingTheDeleteKeepsTheSelectionVisible()
			throws Exception {
		select(first);
		assertTrue(bar().perform(SelectionAction.DELETE));
		idle();
		dismissDialogs();

		assertTrue("the selection stays on screen", bar().isShowing());
		assertEquals(1, selected());
	}

	@Test
	public void editIsOnlyOfferedWhenTheContactCanReceiveEdits()
			throws Exception {
		select(first);
		assertFalse(bar().shownActions().contains(SelectionAction.EDIT));
	}

	@Test
	public void theSelectedMessageIsHighlighted() throws Exception {
		select(second);

		View row = holder(1).itemView;
		assertTrue("the pressed message is highlighted", row.isActivated());
		assertTrue(SelectionBarTest.drawsHighlight(row));
		assertTrue("TalkBack hears that it is selected", row.isSelected());
		assertFalse(holder(0).itemView.isActivated());
		assertFalse(SelectionBarTest.drawsHighlight(holder(0).itemView));

		bar().close();
		idle();
		assertFalse(holder(1).itemView.isActivated());
		assertFalse(holder(1).itemView.isSelected());
	}

	private int color(int attr) {
		return UiUtils.resolveColorAttribute(activity, attr);
	}

	private static ConversationMessageItem incoming(String text, long time) {
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_in, header(time, false),
				new MutableLiveData<>("Bob"));
		item.setText(text);
		return item;
	}

	private static ConversationMessageItem dressed(ConversationMessageItem item) {
		item.setReplyToMessageId(new MessageId(getRandomId()));
		item.setReplyToText("where do we meet");
		item.setReplyToLocal(Boolean.TRUE);
		item.addReaction("👍");
		item.setLinkPreview(new LinkPreview("https://example.org/map", "Map",
				"The route for Saturday", null));
		return item;
	}

	private static ConversationMessageItem picture(boolean local, long time) {
		AttachmentHeader a = new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), "image/jpeg");
		return new ConversationMessageItem(local
				? R.layout.list_item_conversation_msg_out
				: R.layout.list_item_conversation_msg_in, header(time, local),
				new MutableLiveData<>("Bob"), Collections.singletonList(
				AttachmentItemsForTests.missing(a)));
	}

	@Test
	public void aSelectedMessageTintsItsOwnBubbleAndDrawsNoOutline()
			throws Exception {
		ConversationMessageItem in = incoming("hello", 3_000L);
		adapter.add(in);
		idle();
		select(in);
		select(second);

		View inBubble = holder(2).itemView.findViewById(R.id.layout);
		int bubbleIn = color(R.attr.zerionColorBubbleIn);
		assertEquals(bubbleIn, SelectedLook.painted(inBubble, false));
		assertEquals(over(color(R.attr.zerionColorMessageSelectedReceived),
				bubbleIn), SelectedLook.painted(inBubble));
		assertEquals("the edge of a received bubble stays as it was",
				SelectedLook.strokeWidth(inBubble, false),
				SelectedLook.strokeWidth(inBubble, true));
		assertEquals(color(R.attr.zerionColorOutline),
				SelectedLook.strokeColor(inBubble, true));

		View outBubble = holder(1).itemView.findViewById(R.id.layout);
		int own = ChatPreferences.getBubbleColor(activity);
		assertEquals(own, SelectedLook.painted(outBubble, false));
		assertTrue(SelectedLook.near(over(color(
				R.attr.zerionColorMessageSelectedOwn), own),
				SelectedLook.painted(outBubble)));
		assertEquals(0, SelectedLook.strokeWidth(outBubble, true));
		assertEquals("an unselected message keeps its colour", own,
				SelectedLook.painted(holder(0).itemView.findViewById(
						R.id.layout)));
		for (int i = 0; i < 3; i++) {
			assertNull("no row draws an outline around the message",
					holder(i).itemView.getBackground());
		}
	}

	private List<String> unreadableWhenSelected() throws Exception {
		ConversationMessageItem in = dressed(incoming(
				"see https://example.org/map", 3_000L));
		ConversationMessageItem out = dressed(item("on my way", 4_000L));
		ConversationSecretNoteItem note = new ConversationSecretNoteItem(
				header(5_000L, false), new MutableLiveData<>("Bob"));
		note.setText("SECRET:10:hidden words");
		List<ConversationItem> all = new ArrayList<>(java.util.Arrays.asList(
				first, second, in, out, note, picture(false, 6_000L),
				picture(true, 7_000L)));
		for (int i = 2; i < all.size(); i++) adapter.add(all.get(i));
		idle();
		for (ConversationItem item : all) select(item);
		assertEquals(all.size(), selected());
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.drawsSelected(row));
			problems.addAll(SelectedLook.unreadable(row, 4.5, 4.5));
		}
		return problems;
	}

	@Test
	public void wordsOnASelectedMessageStayReadableInLight()
			throws Exception {
		start(AppCompatDelegate.MODE_NIGHT_NO);
		List<String> problems = unreadableWhenSelected();
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void wordsOnASelectedMessageStayReadableInDark() throws Exception {
		start(AppCompatDelegate.MODE_NIGHT_YES);
		List<String> problems = unreadableWhenSelected();
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void selectingAMessageWithAQuoteReactionAndPictureMovesNothing()
			throws Exception {
		ConversationMessageItem in = dressed(incoming(
				"see https://example.org/map", 3_000L));
		ConversationMessageItem photo = picture(false, 4_000L);
		ConversationMessageItem myPhoto = picture(true, 5_000L);
		adapter.add(in);
		adapter.add(photo);
		adapter.add(myPhoto);
		idle();
		List<List<String>> before = new ArrayList<>();
		for (int i = 2; i < 5; i++) before.add(SelectedLook.boxes(holder(i).itemView));

		select(in);
		select(photo);
		select(myPhoto);

		for (int i = 2; i < 5; i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.drawsSelected(row));
			assertEquals("row " + i + " keeps its size and layout",
					before.get(i - 2), SelectedLook.boxes(row));
		}
		View reaction = holder(2).itemView.findViewById(R.id.reactionsView);
		assertEquals(View.VISIBLE, reaction.getVisibility());
		assertEquals(View.VISIBLE, holder(2).itemView.findViewById(
				R.id.replyPreviewContainer).getVisibility());
		for (int i = 3; i < 5; i++) {
			RecyclerView images = holder(i).itemView.findViewById(
					R.id.imageList);
			ImageView image = images.getChildAt(0).findViewById(
					R.id.imageView);
			int[] state = image.getDrawableState();
			assertTrue("the picture follows the bubble",
					java.util.Arrays.binarySearch(sorted(state),
							android.R.attr.state_activated) >= 0);
			assertEquals("a missing picture is drawn like the words on it",
					color(i == 3 ? R.attr.zerionColorSelectionForeground
							: R.attr.zerionColorOnBubbleOut),
					ImageViewCompat.getImageTintList(image).getColorForState(
							state, 0));
		}
		ImageView preview = holder(2).itemView.findViewById(
				R.id.linkPreviewImage);
		assertEquals("the link preview picture darkens with it",
				color(R.attr.zerionColorMessageSelectedOwn),
				ImageViewCompat.getImageTintList(preview).getColorForState(
						SelectedLook.state(preview, true), 0));
		assertEquals(0, ImageViewCompat.getImageTintList(preview)
				.getColorForState(SelectedLook.state(preview, false), 0)
				>>> 24);
	}

	private static ConversationMessageItem document(boolean local,
			long time) {
		AttachmentHeader a = new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), "text/plain");
		return new ConversationMessageItem(local
				? R.layout.list_item_conversation_msg_out
				: R.layout.list_item_conversation_msg_in, header(time, local),
				new MutableLiveData<>("Bob"), Collections.singletonList(
				AttachmentItemsForTests.available(a, "txt")));
	}

	private List<String> hiddenDocumentIcons(int night) throws Exception {
		start(night);
		ConversationMessageItem theirs = document(false, 3_000L);
		ConversationMessageItem mine = document(true, 4_000L);
		adapter.add(theirs);
		adapter.add(mine);
		idle();
		List<String> problems = new ArrayList<>();
		for (boolean selected : new boolean[] {false, true}) {
			if (selected) {
				select(theirs);
				select(mine);
			}
			for (int i = 2; i < 4; i++) {
				View row = holder(i).itemView;
				assertEquals(selected, SelectedLook.drawsSelected(row));
				RecyclerView images = row.findViewById(R.id.imageList);
				ImageView icon = images.getChildAt(0).findViewById(
						R.id.imageView);
				int c = SelectedLook.iconColor(icon);
				int bubble = SelectedLook.painted(row.findViewById(
						R.id.layout));
				double r = ratio(c, bubble);
				if (r < 3.0) {
					problems.add(String.format(java.util.Locale.ROOT,
							"%s %s document icon #%08X on #%08X is %.2f:1",
							i == 2 ? "received" : "own",
							selected ? "selected" : "plain", c, bubble, r));
				}
			}
		}
		return problems;
	}

	@Test
	public void aDocumentIconStaysVisibleInLight() throws Exception {
		List<String> problems = hiddenDocumentIcons(
				AppCompatDelegate.MODE_NIGHT_NO);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void aDocumentIconStaysVisibleInDark() throws Exception {
		List<String> problems = hiddenDocumentIcons(
				AppCompatDelegate.MODE_NIGHT_YES);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static int[] sorted(int[] state) {
		int[] copy = state.clone();
		java.util.Arrays.sort(copy);
		return copy;
	}

	@Test
	public void threeSelectedMessagesAreEachToldAsSelected() throws Exception {
		ConversationMessageItem in = incoming("hello", 3_000L);
		ConversationMessageItem left = incoming("still here", 4_000L);
		adapter.add(in);
		adapter.add(left);
		idle();

		select(first);
		select(second);
		select(in);

		assertEquals(3, selected());
		assertEquals("3 selected", bar().getTitleView().getText().toString());
		for (int i = 0; i < 3; i++) {
			View row = holder(i).itemView;
			assertTrue(SelectedLook.toldSelected(row));
			assertTrue("the bubble TalkBack reads is selected",
					SelectedLook.toldSelected(row.findViewById(R.id.layout)));
			assertTrue(SelectedLook.drawsSelected(row));
		}
		View other = holder(3).itemView;
		assertFalse(SelectedLook.toldSelected(other.findViewById(R.id.layout)));
		assertFalse(SelectedLook.drawsSelected(other));

		SelectionBarTest.tap(holder(1).itemView.findViewById(R.id.text));
		assertEquals(2, selected());
		assertFalse(SelectedLook.toldSelected(
				holder(1).itemView.findViewById(R.id.layout)));
		assertFalse(SelectedLook.drawsSelected(holder(1).itemView));
	}

	private static int spoken(ShadowAccessibilityManager talkBack) {
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT
					|| e.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED
					|| (e.getContentChangeTypes()
					& (AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
					| AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
					| AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION))
					!= 0) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void eachChangeOfTheSelectionIsSaidOnceAndARebindSaysNothing()
			throws Exception {
		holder(1);
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);

		select(second);
		holder(1);
		assertEquals("the bar appearing says it once", 1, spoken(talkBack));

		select(first);
		holder(0);
		assertEquals("a second message is said once", 2, spoken(talkBack));

		adapter.notifyDataSetChanged();
		holder(0);
		holder(1);
		assertEquals("binding the rows again says nothing", 2,
				spoken(talkBack));
		assertTrue(SelectedLook.toldSelected(
				holder(0).itemView.findViewById(R.id.layout)));
	}

	@Test
	public void aCallEventCannotBeSelected() throws Exception {
		ConversationCallEventItem call = new ConversationCallEventItem(
				R.layout.list_item_conversation_call_event,
				header(3_000L, true), new MutableLiveData<>("Bob"),
				ConversationCallEventItem.CallEventType.CALL_END, "call",
				1_000L);
		adapter.add(call);
		idle();

		View row = holder(2).itemView;
		assertFalse(row.isLongClickable());
		assertFalse(row.findViewById(R.id.layout).isLongClickable());
		assertFalse(row.performLongClick());
		idle();
		assertFalse("a call event is never selected, so it needs no "
				+ "highlight", bar().isShowing());
	}

	@Test
	public void replyIsOfferedAndQuotesTheMessage() throws Exception {
		select(first);
		assertTrue(bar().shownActions().contains(SelectionAction.REPLY));

		assertTrue(bar().perform(SelectionAction.REPLY));
		idle();

		assertFalse(bar().isShowing());
		assertEquals(0, selected());
		TextInputView input = (TextInputView) field(activity, "textInputView");
		assertSame(first, input.getReplyingToItem());
	}

	@Test
	public void severalMessagesOfferOnlyActionsForSeveral() throws Exception {
		select(first);
		select(second);

		assertEquals(2, selected());
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.FORWARD), bar().shownActions());
		TextView title = bar().getTitleView();
		assertNotNull(title);
		assertEquals("2 selected", title.getText().toString());
	}

	@Test
	public void aDisappearingMessageIsNeverForwarded() throws Exception {
		ConversationMessageItem timed = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out,
				header(3_000L, true, 60L * 60L * 1000L),
				new MutableLiveData<>("Bob"));
		timed.setText("gone soon");
		adapter.add(timed);
		idle();

		select(timed);
		assertTrue(bar().shownActions().contains(SelectionAction.COPY));
		assertFalse("its timer would not travel with it",
				bar().shownActions().contains(SelectionAction.FORWARD));

		select(first);
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE),
				bar().shownActions());
	}

	@Test
	public void severalMessagesAreCopiedInChatOrder() throws Exception {
		select(second);
		select(first);

		assertTrue(bar().perform(SelectionAction.COPY));
		idle();

		ClipboardManager cm = (ClipboardManager) activity.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("first\nsecond", cm.getPrimaryClip().getItemAt(0)
				.getText().toString());
	}

	@Test
	public void aSecretNoteCanOnlyBeDeleted() throws Exception {
		ConversationSecretNoteItem note = new ConversationSecretNoteItem(
				header(3_000L, false), new MutableLiveData<>("Bob"));
		note.setText("SECRET:10:hidden words");
		adapter.add(note);
		idle();

		select(note);

		assertEquals("a secret note is never copied, forwarded or quoted",
				EnumSet.of(SelectionAction.DELETE), bar().shownActions());
	}

	@Test
	public void aSecretNoteCannotBeSwipedIntoAReply() throws Exception {
		ConversationSecretNoteItem note = new ConversationSecretNoteItem(
				header(3_000L, false), new MutableLiveData<>("Bob"));
		note.setText("SECRET:10:hidden words");
		adapter.add(note);
		idle();
		SwipeToReplyCallback swipe =
				(SwipeToReplyCallback) field(activity, "swipeCallback");
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();

		assertEquals("an unopened secret note is never quoted", 0,
				swipe.getMovementFlags(list, holder(2)));
		assertTrue(swipe.getMovementFlags(list, holder(0)) != 0);
	}

	private ConversationMessageItem stillLoading() {
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_in,
				header(3_000L, false), new MutableLiveData<>("Bob"));
		adapter.add(item);
		idle();
		return item;
	}

	private void textArrives(ConversationItem item, String text)
			throws Exception {
		java.lang.reflect.Method m = ConversationActivity.class
				.getDeclaredMethod("displayMessageText", MessageId.class,
						String.class);
		m.setAccessible(true);
		m.invoke(activity, item.getId(), text);
		idle();
	}

	@Test
	public void aMessageWhoseTextIsStillLoadingCanOnlyBeDeleted()
			throws Exception {
		ConversationMessageItem loading = stillLoading();
		SwipeToReplyCallback swipe =
				(SwipeToReplyCallback) field(activity, "swipeCallback");
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		assertEquals("it may still turn out to be a secret note", 0,
				swipe.getMovementFlags(list, holder(2)));

		select(loading);

		assertEquals(EnumSet.of(SelectionAction.DELETE),
				bar().shownActions());
	}

	@Test
	public void theBarFollowsTextThatArrivesAfterTheMessage()
			throws Exception {
		ConversationMessageItem loading = stillLoading();
		select(loading);
		assertTrue(bar().getActionView(SelectionAction.COPY) == null);

		textArrives(loading, "see you at ten");

		assertTrue("the bar offers what the loaded text allows",
				bar().getActionView(SelectionAction.COPY) != null);
		assertTrue(bar().getActionView(SelectionAction.REPLY) != null);
	}

	@Test
	public void aMessageThatTurnsOutToBeASecretNoteStaysDeleteOnly()
			throws Exception {
		ConversationMessageItem loading = stillLoading();
		select(loading);

		textArrives(loading, "SECRET:10:hidden words");

		assertTrue(adapter.getItemAt(2) instanceof ConversationSecretNoteItem);
		assertEquals(EnumSet.of(SelectionAction.DELETE),
				bar().shownActions());
		assertTrue(bar().getActionView(SelectionAction.REPLY) == null);
		assertTrue(bar().getActionView(SelectionAction.DELETE) != null);
		SwipeToReplyCallback swipe =
				(SwipeToReplyCallback) field(activity, "swipeCallback");
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		assertEquals(0, swipe.getMovementFlags(list, holder(2)));
	}

	@Test
	public void messageInfoSaysWhenItWasSentAndHowFarItGot()
			throws Exception {
		select(first);
		assertTrue(bar().perform(SelectionAction.INFO));
		idle();

		AlertDialog dialog = (AlertDialog) org.robolectric.shadows
				.ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		TextView message = dialog.findViewById(android.R.id.message);
		assertNotNull(message);
		String text = message.getText().toString();
		assertTrue(text, text.contains(activity.getString(
				R.string.message_info_sent)));
		assertTrue(text, text.contains(activity.getString(
				R.string.message_state_sent)));
		assertFalse(bar().isShowing());
	}

	@Test
	public void backClearsTheSelection() throws Exception {
		select(first);

		SelectionBarTest.pressBack(activity);

		assertFalse(bar().isShowing());
		assertEquals(0, selected());
		assertFalse(activity.isFinishing());
	}

	private static final String INVITE =
			"zerion://channel/abcdefghijklmnopqrstuvwxyz0123456789";

	private TextView textOf(ConversationMessageItem item, int position)
			throws Exception {
		adapter.add(item);
		idle();
		TextView text = holder(position).itemView.findViewById(R.id.text);
		assertNotNull(text);
		assertNotNull("the text is link aware", text.getMovementMethod());
		while (nextActivity() != null) {
			idle();
		}
		return text;
	}

	private Intent nextActivity() {
		return shadowOf((android.app.Application) activity.getApplication())
				.getNextStartedActivity();
	}

	@Test
	public void aLongPressOnAChannelLinkSelectsTheMessageWithoutOpeningIt()
			throws Exception {
		TextView text = textOf(item(INVITE, 3_000L), 2);

		SelectionBarTest.longPress(text);

		assertTrue(bar().isShowing());
		assertEquals(1, selected());
		assertTrue(holder(2).itemView.isActivated());
		assertNull("the long press does not open the link", nextActivity());
	}

	@Test
	public void aTapOnAChannelLinkStillOpensIt() throws Exception {
		TextView text = textOf(item(INVITE, 3_000L), 2);

		SelectionBarTest.tap(text);

		Intent opened = nextActivity();
		assertNotNull(opened);
		assertEquals(
				com.professor.zerion.android.channel.ChannelInviteHandlerActivity
						.class.getName(),
				opened.getComponent().getClassName());
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void aLongPressOnAWebLinkSelectsTheMessageWithoutOpeningIt()
			throws Exception {
		TextView text = textOf(item("https://example.org/map", 3_000L), 2);

		SelectionBarTest.longPress(text);

		assertTrue(bar().isShowing());
		assertEquals(1, selected());
		assertNull("the long press does not ask about the link",
				org.robolectric.shadows.ShadowDialog.getLatestDialog());

		bar().close();
		idle();
		SelectionBarTest.tap(text);
		assertNotNull("a tap still asks about the link",
				org.robolectric.shadows.ShadowDialog.getLatestDialog());
		assertFalse(bar().isShowing());
	}

	@Test
	public void aTapOnTheTextWhileSelectingTogglesTheMessage()
			throws Exception {
		select(first);
		TextView text = holder(1).itemView.findViewById(R.id.text);
		assertNotNull(text);

		SelectionBarTest.tap(text);
		assertEquals("the tapped message joins the selection", 2,
				selected());
		assertTrue(holder(1).itemView.isActivated());

		SelectionBarTest.tap(text);
		assertEquals("a second tap lets it go", 1, selected());
		assertTrue(bar().isShowing());
	}

	@Test
	public void aTapOnALinkWhileSelectingTogglesTheMessageInsteadOfOpeningIt()
			throws Exception {
		TextView text = textOf(item(INVITE, 3_000L), 2);
		select(first);

		SelectionBarTest.tap(text);

		assertEquals(2, selected());
		assertTrue(holder(2).itemView.isActivated());
		assertNull("the link does not open while selecting", nextActivity());
	}

	private static void dismissDialogs() {
		android.app.Dialog d =
				org.robolectric.shadows.ShadowDialog.getLatestDialog();
		if (d != null && d.isShowing()) d.dismiss();
		idle();
	}
}
