package com.professor.zerion.android.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

@NotNullByDefault
class ChatsAdapter extends RecyclerView.Adapter<ChatItemViewHolder> {

	interface OnChatClickListener {
		void onChatClick(ChatItem item);

		boolean onChatLongClick(ChatItem item, View anchor);
	}

	private final List<ChatItem> items = new ArrayList<>();
	private final OnChatClickListener listener;

	ChatsAdapter(OnChatClickListener listener) {
		this.listener = listener;
	}

	void submit(List<ChatItem> newItems) {
		List<ChatItem> old = new ArrayList<>(items);
		DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override
			public int getOldListSize() {
				return old.size();
			}

			@Override
			public int getNewListSize() {
				return newItems.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPos, int newPos) {
				ChatItem a = old.get(oldPos);
				ChatItem b = newItems.get(newPos);
				return a.getType() == b.getType()
						&& a.getContactId() == b.getContactId()
						&& java.util.Arrays.equals(a.getBlobId(), b.getBlobId());
			}

			@Override
			public boolean areContentsTheSame(int oldPos, int newPos) {
				ChatItem a = old.get(oldPos);
				ChatItem b = newItems.get(newPos);
				return a.getName().equals(b.getName())
						&& a.getTime() == b.getTime()
						&& a.getUnread() == b.getUnread()
						&& a.isPinned() == b.isPinned()
						&& a.isOnline() == b.isOnline()
						&& java.util.Objects.equals(
								a.getAvatarHeader(), b.getAvatarHeader());
			}
		});
		items.clear();
		items.addAll(newItems);
		diff.dispatchUpdatesTo(this);
	}

	@Override
	public ChatItemViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_chat, parent, false);
		return new ChatItemViewHolder(v);
	}

	@Override
	public void onBindViewHolder(ChatItemViewHolder holder, int position) {
		holder.bind(items.get(position), listener);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}
}
