package com.professor.zerion.android.conversation;

import android.content.Context;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.sync.MessageId;
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
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@NotNullByDefault
class ConversationAdapter
		extends ZerionAdapter<ConversationItem, ConversationItemViewHolder>
		implements ItemReturningAdapter<ConversationItem> {

	private final ConversationListener listener;
	private final RecycledViewPool imageViewPool;
	private final ImageItemDecoration imageItemDecoration;
	private final org.briarproject.briar.api.attachment.AttachmentReader attachmentReader;
	private final java.util.concurrent.Executor dbExecutor;
	@Nullable
	private SelectionTracker<String> tracker = null;
	private int highlightedPosition = -1;

	ConversationAdapter(Context ctx,
			ConversationListener conversationListener,
			org.briarproject.briar.api.attachment.AttachmentReader attachmentReader,
			java.util.concurrent.Executor dbExecutor) {
		super(ctx, ConversationItem.class);
		listener = conversationListener;
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		imageViewPool = new RecycledViewPool();
		imageViewPool.setMaxRecycledViews(R.layout.list_item_conversation_msg_in, 15);
		imageViewPool.setMaxRecycledViews(R.layout.list_item_conversation_msg_out, 15);
		imageItemDecoration = new ImageItemDecoration(ctx);
		setHasStableIds(true);
	}

	@Override
	public long getItemId(int position) {
		return items.get(position).getId().hashCode();
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
					imageViewPool, imageItemDecoration, attachmentReader, dbExecutor);
		} else if (type == R.layout.list_item_conversation_msg_out) {
			return new ConversationMessageViewHolder(v, listener, false,
					imageViewPool, imageItemDecoration, attachmentReader, dbExecutor);
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
	public void onBindViewHolder(ConversationItemViewHolder ui, int position) {
		ConversationItem item = items.get(position);
		boolean selected = false;
		ui.bind(item, selected);
		if (item instanceof ConversationGroupTrInvitationItem) {
			ConversationGroupTrInvitationItem inv =
					(ConversationGroupTrInvitationItem) item;
			ui.itemView.setOnClickListener(
					v -> listener.onGroupTrInvitationOpen(inv));
		}
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
		if (old >= 0) notifyItemChanged(old);
		if (position >= 0) notifyItemChanged(position);
		if (old < 0 && position >= 0) {
			notifyItemRangeChanged(0, items.size());
		} else if (old >= 0 && position < 0) {
			notifyItemRangeChanged(0, items.size());
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
		return c1.equals(c2);
	}

	@Override
	public void add(ConversationItem item) {
		items.beginBatchedUpdates();
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
