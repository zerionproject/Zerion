package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ViewOnceBubbleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context ctx;
	private ConversationListener listener;
	private ConversationAdapter adapter;

	@Before
	public void setUp() {
		ctx = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		listener = mock(ConversationListener.class);
		adapter = new ConversationAdapter(ctx, listener,
				mock(org.zerionproject.app.api.attachment.AttachmentReader.class),
				Runnable::run, mock(com.professor.zerion.android.conversation
				.voice.VoiceMemoKeys.class));
	}

	private static PrivateMessageHeader header(boolean local, String type,
			boolean viewOnce, boolean opened) {
		GroupId g = new GroupId(getRandomId());
		AttachmentHeader a =
				new AttachmentHeader(g, new MessageId(getRandomId()), type);
		return new PrivateMessageHeader(new MessageId(getRandomId()), g,
				1_000L, local, true, true, false, false,
				Collections.singletonList(a), -1L, null, false, false,
				viewOnce, opened);
	}

	private ConversationItem visit(PrivateMessageHeader h,
			ConversationVisitor.AttachmentCache cache) {
		ConversationVisitor visitor = new ConversationVisitor(ctx,
				m -> "a caption that must not show", cache,
				new MutableLiveData<>("Bob"), null);
		return h.accept(visitor);
	}

	private ConversationItemViewHolder bound(ConversationItem item) {
		adapter.setItems(Collections.singletonList(item));
		ConversationItemViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx), adapter.getItemViewType(0));
		adapter.onBindViewHolder(h, 0);
		return h;
	}

	private static List<String> shownText(View v) {
		List<String> out = new ArrayList<>();
		collect(v, out);
		return out;
	}

	private static void collect(View v, List<String> out) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null && t.length() > 0) out.add(t.toString());
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}

	private static int pictures(View v) {
		int n = 0;
		if (v instanceof RecyclerView) n++;
		if (v instanceof ImageView && ((ImageView) v).getDrawable()
				instanceof android.graphics.drawable.BitmapDrawable) {
			n++;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				n += pictures(g.getChildAt(i));
			}
		}
		return n;
	}

	@Test
	public void aViewOnceMessageBecomesATileWithoutLoadingTheMedia() {
		ConversationVisitor.AttachmentCache cache =
				mock(ConversationVisitor.AttachmentCache.class);

		ConversationItem item = visit(header(false, "image/jpeg", true,
				false), cache);

		assertTrue(item instanceof ConversationViewOnceItem);
		assertNull(item.getText());
		verify(cache, never()).getAttachmentItems(any());
	}

	@Test
	public void anOrdinaryPhotoIsStillShownAsAPhoto() {
		ConversationVisitor.AttachmentCache cache =
				h -> Collections.emptyList();

		ConversationItem item = visit(header(false, "image/jpeg", false,
				false), cache);

		assertTrue(item instanceof ConversationMessageItem);
	}

	@Test
	public void anUnopenedPhotoSaysTapToViewOnceAndShowsNoPicture() {
		ConversationItemViewHolder h = bound(new ConversationViewOnceItem(
				header(false, "image/jpeg", true, false),
				new MutableLiveData<>("Bob")));

		List<String> shown = shownText(h.itemView);
		assertTrue(shown.contains(ctx.getString(R.string.view_once_photo)));
		assertTrue(shown.contains(ctx.getString(R.string.view_once_tap)));
		assertEquals(0, pictures(h.itemView));
	}

	@Test
	public void tappingAnUnopenedTileAsksToOpenIt() {
		ConversationViewOnceItem item = new ConversationViewOnceItem(
				header(false, "video/mp4", true, false),
				new MutableLiveData<>("Bob"));
		ConversationItemViewHolder h = bound(item);
		assertTrue(shownText(h.itemView).contains(
				ctx.getString(R.string.view_once_video)));

		h.itemView.findViewById(R.id.layout).performClick();

		verify(listener).onViewOnceClicked(item);
	}

	@Test
	public void anOpenedTileSaysViewedAndCannotBeOpenedAgain() {
		ConversationViewOnceItem item = new ConversationViewOnceItem(
				header(false, "image/jpeg", true, true),
				new MutableLiveData<>("Bob"));
		ConversationItemViewHolder h = bound(item);

		assertTrue(shownText(h.itemView).contains(
				ctx.getString(R.string.view_once_viewed)));
		assertFalse(shownText(h.itemView).contains(
				ctx.getString(R.string.view_once_tap)));
		h.itemView.findViewById(R.id.layout).performClick();

		verify(listener, never()).onViewOnceClicked(any());
	}

	@Test
	public void theSendersCopyShowsAPlaceholderUntilItIsViewed() {
		ConversationViewOnceItem sent = new ConversationViewOnceItem(
				header(true, "image/jpeg", true, false),
				new MutableLiveData<>("Bob"));
		ConversationItemViewHolder h = bound(sent);
		List<String> shown = shownText(h.itemView);
		assertTrue(shown.contains(ctx.getString(R.string.view_once_photo)));
		assertFalse(shown.contains(ctx.getString(R.string.view_once_tap)));
		assertFalse(shown.contains(ctx.getString(R.string.view_once_viewed)));
		assertEquals(0, pictures(h.itemView));
		h.itemView.findViewById(R.id.layout).performClick();
		verify(listener, never()).onViewOnceClicked(any());

		ConversationViewOnceItem viewed = new ConversationViewOnceItem(
				header(true, "image/jpeg", true, true),
				new MutableLiveData<>("Bob"));
		assertTrue(shownText(bound(viewed).itemView).contains(
				ctx.getString(R.string.view_once_viewed)));
	}

	@Test
	public void aReceiptTurnsTheTileIntoViewed() {
		PrivateMessageHeader h = header(true, "image/jpeg", true, false);
		ConversationViewOnceItem shown =
				new ConversationViewOnceItem(h, new MutableLiveData<>("Bob"));
		PrivateMessageHeader opened = new PrivateMessageHeader(h.getId(),
				h.getGroupId(), h.getTimestamp(), true, true, true, true,
				false, h.getAttachmentHeaders(), -1L, null, false, false,
				true, true);

		int change = shown.updateFrom(new ConversationViewOnceItem(opened,
				new MutableLiveData<>("Bob")));

		assertEquals(ConversationItem.CONTENT_CHANGED, change);
		assertTrue(shown.isOpened());
	}

	@Test
	public void allMediaListsNoViewOnceMedia() {
		assertTrue(AllMediaActivity.listed(header(false, "image/jpeg", true,
				false)).isEmpty());
		assertTrue(AllMediaActivity.listed(header(true, "video/mp4", true,
				false)).isEmpty());
		assertEquals(1, AllMediaActivity.listed(header(false, "image/jpeg",
				false, false)).size());
	}

	@Test
	public void aViewOnceTileCannotBeQuotedCopiedOrForwarded() {
		ConversationViewOnceItem item = new ConversationViewOnceItem(
				header(false, "image/jpeg", true, false),
				new MutableLiveData<>("Bob"));
		item.setText("caption");

		assertNull(item.getText());
		assertFalse(ConversationActivity.canQuote(item));
		assertNull(ConversationActivity.copyableText(item));
	}
}
