package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.message.TestPendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupPostListWorkTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int POSTS = 20;

	private static final class Counted extends GroupTrPost {

		static final AtomicInteger bodies = new AtomicInteger();
		static final AtomicInteger ids = new AtomicInteger();

		Counted(byte[] body, long timestamp, byte[] id) {
			super(new byte[32], new byte[32], "Alice", body, timestamp, 1L,
					true, 0L, timestamp, id);
		}

		@Override
		public byte[] getBody() {
			bodies.incrementAndGet();
			return super.getBody();
		}

		@Nullable
		@Override
		public byte[] getPostId() {
			ids.incrementAndGet();
			return super.getPostId();
		}
	}

	private static final class Stub implements GroupTrPostAdapter.Callback {

		boolean mine = true;
		int senderBinds = 0;
		final Set<GroupTrPost> selected = new HashSet<>();

		@Override
		public boolean isMine(GroupTrPost p) {
			return mine;
		}

		@Override
		public void bindSender(TextView tv, GroupTrPost p) {
			senderBinds++;
			tv.setText(p.getSenderName());
		}

		@Override
		public void onImageClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
		}

		@Override
		public void onVideoClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
		}

		@Override
		public void onVoiceClick(AppCompatImageButton btn, byte[] audio) {
		}

		@Nullable
		@Override
		public Bitmap videoThumb(byte[] videoBytes) {
			return null;
		}

		@Override
		public String formatTime(long ts) {
			return "";
		}

		@Override
		public String formatDuration(long ms) {
			return "";
		}

		@Override
		public void onPostLongClick(GroupTrPost p) {
		}

		@Override
		public boolean onPostClick(GroupTrPost p) {
			return false;
		}

		@Override
		public boolean isSelected(GroupTrPost p) {
			return selected.contains(p);
		}

		@Override
		public boolean selecting() {
			return !selected.isEmpty();
		}

		@Override
		public String quoteAuthor(GroupTrPost p) {
			return "Alice";
		}

		@Override
		public void onQuoteClick(byte[] postId) {
		}
	}

	private final List<String> changes = new ArrayList<>();
	private Stub stub;
	private GroupTrPostAdapter adapter;
	private FrameLayout parent;

	@Before
	public void setUp() {
		TestPendingSends.reset();
		Counted.bodies.set(0);
		Counted.ids.set(0);
		stub = new Stub();
		adapter = new GroupTrPostAdapter(stub);
		adapter.registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onChanged() {
						changes.add("all");
					}

					@Override
					public void onItemRangeChanged(int start, int count) {
						changes.add("changed " + start + "+" + count);
					}

					@Override
					public void onItemRangeChanged(int start, int count,
							@Nullable Object payload) {
						changes.add("changed " + start + "+" + count
								+ (payload == null ? "" : " partly"));
					}

					@Override
					public void onItemRangeInserted(int start, int count) {
						changes.add("inserted " + start + "+" + count);
					}

					@Override
					public void onItemRangeRemoved(int start, int count) {
						changes.add("removed " + start + "+" + count);
					}

					@Override
					public void onItemRangeMoved(int from, int to, int count) {
						changes.add("moved " + from + ">" + to);
					}
				});
		Context ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		parent = new FrameLayout(ctx);
	}

	private static byte[] id(int n) {
		byte[] id = new byte[32];
		id[0] = (byte) n;
		id[1] = (byte) (n >> 8);
		return id;
	}

	private static List<GroupTrPost> posts(int n) {
		List<GroupTrPost> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(new Counted(("post " + i + " see zerion.chat")
					.getBytes(StandardCharsets.UTF_8), 1_000L + i, id(i)));
		}
		return out;
	}

	private List<GroupTrPostAdapter.PostHolder> bindAll() {
		List<GroupTrPostAdapter.PostHolder> holders = new ArrayList<>();
		for (int i = 0; i < adapter.getItemCount(); i++) {
			GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(
					parent, adapter.getItemViewType(i));
			adapter.onBindViewHolder(h, i);
			holders.add(h);
		}
		return holders;
	}

	private PendingSends.Entry pendingEntry(String text) {
		byte[] body = GroupTrBody.encodeText(text);
		return PendingSends.get().add("group:test", text,
				new PendingGroupPost.Send(body, null, text), body.length,
				true, () -> PendingSends.Result.failed(null));
	}

	private static PendingGroupPost pendingRow(PendingSends.Entry e) {
		PendingGroupPost.Send s = (PendingGroupPost.Send) e.getPayload();
		return new PendingGroupPost(new byte[32], new byte[32], "", e,
				s.body);
	}

	@Test
	public void bindingAnUnchangedPostAgainDoesNotParseItsBody() {
		adapter.setPosts(posts(POSTS));
		List<GroupTrPostAdapter.PostHolder> holders = bindAll();
		GroupTrBody.Parsed first = adapter.rowAt(0).parsed();
		Counted.bodies.set(0);

		for (int i = 0; i < holders.size(); i++) {
			adapter.onBindViewHolder(holders.get(i), i);
		}

		assertEquals("binding " + POSTS + " unchanged posts read their bodies "
				+ Counted.bodies.get() + " times", 0, Counted.bodies.get());
		assertSame(first, adapter.rowAt(0).parsed());
		assertEquals("post 0 see zerion.chat", ((TextView) holders.get(0)
				.itemView.findViewById(R.id.postText)).getText().toString());
	}

	@Test
	public void aReloadWithTheSamePostsReadsEachBodyOnceAndRebindsNothing() {
		adapter.setPosts(posts(POSTS));
		bindAll();
		changes.clear();
		Counted.bodies.set(0);

		adapter.apply(adapter.prepare(posts(POSTS)));

		assertEquals("the reload read bodies " + Counted.bodies.get()
				+ " times for " + POSTS + " posts", POSTS,
				Counted.bodies.get());
		assertEquals(Collections.emptyList(), changes);
		assertEquals(POSTS, adapter.getItemCount());
	}

	@Test
	public void theDifferenceIsPreparedAwayFromTheMainThreadAndAppliedWithoutReadingBodies()
			throws Exception {
		adapter.setPosts(posts(POSTS));
		bindAll();
		List<GroupTrPost> grown = posts(POSTS + 1);
		AtomicReference<GroupTrPostAdapter.Update> prepared =
				new AtomicReference<>();
		Thread io = new Thread(() -> prepared.set(adapter.prepare(grown)));
		io.start();
		io.join(10_000);
		assertNotNull(prepared.get());
		changes.clear();
		Counted.bodies.set(0);

		assertTrue("the prepared difference was used",
				adapter.apply(prepared.get()));

		assertEquals(0, Counted.bodies.get());
		assertEquals(Arrays.asList("inserted " + POSTS + "+1"), changes);
		assertEquals(POSTS + 1, adapter.getItemCount());
	}

	@Test
	public void anUpdatePreparedAgainstAnOlderListStillEndsUpRight() {
		adapter.setPosts(posts(POSTS));
		GroupTrPostAdapter.Update stale = adapter.prepare(posts(POSTS + 2));
		PendingSends.Entry e = pendingEntry("later");
		adapter.setPending(Collections.singletonList(pendingRow(e)));
		assertEquals(POSTS + 1, adapter.getItemCount());

		assertFalse("the list moved on, so the difference was worked out again",
				adapter.apply(stale));

		assertEquals(POSTS + 3, adapter.getItemCount());
		assertTrue(adapter.postAt(POSTS + 2) instanceof PendingGroupPost);
		assertTrue(adapter.hasStored(GroupTrPostAdapter.key(
				posts(POSTS + 2).get(POSTS + 1))));
	}

	@Test
	public void postsKeepTheirIdsAcrossReloads() {
		assertTrue(adapter.hasStableIds());
		adapter.setPosts(posts(POSTS));
		long second = adapter.getItemId(1);
		long last = adapter.getItemId(POSTS - 1);
		PendingSends.Entry e = pendingEntry("mine");
		adapter.setPending(Collections.singletonList(pendingRow(e)));
		long pending = adapter.getItemId(POSTS);
		assertNotEquals(second, pending);

		List<GroupTrPost> reordered = new ArrayList<>(posts(POSTS));
		reordered.add(0, new Counted(GroupTrBody.encodeText("older"), 1L,
				id(900)));
		adapter.apply(adapter.prepare(reordered));

		assertEquals(second, adapter.getItemId(2));
		assertEquals(last, adapter.getItemId(POSTS));
		assertEquals(pending, adapter.getItemId(POSTS + 1));
		Set<Long> all = new HashSet<>();
		for (int i = 0; i < adapter.getItemCount(); i++) {
			all.add(adapter.getItemId(i));
		}
		assertEquals(adapter.getItemCount(), all.size());
	}

	@Test
	public void selectingAPostRebindsOnlyItsMark() {
		stub.mine = false;
		adapter.setPosts(posts(POSTS));
		List<GroupTrPostAdapter.PostHolder> holders = bindAll();
		assertEquals(POSTS, stub.senderBinds);
		GroupTrPost chosen = adapter.postAt(3);
		String key = GroupTrPostAdapter.key(chosen);
		stub.selected.add(chosen);
		TextView body = holders.get(3).itemView.findViewById(R.id.postText);
		body.setText("left alone");
		changes.clear();
		stub.senderBinds = 0;
		Counted.bodies.set(0);

		adapter.refreshSelection(key);
		assertEquals(Arrays.asList("changed 3+1 partly"), changes);
		adapter.onBindViewHolder(holders.get(3), 3, Collections.singletonList(
				GroupTrPostAdapter.PAYLOAD_SELECTION));

		assertTrue(holders.get(3).itemView.isActivated());
		assertEquals("a selection change bound the sender again", 0,
				stub.senderBinds);
		assertEquals(0, Counted.bodies.get());
		assertEquals("left alone", body.getText().toString());

		stub.selected.clear();
		adapter.onBindViewHolder(holders.get(3), 3, Collections.singletonList(
				GroupTrPostAdapter.PAYLOAD_SELECTION));
		assertFalse(holders.get(3).itemView.isActivated());
	}

	@Test
	public void aPendingStateChangeRebindsOnlyTheStateLine() {
		adapter.setPosts(posts(2));
		PendingSends.Entry e = pendingEntry("hello");
		adapter.setPending(Collections.singletonList(pendingRow(e)));
		List<GroupTrPostAdapter.PostHolder> holders = bindAll();
		assertEquals(MessageState.SENDING, e.getState());
		PendingSends.get().start(e, Runnable::run);
		assertEquals(MessageState.NOT_SENT, e.getState());
		TextView body = holders.get(2).itemView.findViewById(R.id.postText);
		assertEquals("hello", body.getText().toString());
		body.setText("left alone");
		changes.clear();

		adapter.setPending(Collections.singletonList(pendingRow(e)));
		assertEquals(Arrays.asList("changed 2+1 partly"), changes);
		adapter.onBindViewHolder(holders.get(2), 2, Collections.singletonList(
				GroupTrPostAdapter.PAYLOAD_STATE));

		assertEquals("left alone", body.getText().toString());
		TextView meta = holders.get(2).itemView.findViewById(R.id.postTime);
		String notSent = parent.getContext().getString(
				R.string.message_state_not_sent);
		assertTrue(meta.getText().toString(),
				meta.getText().toString().endsWith(notSent));
		assertNotNull(holders.get(2).itemView.findViewById(R.id.bubble)
				.getForeground());
	}

	@Test
	public void bindingMediaRowsKeepsNoPayloadCopies() {
		List<GroupTrPost> media = new ArrayList<>();
		for (int i = 0; i < POSTS; i++) {
			media.add(new Counted(GroupTrBody.encodeImage(new byte[64],
					"image/jpeg"), 1_000L + i, id(i)));
		}
		media.add(new Counted(GroupTrBody.encodeText("words"), 5_000L,
				id(500)));
		adapter.setPosts(media);

		bindAll();

		int retained = 0;
		for (int i = 0; i < POSTS; i++) {
			if (adapter.rowAt(i).retainsPayload()) retained++;
		}
		assertEquals("rows kept " + retained + " payload copies", 0, retained);
		assertSame("text stays parsed once", adapter.rowAt(POSTS).parsed(),
				adapter.rowAt(POSTS).parsed());
	}

	@Test
	public void idsOfPostsThatLeftAreForgotten() {
		adapter.setPosts(posts(POSTS));
		for (int i = 0; i < POSTS; i++) adapter.getItemId(i);
		long first = adapter.getItemId(0);
		assertEquals(POSTS, adapter.idCount());

		adapter.setPosts(posts(5));

		assertEquals(5, adapter.idCount());
		assertEquals(first, adapter.getItemId(0));
	}

	@Test
	public void aQuoteFindsItsTargetWithoutHexingEveryPost() {
		List<GroupTrPost> all = new ArrayList<>(posts(POSTS));
		all.add(new Counted(GroupTrBody.encodeReply(id(POSTS - 1), "agreed"),
				5_000L, id(500)));
		adapter.setPosts(all);
		Counted.ids.set(0);

		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(parent,
				adapter.getItemViewType(POSTS));
		adapter.onBindViewHolder(h, POSTS);

		assertEquals("binding one quote asked every post for its id "
				+ Counted.ids.get() + " times", 0, Counted.ids.get());
		TextView quoted = h.itemView.findViewById(R.id.quoteText);
		assertEquals("post " + (POSTS - 1) + " see zerion.chat",
				quoted.getText().toString());
	}
}
