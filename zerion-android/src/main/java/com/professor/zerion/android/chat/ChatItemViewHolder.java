package com.professor.zerion.android.chat;

import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.imageview.ShapeableImageView;
import com.professor.zerion.R;

import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.recyclerview.widget.RecyclerView;

@NotNullByDefault
class ChatItemViewHolder extends RecyclerView.ViewHolder {

	private final TextView avatarView;
	private final ShapeableImageView avatarImage;
	private final TextView nameView;
	private final TextView dateView;
	private final TextView subtitleView;
	private final TextView unreadCountView;
	private final View pinIcon;
	private final View presenceDot;

	ChatItemViewHolder(View v) {
		super(v);
		avatarView = v.findViewById(R.id.avatarView);
		avatarImage = v.findViewById(R.id.avatarImage);
		nameView = v.findViewById(R.id.nameView);
		dateView = v.findViewById(R.id.dateView);
		subtitleView = v.findViewById(R.id.subtitleView);
		unreadCountView = v.findViewById(R.id.unreadCountView);
		pinIcon = v.findViewById(R.id.pinIcon);
		presenceDot = v.findViewById(R.id.presenceDot);
	}

	void bind(ChatItem item, ChatsAdapter.OnChatClickListener listener) {
		String name = item.getName();
		nameView.setText(name);
		bindAvatar(name, item.getAvatarHeader());

		switch (item.getType()) {
			case GROUP:
				subtitleView.setText(R.string.chat_type_group);
				break;
			case CHANNEL:
				subtitleView.setText(R.string.chat_type_channel);
				break;
			default:
				subtitleView.setText(item.isOnline()
						? R.string.online : R.string.offline);
				break;
		}

		// Presence dot: only 1:1 contacts have an online/offline state.
		if (item.getType() == ChatItem.Type.CONTACT) {
			presenceDot.setVisibility(View.VISIBLE);
			presenceDot.setBackgroundResource(item.isOnline()
					? R.drawable.bg_presence_online
					: R.drawable.bg_presence_offline);
		} else {
			presenceDot.setVisibility(View.GONE);
		}

		long time = item.getTime();
		if (time > 0) {
			dateView.setText(DateUtils.getRelativeTimeSpanString(time,
					System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
		} else {
			dateView.setText("");
		}

		int unread = item.getUnread();
		if (unread > 0) {
			unreadCountView.setText(String.valueOf(unread));
			unreadCountView.setVisibility(View.VISIBLE);
		} else {
			unreadCountView.setVisibility(View.GONE);
		}

		pinIcon.setVisibility(item.isPinned() ? View.VISIBLE : View.GONE);

		itemView.setOnClickListener(v -> listener.onChatClick(item));
		itemView.setOnLongClickListener(v ->
				listener.onChatLongClick(item, v));
	}

	private void bindAvatar(String name, @Nullable AttachmentHeader avatar) {
		if (avatar != null) {
			avatarView.setVisibility(View.GONE);
			avatarImage.setVisibility(View.VISIBLE);
			Glide.with(avatarImage)
					.load(avatar)
					.diskCacheStrategy(DiskCacheStrategy.NONE)
					.centerCrop()
					.signature(new ObjectKey(avatar.getMessageId().getBytes()))
					.into(avatarImage);
		} else {
			Glide.with(avatarImage).clear(avatarImage);
			avatarImage.setVisibility(View.GONE);
			avatarView.setVisibility(View.VISIBLE);
			avatarView.setText(initial(name));
		}
	}

	private String initial(String name) {
		if (name.isEmpty()) return "?";
		int cp = name.codePointAt(0);
		return new String(Character.toChars(cp)).toUpperCase();
	}
}
