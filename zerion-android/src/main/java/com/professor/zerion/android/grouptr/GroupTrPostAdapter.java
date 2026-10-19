package com.professor.zerion.android.grouptr;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;
import com.professor.zerion.android.accessibility.RowReading;
import com.professor.zerion.android.accessibility.RowStates;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.MessageStates;
import com.professor.zerion.android.selection.RowActions;
import com.professor.zerion.android.selection.SelectedFill;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionRows;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.zerionproject.core.util.StringUtils.toHexString;

class GroupTrPostAdapter
		extends RecyclerView.Adapter<GroupTrPostAdapter.PostHolder> {

	interface Callback {
		boolean isMine(GroupTrPost p);

		void bindSender(TextView tv, GroupTrPost p);

		void onImageClick(GroupTrPost p, byte[] bytes, @Nullable String mime);

		void onVideoClick(GroupTrPost p, byte[] bytes, @Nullable String mime);

		void onVoiceClick(AppCompatImageButton btn, byte[] audio);

		@Nullable
		Bitmap videoThumb(byte[] videoBytes);

		String formatTime(long ts);

		String formatDuration(long ms);

		void onPostLongClick(GroupTrPost p);

		boolean onPostClick(GroupTrPost p);

		boolean isSelected(GroupTrPost p);

		boolean selecting();

		String quoteAuthor(GroupTrPost p);

		void onQuoteClick(byte[] postId);

		default void onRetry(PendingGroupPost p) {
		}

		default void onDiscard(PendingGroupPost p) {
		}

		default void onEdit(PendingGroupPost p) {
		}

		default boolean composerShown() {
			return true;
		}

		default Set<SelectionAction> rowActions(GroupTrPost p) {
			return RowActions.none();
		}

		default boolean onRowAction(GroupTrPost p, SelectionAction action) {
			return false;
		}
	}

	static final Object PAYLOAD_SELECTION = new Object();
	static final Object PAYLOAD_STATE = new Object();
	static final Object PAYLOAD_NAME = new Object();

	static final class Update {
		private final List<GroupPostRow> against;
		private final List<GroupPostRow> rows;
		private final DiffUtil.DiffResult diff;

		private Update(List<GroupPostRow> against, List<GroupPostRow> rows,
				DiffUtil.DiffResult diff) {
			this.against = against;
			this.rows = rows;
			this.diff = diff;
		}
	}

	private static void showLinkDialog(android.view.View v, String url) {
		android.content.Context c = v.getContext();
		if (c instanceof androidx.fragment.app.FragmentActivity) {
			com.professor.zerion.android.widget.LinkDialogFragment f =
					com.professor.zerion.android.widget.LinkDialogFragment
							.newInstance(url);
			f.show(((androidx.fragment.app.FragmentActivity) c)
					.getSupportFragmentManager(), f.getUniqueTag());
		}
	}

	static String excerpt(android.content.Context ctx, GroupTrPost p) {
		return excerpt(ctx, GroupPostRow.of(p));
	}

	static String excerpt(android.content.Context ctx, GroupPostRow r) {
		switch (r.kind) {
			case VOICE:
				return ctx.getString(R.string.voice_message);
			case IMAGE:
				return ctx.getString(R.string.image);
			case VIDEO:
				return ctx.getString(R.string.video);
			default:
				String text = r.text();
				String t = text == null ? "" : text;
				int nl = t.indexOf('\n');
				return nl >= 0 ? t.substring(0, nl) : t;
		}
	}

	private static final int TEXT_IN = 0;
	private static final int TEXT_OUT = 1;
	private static final int VOICE_IN = 2;
	private static final int VOICE_OUT = 3;
	private static final int IMAGE_IN = 4;
	private static final int IMAGE_OUT = 5;
	private static final int VIDEO_IN = 6;
	private static final int VIDEO_OUT = 7;

	private final Callback cb;
	private volatile List<GroupPostRow> rows = Collections.emptyList();
	private volatile Map<String, GroupPostRow> byKey = Collections.emptyMap();
	private volatile Map<String, MessageState> deliveries =
			Collections.emptyMap();
	private final Map<String, Long> statesAt = new HashMap<>();
	private long fullStatesAt = 0L;
	private final Map<String, Long> ids = new HashMap<>();
	private long nextId = 1;

	private static final java.util.concurrent.Executor DECODE_EXECUTOR =
			java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
				Thread t = new Thread(r, "GroupTrDecode");
				t.setDaemon(true);
				return t;
			});

	private final com.professor.zerion.android.settings.MessageStyle.Source messageStyle =
			new com.professor.zerion.android.settings.MessageStyle.Source();

	GroupTrPostAdapter(Callback cb) {
		this.cb = cb;
		setHasStableIds(true);
	}

	void setMessageStyle(com.professor.zerion.android.settings.MessageStyle style) {
		if (messageStyle.update(style)) notifyDataSetChanged();
	}

	Update prepare(List<GroupTrPost> posts) {
		List<GroupPostRow> against = rows;
		Map<String, GroupPostRow> known = byKey;
		List<GroupPostRow> next = new ArrayList<>(against.size());
		for (GroupTrPost p : posts) next.add(rowFor(p, known));
		for (GroupPostRow r : against) {
			if (r.pending) next.add(r);
		}
		return new Update(against, next, diff(against, next));
	}

	boolean apply(Update update) {
		List<GroupPostRow> current = rows;
		if (current == update.against) {
			publish(update.rows);
			update.diff.dispatchUpdatesTo(this);
			return true;
		}
		List<GroupPostRow> next = new ArrayList<>(update.rows.size());
		for (GroupPostRow r : update.rows) {
			if (!r.pending) next.add(r);
		}
		for (GroupPostRow r : current) {
			if (r.pending) next.add(r);
		}
		DiffUtil.DiffResult diff = diff(current, next);
		publish(next);
		diff.dispatchUpdatesTo(this);
		return false;
	}

	void setPosts(List<GroupTrPost> newPosts) {
		apply(prepare(newPosts));
	}

	void setPending(List<PendingGroupPost> pending) {
		List<GroupPostRow> current = rows;
		List<GroupPostRow> stored = new ArrayList<>(current.size());
		List<GroupPostRow> before = new ArrayList<>();
		for (GroupPostRow r : current) {
			if (r.pending) before.add(r);
			else stored.add(r);
		}
		Map<String, GroupPostRow> known = byKey;
		List<GroupPostRow> after = new ArrayList<>(pending.size());
		for (PendingGroupPost p : pending) after.add(rowFor(p, known));
		DiffUtil.DiffResult diff = diff(before, after);
		List<GroupPostRow> next = new ArrayList<>(stored.size()
				+ after.size());
		next.addAll(stored);
		next.addAll(after);
		publish(next);
		diff.dispatchUpdatesTo(offset(stored.size()));
	}

	private static GroupPostRow rowFor(GroupTrPost p,
			Map<String, GroupPostRow> known) {
		GroupPostRow kept = known.get(GroupPostRow.key(p));
		if (kept != null && kept.shows(p)) return kept;
		return GroupPostRow.of(p);
	}

	private static DiffUtil.DiffResult diff(List<GroupPostRow> old,
			List<GroupPostRow> next) {
		return DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override
			public int getOldListSize() {
				return old.size();
			}

			@Override
			public int getNewListSize() {
				return next.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPos, int newPos) {
				return old.get(oldPos).sameRow(next.get(newPos));
			}

			@Override
			public boolean areContentsTheSame(int oldPos, int newPos) {
				return old.get(oldPos).sameShown(next.get(newPos));
			}

			@Nullable
			@Override
			public Object getChangePayload(int oldPos, int newPos) {
				GroupPostRow a = old.get(oldPos);
				GroupPostRow b = next.get(newPos);
				return a.pending && b.pending ? PAYLOAD_STATE : null;
			}
		});
	}

	private ListUpdateCallback offset(int by) {
		return new ListUpdateCallback() {
			@Override
			public void onInserted(int position, int count) {
				notifyItemRangeInserted(by + position, count);
			}

			@Override
			public void onRemoved(int position, int count) {
				notifyItemRangeRemoved(by + position, count);
			}

			@Override
			public void onMoved(int fromPosition, int toPosition) {
				notifyItemMoved(by + fromPosition, by + toPosition);
			}

			@Override
			public void onChanged(int position, int count,
					@Nullable Object payload) {
				notifyItemRangeChanged(by + position, count, payload);
			}
		};
	}

	private void publish(List<GroupPostRow> next) {
		Map<String, GroupPostRow> keyed = new HashMap<>(next.size() * 2);
		for (GroupPostRow r : next) keyed.put(r.key, r);
		byKey = keyed;
		rows = Collections.unmodifiableList(next);
		ids.keySet().retainAll(keyed.keySet());
	}

	@VisibleForTesting
	int idCount() {
		return ids.size();
	}

	boolean hasStored(String key) {
		GroupPostRow r = byKey.get(key);
		return r != null && !r.pending;
	}

	void refreshPending() {
		List<GroupPostRow> current = rows;
		int pending = 0;
		for (GroupPostRow r : current) {
			if (r.pending) pending++;
		}
		if (pending > 0) {
			notifyItemRangeChanged(current.size() - pending, pending,
					PAYLOAD_STATE);
		}
	}

	void removePost(GroupTrPost p) {
		GroupTrMediaCache.evictPost(p);
		List<GroupPostRow> current = rows;
		String key = GroupPostRow.key(p);
		for (int i = 0; i < current.size(); i++) {
			if (current.get(i).key.equals(key)) {
				List<GroupPostRow> next = new ArrayList<>(current);
				next.remove(i);
				publish(next);
				notifyItemRemoved(i);
				return;
			}
		}
	}

	static String key(GroupTrPost p) {
		return GroupPostRow.key(p);
	}

	@Nullable
	GroupTrPost find(String key) {
		GroupPostRow r = byKey.get(key);
		return r == null ? null : r.post;
	}

	@Nullable
	GroupPostRow rowAt(int position) {
		List<GroupPostRow> current = rows;
		if (position < 0 || position >= current.size()) return null;
		return current.get(position);
	}

	@Nullable
	GroupTrPost postAt(int position) {
		GroupPostRow r = rowAt(position);
		return r == null ? null : r.post;
	}

	int positionOf(String key) {
		List<GroupPostRow> current = rows;
		for (int i = 0; i < current.size(); i++) {
			if (current.get(i).key.equals(key)) return i;
		}
		return -1;
	}

	List<GroupTrPost> currentPosts() {
		List<GroupPostRow> current = rows;
		List<GroupTrPost> out = new ArrayList<>(current.size());
		for (GroupPostRow r : current) out.add(r.post);
		return out;
	}

	void refreshSelection(String key) {
		int i = positionOf(key);
		if (i >= 0) notifyItemChanged(i, PAYLOAD_SELECTION);
	}

	void refreshState(String key) {
		int i = positionOf(key);
		if (i >= 0) notifyItemChanged(i, PAYLOAD_STATE);
	}

	void setDeliveries(Map<String, MessageState> next, long seq) {
		if (seq <= fullStatesAt) return;
		Set<String> keys = new HashSet<>(deliveries.keySet());
		keys.addAll(next.keySet());
		applyDeliveries(next, keys, seq);
		fullStatesAt = seq;
		statesAt.values().removeIf(at -> at <= seq);
	}

	void updateDeliveries(Map<String, MessageState> next,
			Collection<String> keys, long seq) {
		applyDeliveries(next, keys, seq);
	}

	private void applyDeliveries(Map<String, MessageState> next,
			Collection<String> keys, long seq) {
		Map<String, MessageState> merged = new HashMap<>(deliveries);
		Set<String> changed = new HashSet<>();
		for (String key : keys) {
			Long at = statesAt.get(key);
			if (seq <= fullStatesAt || (at != null && seq <= at)) continue;
			MessageState now = next.get(key);
			MessageState was = now == null ? merged.remove(key)
					: merged.put(key, now);
			if (was != now) changed.add(key);
			statesAt.put(key, seq);
		}
		deliveries = Collections.unmodifiableMap(merged);
		if (changed.isEmpty()) return;
		List<GroupPostRow> current = rows;
		for (int i = 0; i < current.size(); i++) {
			GroupPostRow r = current.get(i);
			if (!r.pending && changed.contains(r.key)) {
				notifyItemChanged(i, PAYLOAD_STATE);
			}
		}
	}

	@Nullable
	MessageState deliveryOf(String key) {
		return deliveries.get(key);
	}

	void refreshNames() {
		int count = rows.size();
		if (count > 0) notifyItemRangeChanged(0, count, PAYLOAD_NAME);
	}

	void removePosts(Collection<byte[]> postIds) {
		Set<String> gone = new HashSet<>();
		for (byte[] id : postIds) gone.add(toHexString(id));
		List<GroupPostRow> current = rows;
		List<GroupPostRow> next = new ArrayList<>(current.size());
		List<Integer> removed = new ArrayList<>();
		for (int i = 0; i < current.size(); i++) {
			GroupPostRow r = current.get(i);
			if (gone.contains(r.key)) removed.add(i);
			else next.add(r);
		}
		if (removed.isEmpty()) return;
		publish(next);
		for (int i = removed.size() - 1; i >= 0; i--) {
			notifyItemRemoved(removed.get(i));
		}
		for (int i = 0; i < next.size(); i++) {
			byte[] quoted = next.get(i).replyTo();
			if (quoted != null && gone.contains(toHexString(quoted))) {
				notifyItemChanged(i);
			}
		}
	}

	@Override
	public long getItemId(int position) {
		String key = rows.get(position).key;
		Long id = ids.get(key);
		if (id == null) {
			id = nextId++;
			ids.put(key, id);
		}
		return id;
	}

	@Override
	public int getItemViewType(int position) {
		GroupPostRow r = rows.get(position);
		boolean mine = cb.isMine(r.post);
		switch (r.kind) {
			case VOICE:
				return mine ? VOICE_OUT : VOICE_IN;
			case IMAGE:
				return mine ? IMAGE_OUT : IMAGE_IN;
			case VIDEO:
				return mine ? VIDEO_OUT : VIDEO_IN;
			default:
				return mine ? TEXT_OUT : TEXT_IN;
		}
	}

	@Override
	public int getItemCount() {
		return rows.size();
	}

	@NonNull
	@Override
	public PostHolder onCreateViewHolder(@NonNull ViewGroup parent,
			int viewType) {
		LayoutInflater inf = LayoutInflater.from(parent.getContext());
		int layout;
		switch (viewType) {
			case TEXT_OUT:
				layout = R.layout.list_item_grouptr_post_out;
				break;
			case TEXT_IN:
				layout = R.layout.list_item_grouptr_post_in;
				break;
			case VOICE_OUT:
				layout = R.layout.list_item_grouptr_voice_out;
				break;
			case VOICE_IN:
				layout = R.layout.list_item_grouptr_voice_in;
				break;
			case IMAGE_OUT:
				layout = R.layout.list_item_grouptr_image_out;
				break;
			case IMAGE_IN:
				layout = R.layout.list_item_grouptr_image_in;
				break;
			case VIDEO_OUT:
				layout = R.layout.list_item_grouptr_video_out;
				break;
			default:
				layout = R.layout.list_item_grouptr_video_in;
				break;
		}
		View v = inf.inflate(layout, parent, false);
		return new PostHolder(v, viewType);
	}

	@Override
	public void onBindViewHolder(@NonNull PostHolder h, int position) {
		h.bind(rows.get(position));
	}

	@Override
	public void onBindViewHolder(@NonNull PostHolder h, int position,
			@NonNull List<Object> payloads) {
		if (payloads.isEmpty()) {
			h.bind(rows.get(position));
			return;
		}
		boolean selection = false;
		boolean state = false;
		boolean names = false;
		for (Object p : payloads) {
			if (p == PAYLOAD_SELECTION) selection = true;
			else if (p == PAYLOAD_STATE) state = true;
			else if (p == PAYLOAD_NAME) names = true;
			else {
				h.bind(rows.get(position));
				return;
			}
		}
		GroupPostRow r = rows.get(position);
		if (selection) h.bindSelection(r);
		if (state) h.bindState(r);
		if (names) h.bindNames(r);
	}

	@Override
	public void onViewRecycled(@NonNull PostHolder h) {
		h.recycle();
	}

	class PostHolder extends RecyclerView.ViewHolder {
		private final int viewType;
		@Nullable
		private final TextView sender;
		@Nullable
		private final View bubble;
		@Nullable
		private final View mediaBubble;
		@Nullable
		private final View voiceBubble;
		@Nullable
		private final TextView postText;
		@Nullable
		private final TextView postTime;
		@Nullable
		private final ImageView imageView;
		@Nullable
		private final TextView imageTime;
		@Nullable
		private final AppCompatImageButton voicePlayButton;
		@Nullable
		private final TextView voiceDuration;
		@Nullable
		private final TextView voiceTime;
		@Nullable
		private final ImageView videoThumb;
		@Nullable
		private final TextView videoDuration;
		@Nullable
		private final TextView videoTime;
		@Nullable
		private final View quoteBlock;
		@Nullable
		private final TextView quoteAuthor;
		@Nullable
		private final TextView quoteText;
		@Nullable
		private final View notSentActions;
		@Nullable
		private ImageView mediaImage;
		@Nullable
		private Object imageToken;

		PostHolder(View v, int viewType) {
			super(v);
			this.viewType = viewType;
			View named = v.findViewById(R.id.senderName);
			sender = named instanceof TextView ? (TextView) named : null;
			bubble = v.findViewById(R.id.bubble);
			mediaBubble = v.findViewById(R.id.mediaBubble);
			voiceBubble = v.findViewById(R.id.voiceBubble);
			postText = v.findViewById(R.id.postText);
			postTime = v.findViewById(R.id.postTime);
			imageView = v.findViewById(R.id.imageView);
			imageTime = v.findViewById(R.id.imageTime);
			voicePlayButton = v.findViewById(R.id.voicePlayButton);
			voiceDuration = v.findViewById(R.id.voiceDuration);
			voiceTime = v.findViewById(R.id.voiceTime);
			videoThumb = v.findViewById(R.id.videoThumb);
			videoDuration = v.findViewById(R.id.videoDuration);
			videoTime = v.findViewById(R.id.videoTime);
			quoteBlock = v.findViewById(R.id.quoteBlock);
			quoteAuthor = v.findViewById(R.id.quoteAuthor);
			quoteText = v.findViewById(R.id.quoteText);
			notSentActions = v.findViewById(R.id.messageNotSentActions);
			if (!isOutgoing()) {
				SelectedFill.received(bubble);
				SelectedFill.received(mediaBubble);
				SelectedFill.received(voiceBubble);
			}
		}

		void bind(GroupPostRow r) {
			GroupTrPost p = r.post;
			applyChatStyle();
			if (sender != null) cb.bindSender(sender, p);
			boolean pending = r.pending;
			bindSelection(r);
			selectable(itemView, p, pending);
			selectable(bubble, p, pending);
			selectable(mediaBubble, p, pending);
			if (postText != null) {
				if (pending) SelectionRows.notSelectable(postText);
				else SelectionRows.passPresses(postText, itemView,
						cb::selecting);
			}
			selectable(imageView, p, pending);
			selectable(voicePlayButton, p, pending);
			selectable(quoteBlock, p, pending);
			GroupTrBody.Parsed parsed = r.parsed();
			switch (viewType) {
				case VOICE_IN:
				case VOICE_OUT:
					bindVoice(p, parsed);
					break;
				case IMAGE_IN:
				case IMAGE_OUT:
					bindImage(p, parsed);
					break;
				case VIDEO_IN:
				case VIDEO_OUT:
					bindVideo(p, parsed);
					break;
				default:
					bindText(p, parsed);
					break;
			}
			if (isOutgoing()) bindState(r);
		}

		void bindNames(GroupPostRow r) {
			if (sender != null) cb.bindSender(sender, r.post);
			if (r.kind == GroupTrBody.Kind.REPLY) bindQuote(r.post, r.parsed());
		}

		void bindSelection(GroupPostRow r) {
			SelectionRows.mark(itemView, !r.pending && cb.isSelected(r.post));
		}

		private void bindReading(GroupTrPost p, boolean pending) {
			RowReading.oneItem(itemView);
			View sender = itemView.findViewById(R.id.senderName);
			RowReading.partOf(sender);
			RowReading.partOf(itemView.findViewById(R.id.bubble));
			RowReading.partOf(itemView.findViewById(R.id.postText));
			if (viewType == IMAGE_IN || viewType == IMAGE_OUT) {
				RowReading.partOf(itemView.findViewById(R.id.mediaBubble));
			}
			RowActions.extra(itemView, R.id.action_safety_code,
					sender == null || pending ? null : itemView.getContext()
							.getString(R.string.grouptr_member_safety_code),
					sender == null ? null : sender::callOnClick);
			RowActions.bind(itemView, pending ? null : new RowActions.Row() {
				@Override
				public Set<SelectionAction> offered() {
					return cb.rowActions(p);
				}

				@Override
				public boolean perform(SelectionAction action) {
					return cb.onRowAction(p, action);
				}
			});
		}

		private boolean isOutgoing() {
			return viewType == TEXT_OUT || viewType == VOICE_OUT
					|| viewType == IMAGE_OUT || viewType == VIDEO_OUT;
		}

		void bindState(GroupPostRow r) {
			if (!isOutgoing()) return;
			GroupTrPost p = r.post;
			PendingGroupPost pending = r.pending ? (PendingGroupPost) p : null;
			MessageState state = pending == null ? deliveries.get(r.key)
					: pending.getState();
			TextView meta = meta();
			if (meta != null) {
				MessageStates.bind(meta, cb.formatTime(p.getTimestamp()),
						state);
				if (pending != null) {
					RowStates.of(meta).saw(meta, pending.key(), state,
							() -> excerpt(itemView.getContext(), p));
				}
			}
			boolean notSent = state == MessageState.NOT_SENT;
			View outlined = outlined();
			if (outlined != null) {
				MessageStates.outline(outlined, notSent,
						R.drawable.bg_message_not_sent_out);
			}
			if (pending == null || !notSent) {
				MessageStates.hideActions(notSentActions);
				return;
			}
			MessageStates.actions(notSentActions,
					excerpt(itemView.getContext(), r),
					pending.isEditable() ? () -> cb.onEdit(pending) : null,
					!cb.composerShown(),
					pending.isRetryable() ? () -> cb.onRetry(pending) : null,
					pending.isDiscardable()
							? () -> cb.onDiscard(pending) : null);
		}

		@Nullable
		private TextView meta() {
			switch (viewType) {
				case VOICE_OUT:
					return voiceTime;
				case IMAGE_OUT:
					return imageTime;
				case VIDEO_OUT:
					return videoTime;
				default:
					return postTime;
			}
		}

		@Nullable
		private View outlined() {
			switch (viewType) {
				case VOICE_OUT:
					return voiceBubble;
				case IMAGE_OUT:
				case VIDEO_OUT:
					return mediaBubble;
				default:
					return bubble;
			}
		}

		private void applyChatStyle() {
			com.professor.zerion.android.settings.MessageStyle style =
					messageStyle.get(itemView.getContext());
			if (postText != null) style.applyTextSize(postText);
			if (!isOutgoing()) return;
			if (bubble != null) style.tintOutgoing(bubble);
			if (mediaBubble != null) style.tintOutgoing(mediaBubble);
			if (voiceBubble != null) style.tintOutgoing(voiceBubble);
		}

		private void selectable(@Nullable View v, GroupTrPost p,
				boolean pending) {
			if (v == null) return;
			if (v == itemView) bindReading(p, pending);
			if (pending) {
				SelectionRows.notSelectable(v);
				if ((v == itemView || v.getId() == R.id.bubble)
						&& v.isClickable()) {
					v.setOnClickListener(null);
					v.setClickable(false);
				}
				return;
			}
			v.setOnLongClickListener(x -> {
				cb.onPostLongClick(p);
				return true;
			});
			SelectionRows.nameLongPress(v);
			if (v == itemView || v.getId() == R.id.bubble) {
				v.setOnClickListener(x -> cb.onPostClick(p));
			}
		}

		private void bindQuote(GroupTrPost p, GroupTrBody.Parsed parsed) {
			View block = quoteBlock;
			if (block == null || quoteAuthor == null || quoteText == null) {
				return;
			}
			if (parsed.replyTo == null) {
				block.setVisibility(View.GONE);
				return;
			}
			byte[] target = parsed.replyTo;
			GroupPostRow quoted = byKey.get(toHexString(target));
			android.content.Context ctx = itemView.getContext();
			if (quoted == null) {
				quoteAuthor.setVisibility(View.GONE);
				quoteText.setText(R.string.reply_original_unavailable);
				block.setContentDescription(
						ctx.getString(R.string.reply_original_unavailable));
			} else {
				String name = cb.quoteAuthor(quoted.post);
				String line = excerpt(ctx, quoted);
				quoteAuthor.setVisibility(View.VISIBLE);
				quoteAuthor.setText(name);
				quoteText.setText(line);
				block.setContentDescription(name + ", " + line);
			}
			block.setVisibility(View.VISIBLE);
			block.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onQuoteClick(target);
			});
		}

		private void bindText(GroupTrPost p, GroupTrBody.Parsed parsed) {
			bindQuote(p, parsed);
			TextView body = postText;
			if (body == null) return;
			if (!com.professor.zerion.android.channel.ChannelInviteSpanUtil
					.apply(body, parsed.text)) {
				body.setText(parsed.text);
				if (parsed.text != null && (parsed.text.indexOf('.') >= 0
						|| parsed.text.indexOf(':') >= 0)) {
					android.text.util.Linkify.addLinks(body,
							android.text.util.Linkify.WEB_URLS);
					com.professor.zerion.android.util.UiUtils
							.makeLinksClickable(body,
									url -> showLinkDialog(body, url));
				}
			}
			if (postTime != null) {
				postTime.setText(cb.formatTime(p.getTimestamp()));
			}
		}

		private void bindVoice(GroupTrPost p, GroupTrBody.Parsed parsed) {
			AppCompatImageButton playBtn = voicePlayButton;
			if (playBtn == null) return;
			if (voiceDuration != null) {
				voiceDuration.setText(cb.formatDuration(parsed.durationMs));
			}
			if (voiceTime != null) {
				voiceTime.setText(cb.formatTime(p.getTimestamp()));
			}
			byte[] audio = parsed.payload;
			playBtn.setImageResource(R.drawable.ic_play_arrow_24dp);
			playBtn.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onVoiceClick(playBtn, audio);
			});
		}

		private void bindImage(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView img = imageView;
			if (img == null) return;
			mediaImage = img;
			if (imageTime != null) {
				imageTime.setText(cb.formatTime(p.getTimestamp()));
			}
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			img.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onImageClick(p, bytes, mime);
			});
			String cacheKey = GroupTrMediaCache.key(p,
					GroupTrMediaCache.IMAGE);
			Bitmap cached = GroupTrMediaCache.get(cacheKey);
			if (cached != null && !cached.isRecycled()) {
				img.setImageBitmap(cached);
				return;
			}
			img.setImageDrawable(null);
			Object token = new Object();
			imageToken = token;
			DECODE_EXECUTOR.execute(() -> {
				Bitmap bmp = SafeImageDecoder.decode(bytes, 1024);
				if (bmp == null) return;
				GroupTrMediaCache.put(cacheKey, bmp);
				img.post(() -> {
					if (imageToken == token && mediaImage == img) {
						img.setImageBitmap(bmp);
					}
				});
			});
		}

		private void bindVideo(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView thumb = videoThumb;
			if (thumb == null) return;
			mediaImage = thumb;
			if (videoDuration != null) {
				videoDuration.setText(cb.formatDuration(parsed.durationMs));
			}
			if (videoTime != null) {
				videoTime.setText(cb.formatTime(p.getTimestamp()));
			}
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			String cacheKey = GroupTrMediaCache.key(p,
					GroupTrMediaCache.VIDEO);
			Bitmap cachedThumb = GroupTrMediaCache.get(cacheKey);
			if (cachedThumb != null && !cachedThumb.isRecycled()) {
				thumb.setImageBitmap(cachedThumb);
			} else {
				thumb.setImageDrawable(null);
				Object token = new Object();
				imageToken = token;
				DECODE_EXECUTOR.execute(() -> {
					Bitmap thumbBmp = cb.videoThumb(bytes);
					if (thumbBmp == null) return;
					GroupTrMediaCache.put(cacheKey, thumbBmp);
					thumb.post(() -> {
						if (imageToken == token && mediaImage == thumb) {
							thumb.setImageBitmap(thumbBmp);
						}
					});
				});
			}
			if (mediaBubble != null) {
				mediaBubble.setOnClickListener(v -> {
					if (cb.onPostClick(p)) return;
					cb.onVideoClick(p, bytes, mime);
				});
			}
		}

		void recycle() {
			SelectionRows.mark(itemView, false);
			imageToken = null;
			if (mediaImage != null) {
				mediaImage.setImageDrawable(null);
				mediaImage = null;
			}
		}
	}
}
