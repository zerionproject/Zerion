package com.professor.zerion.android.conversation;

import android.content.Context;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.util.ZerionAdapter;
import com.professor.zerion.android.util.ItemReturningAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@NotNullByDefault
class ConversationAdapter
		extends ZerionAdapter<ConversationItem, ConversationItemViewHolder>
		implements ItemReturningAdapter<ConversationItem> {

	private final ConversationListener listener;
	private final RecycledViewPool imageViewPool;
	private final ImageItemDecoration imageItemDecoration;
	private final org.zerionproject.app.api.attachment.AttachmentReader attachmentReader;
	private final java.util.concurrent.Executor dbExecutor;
	private final com.professor.zerion.android.conversation.voice.VoiceMemoKeys
			voiceMemoKeys;
	static final Object PAYLOAD_STATE = new Object();
	static final Object PAYLOAD_HIGHLIGHT = new Object();
	static final Object PAYLOAD_NAME = new Object();

	@Nullable
	private SelectionTracker<String> tracker = null;
	private final com.professor.zerion.android.settings.MessageStyle.Source messageStyle =
			new com.professor.zerion.android.settings.MessageStyle.Source();
	private int highlightedPosition = -1;

	ConversationAdapter(Context ctx,
			ConversationListener conversationListener,
			org.zerionproject.app.api.attachment.AttachmentReader attachmentReader,
			java.util.concurrent.Executor dbExecutor,
			com.professor.zerion.android.conversation.voice.VoiceMemoKeys
					voiceMemoKeys) {
		super(ctx, ConversationItem.class);
		listener = conversationListener;
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		this.voiceMemoKeys = voiceMemoKeys;
		imageViewPool = new RecycledViewPool();
		imageViewPool.setMaxRecycledViews(R.layout.list_item_image, 24);
		imageItemDecoration = new ImageItemDecoration(ctx);
		setHasStableIds(true);
	}

	void setMessageStyle(com.professor.zerion.android.settings.MessageStyle style) {
		if (messageStyle.update(style)) notifyDataSetChanged();
	}

	@Override
	public long getItemId(int position) {
		byte[] b = items.get(position).getId().getBytes();
		long id = 0;
		for (int i = 0; i < 8 && i < b.length; i++) {
			id = (id << 8) | (b[i] & 0xFF);
		}
		return id;
	}

	@LayoutRes
	@Override
	public int getItemViewType(int position) {
		ConversationItem item = items.get(position);
		return item.getLayout();
	}

	String getItemKey(int position) {
		return items.get(position).getKey();
	}

	int getPositionOfKey(String key) {
		for (int i = 0; i < items.size(); i++) {
			if (key.equals(items.get(i).getKey())) return i;
		}
		return NO_POSITION;
	}

	int getPositionOfMessage(MessageId id) {
		for (int i = 0; i < items.size(); i++) {
			if (id.equals(items.get(i).getId())) return i;
		}
		return NO_POSITION;
	}

	@Nullable
	public ConversationItem getItemAt(int position) {
		if (position >= 0 && position < items.size()) {
			return items.get(position);
		}
		return null;
	}

	@Override
	public ConversationItemViewHolder onCreateViewHolder(ViewGroup viewGroup,
			@LayoutRes int type) {
		View v = LayoutInflater.from(viewGroup.getContext()).inflate(
				type, viewGroup, false);
		if (type == R.layout.list_item_conversation_msg_in) {
			return new ConversationMessageViewHolder(v, listener, true,
					imageViewPool, imageItemDecoration, attachmentReader, dbExecutor,
					voiceMemoKeys);
		} else if (type == R.layout.list_item_conversation_msg_out) {
			return new ConversationMessageViewHolder(v, listener, false,
					imageViewPool, imageItemDecoration, attachmentReader, dbExecutor,
					voiceMemoKeys);
		} else if (type == R.layout.list_item_conversation_secret_in) {
			return new ConversationSecretNoteViewHolder(v, listener, true);
		} else if (type == R.layout.list_item_conversation_secret_out) {
			return new ConversationSecretNoteViewHolder(v, listener, false);
		} else if (type == R.layout.list_item_conversation_notice_in) {
			return new ConversationNoticeViewHolder(v, listener, true);
		} else if (type == R.layout.list_item_conversation_notice_out) {
			return new ConversationNoticeViewHolder(v, listener, false);
		} else if (type == R.layout.list_item_conversation_request) {
			return new ConversationRequestViewHolder(v, listener, true);
		} else if (type == R.layout.list_item_conversation_call_event) {
			return new ConversationCallEventViewHolder(v);
		}
		throw new IllegalArgumentException("Unknown ConversationItem");
	}

	@Override
	public void onViewRecycled(ConversationItemViewHolder ui) {
		ui.onRecycled();
		super.onViewRecycled(ui);
	}

	@Override
	public void onBindViewHolder(ConversationItemViewHolder ui, int position,
			List<Object> payloads) {
		if (payloads.isEmpty()) {
			onBindViewHolder(ui, position);
			return;
		}
		boolean time = false;
		boolean highlight = false;
		for (Object p : payloads) {
			if (p == com.professor.zerion.android.view.ZerionRecyclerView
					.PAYLOAD_TIME_REFRESH || p == PAYLOAD_STATE) {
				time = true;
			} else if (p == PAYLOAD_HIGHLIGHT) {
				highlight = true;
			} else {
				onBindViewHolder(ui, position);
				return;
			}
		}
		if (time) ui.bindTimeOnly(items.get(position));
		if (highlight) applyHighlight(ui, position);
	}

	@Override
	public void onBindViewHolder(ConversationItemViewHolder ui, int position) {
		ConversationItem item = items.get(position);
		SelectionTracker<String> t = tracker;
		boolean selected = t != null && t.isSelected(item.getKey());
		ui.setMessageStyle(messageStyle.get(ui.itemView.getContext()));
		ui.setSelecting(this::hasSelection);
		ui.bind(item, selected);
		applyHighlight(ui, position);
	}

	private void applyHighlight(ConversationItemViewHolder ui, int position) {
		if (position == highlightedPosition) {
			ui.itemView.setAlpha(1.0f);
		} else if (highlightedPosition >= 0) {
			ui.itemView.setAlpha(0.4f);
		} else {
			ui.itemView.setAlpha(1.0f);
		}
	}

	@UiThread
	void setHighlightedPosition(int position) {
		int old = highlightedPosition;
		highlightedPosition = position;
		if (old < 0 && position >= 0) {
			notifyItemRangeChanged(0, items.size(), PAYLOAD_HIGHLIGHT);
		} else if (old >= 0 && position < 0) {
			notifyItemRangeChanged(0, items.size(), PAYLOAD_HIGHLIGHT);
		} else {
			if (old >= 0) notifyItemChanged(old, PAYLOAD_HIGHLIGHT);
			if (position >= 0) notifyItemChanged(position, PAYLOAD_HIGHLIGHT);
		}
	}

	@UiThread
	void refreshState(int position) {
		notifyItemChanged(position, PAYLOAD_STATE);
	}

	@UiThread
	void refreshNames() {
		if (items.size() > 0) {
			notifyItemRangeChanged(0, items.size(), PAYLOAD_NAME);
		}
	}

	@Override
	public int compare(ConversationItem c1, ConversationItem c2) {
		return Long.compare(c1.getTime(), c2.getTime());
	}

	@Override
	public boolean areItemsTheSame(ConversationItem c1,
			ConversationItem c2) {
		return c1.getId().equals(c2.getId());
	}

	@Override
	public boolean areContentsTheSame(ConversationItem c1,
			ConversationItem c2) {
		return c1.showsTheSameAs(c2);
	}

	@Override
	public void add(ConversationItem item) {
		items.beginBatchedUpdates();
		items.add(item);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	@UiThread
	void addOrRefresh(ConversationItem item) {
		int position = getPositionOfMessage(item.getId());
		if (position == NO_POSITION) {
			add(item);
			return;
		}
		ConversationItem kept = items.get(position);
		if (kept.getClass() == item.getClass()) {
			int change = kept.updateFrom(item);
			if (change == ConversationItem.CONTENT_CHANGED) {
				notifyItemChanged(position);
			} else if (change == ConversationItem.STATE_CHANGED) {
				refreshState(position);
			}
			return;
		}
		if (kept instanceof ConversationSecretNoteItem) return;
		items.beginBatchedUpdates();
		items.remove(kept);
		items.add(item);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	@Override
	public void replaceAll(Collection<ConversationItem> itemsToReplace) {
		items.beginBatchedUpdates();
		items.replaceAll(itemsToReplace);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	@UiThread
	void removeItems(Collection<MessageId> messageIds) {
		List<ConversationItem> toRemove = new ArrayList<>(messageIds.size());
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (messageIds.contains(item.getId())) toRemove.add(item);
		}
		items.beginBatchedUpdates();
		for (ConversationItem item : toRemove) items.remove(item);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	private void updateTimersInBatch() {
		long lastTimerIncoming = NO_AUTO_DELETE_TIMER;
		long lastTimerOutgoing = NO_AUTO_DELETE_TIMER;
		for (int i = 0; i < items.size(); i++) {
			ConversationItem c = items.get(i);
			boolean itemChanged;
			boolean timerChanged;
			if (c.isIncoming()) {
				timerChanged = lastTimerIncoming != c.getAutoDeleteTimer();
				lastTimerIncoming = c.getAutoDeleteTimer();
			} else {
				timerChanged = lastTimerOutgoing != c.getAutoDeleteTimer();
				lastTimerOutgoing = c.getAutoDeleteTimer();
			}
			itemChanged = c.setTimerNoticeVisible(timerChanged);
			if (itemChanged) items.updateItemAt(i, c);
		}
	}

	private boolean hasSelection() {
		SelectionTracker<String> t = tracker;
		return t != null && t.hasSelection();
	}

	void setSelectionTracker(SelectionTracker<String> tracker) {
		this.tracker = tracker;
	}

	SparseArray<ConversationItem> getOutgoingMessages() {
		SparseArray<ConversationItem> messages = new SparseArray<>();
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (!item.isIncoming()) {
				messages.put(i, item);
			}
		}
		return messages;
	}

	java.util.Map<MessageId, ConversationItem> itemsById() {
		java.util.Map<MessageId, ConversationItem> byId =
				new java.util.HashMap<>(items.size() * 2);
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			byId.put(item.getId(), item);
		}
		return byId;
	}

	@Nullable
	Pair<Integer, ConversationMessageItem> getMessageItem(MessageId messageId) {
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (item instanceof ConversationMessageItem &&
					item.getId().equals(messageId)) {
				return new Pair<>(i, (ConversationMessageItem) item);
			}
		}
		return null;
	}

	boolean isScrolledToBottom(LinearLayoutManager layoutManager) {
		return layoutManager.findLastVisibleItemPosition() == items.size() - 1;
	}

}
