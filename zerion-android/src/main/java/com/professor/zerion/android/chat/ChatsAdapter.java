package com.professor.zerion.android.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

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
		items.clear();
		items.addAll(newItems);
		notifyDataSetChanged();
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
