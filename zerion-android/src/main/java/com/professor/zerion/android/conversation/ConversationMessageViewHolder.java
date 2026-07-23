package com.professor.zerion.android.conversation;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.core.widget.ImageViewCompat.setImageTintList;

@UiThread
@NotNullByDefault
class ConversationMessageViewHolder extends ConversationItemViewHolder {

	private final ImageAdapter adapter;
	private final ViewGroup statusLayout;
	private final View voiceMessageView;
	private final int timeColor, timeColorBubble;
	private final ConstraintSet textConstraints = new ConstraintSet();
	private final ConstraintSet imageConstraints = new ConstraintSet();
	private final ConstraintSet imageTextConstraints = new ConstraintSet();

	@Nullable
	private VoiceMessageViewHolder voiceHolder;
	@Nullable
	private org.zerionproject.core.api.sync.MessageId boundVoiceItemId;
	@Nullable
	private String boundVoiceStateKey;
	private final AttachmentReader attachmentReader;
	private final Executor dbExecutor;

	ConversationMessageViewHolder(View v, ConversationListener listener,
			boolean isIncoming, RecycledViewPool imageViewPool,
			ImageItemDecoration imageItemDecoration,
			AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor) {
		super(v, listener, isIncoming);
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		statusLayout = v.findViewById(R.id.statusLayout);
		voiceMessageView = v.findViewById(R.id.voiceMessageView);

		RecyclerView list = v.findViewById(R.id.imageList);
		list.setRecycledViewPool(imageViewPool);
		adapter = new ImageAdapter(v.getContext(), listener, attachmentReader, dbExecutor);
		list.setAdapter(adapter);
		list.addItemDecoration(imageItemDecoration);

		timeColor = time.getCurrentTextColor();
		timeColorBubble =
				getColor(v.getContext(), R.color.msg_status_bubble_foreground);

		textConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_in_content);
		imageConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_image);
		imageTextConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_image_text);

		textConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
		imageConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
		imageTextConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
	}

	@Override
	void bind(ConversationItem conversationItem, boolean selected) {
		super.bind(conversationItem, selected);
		ConversationMessageItem item =
				(ConversationMessageItem) conversationItem;

		boolean formattedPart = com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat
				.isPart(item.getText());
		com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat.Part part =
				com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat
						.parse(item.getText());
		if (formattedPart && (part == null || part.seq > 0)) {
			bindHiddenPart();
			return;
		}
		restoreItemHeight();

		if (item.needsAttachmentLoading()) {
			listener.loadAttachmentsForItem(item);
		}

		boolean hasVoiceMessage = hasVoiceMessage(item);

		if (hasVoiceMessage) {
			bindVoiceMessage(item, part);
		} else if (item.getAttachments().isEmpty()) {
			bindTextItem(item);
		} else {
			bindImageItem(item);
		}

		bindReplyContext(conversationItem);
		bindReactions(conversationItem);
		bindLinkPreview(conversationItem);
	}

	@Override
	void onRecycled() {
		if (voiceHolder != null) {
			voiceHolder.onRecycled();
			voiceHolder = null;
		}
	}

	private void bindHiddenPart() {
		if (voiceHolder != null) {
			voiceHolder.onRecycled();
			voiceHolder = null;
		}
		itemView.setVisibility(GONE);
		android.view.ViewGroup.LayoutParams lp = itemView.getLayoutParams();
		if (lp != null) {
			lp.height = 0;
			itemView.setLayoutParams(lp);
		}
	}

	private void restoreItemHeight() {
		if (itemView.getVisibility() != VISIBLE) {
			itemView.setVisibility(VISIBLE);
		}
		android.view.ViewGroup.LayoutParams lp = itemView.getLayoutParams();
		if (lp != null && lp.height == 0) {
			lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
			itemView.setLayoutParams(lp);
		}
	}

	private boolean hasVoiceMessage(ConversationMessageItem item) {
		String messageText = item.getText();
		if (messageText != null && com.professor.zerion.android.conversation.voice.VoiceMessageFormat.isVoiceMessage(messageText)) {
			return true;
		}
		if (messageText != null && com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat.isPart(messageText)) {
			return true;
		}

		if (item.getAttachments().isEmpty()) return false;
		for (AttachmentItem attachment : item.getAttachments()) {
			String contentType = attachment.getHeader().getContentType();
			if ("audio/opus".equals(contentType) ||
					"audio/3gpp".equals(contentType) ||
					"audio/3gp".equals(contentType)) {
				return true;
			}
		}
		return false;
	}

	private void bindVoiceMessage(ConversationMessageItem item,
			@Nullable com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat.Part part) {
		adapter.clear();

		String messageText = item.getText();
		String reassembled = null;
		String stateKey;
		if (part != null) {
			reassembled = listener.getReassembledVoiceMessage(part.memoId);
			if (reassembled != null) {
				stateKey = "ready:" + reassembled.length() + ":"
						+ reassembled.hashCode();
			} else if (listener.isVoiceMemoFailed(part.memoId)) {
				stateKey = "failed";
			} else {
				stateKey = "receiving";
			}
		} else if (messageText != null && com.professor.zerion.android
				.conversation.voice.VoiceMessageFormat
				.isVoiceMessage(messageText)) {
			stateKey = "msg";
		} else {
			stateKey = "att";
		}

		boolean sameAsBound = voiceHolder != null
				&& item.getId().equals(boundVoiceItemId)
				&& stateKey.equals(boundVoiceStateKey);

		if (!sameAsBound) {
			if (voiceHolder != null) {
				voiceHolder.onRecycled();
			}
			voiceHolder = new VoiceMessageViewHolder(voiceMessageView,
					attachmentReader, dbExecutor);
			if (part != null) {
				if (reassembled != null) {
					voiceHolder.bindEncryptedVoice(reassembled,
							item.getGroupId(), item.getId());
				} else if (stateKey.equals("failed")) {
					voiceHolder.bindFailed();
				} else {
					voiceHolder.bindReceiving();
				}
			} else if (stateKey.equals("msg")) {
				voiceHolder.bindEncryptedVoice(messageText, item.getGroupId(),
						item.getId());
			} else {
				voiceHolder.bind(item.getAttachments().get(0));
			}
			boundVoiceItemId = item.getId();
			boundVoiceStateKey = stateKey;
		}

		resetStatusLayoutForText();
		textConstraints.applyTo(layout);

		voiceMessageView.setVisibility(VISIBLE);
		text.setVisibility(GONE);
	}

	private void bindTextItem(ConversationMessageItem item) {
		voiceMessageView.setVisibility(GONE);
		text.setVisibility(VISIBLE);
		resetStatusLayoutForText();
		textConstraints.applyTo(layout);
		adapter.clear();

		String body = item.getText();
		if (com.professor.zerion.android.sticker.StickerUtils
				.isSingleEmojiSticker(body)) {
			text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 64f);
			layout.setBackground(null);
		}

		if (voiceHolder != null) {
			voiceHolder.onRecycled();
			voiceHolder = null;
		}
	}

	private void bindImageItem(ConversationMessageItem item) {
		voiceMessageView.setVisibility(GONE);
		text.setVisibility(item.getText() == null ? GONE : VISIBLE);

		if (voiceHolder != null) {
			voiceHolder.onRecycled();
			voiceHolder = null;
		}

		boolean isStickerOnly = item.getText() == null
				&& item.getAttachments().size() == 1
				&& item.getAttachments().get(0).isSticker();

		ConstraintSet constraintSet;
		if (item.getText() == null) {
			if (isStickerOnly) {
				resetStatusLayoutForText();
				layout.setBackground(null);
			} else {
				statusLayout.setBackgroundResource(R.drawable.msg_status_bubble);
				time.setTextColor(timeColorBubble);
				setImageTintList(bomb, ColorStateList.valueOf(timeColorBubble));
			}
			constraintSet = imageConstraints;
		} else {
			resetStatusLayoutForText();
			constraintSet = imageTextConstraints;
		}

		if (item.getAttachments().size() == 1) {
			AttachmentItem attachment = item.getAttachments().get(0);
			if (attachment.isSticker()) {
				int stickerPx = (int) (160 * itemView.getResources()
						.getDisplayMetrics().density);
				constraintSet.constrainWidth(R.id.imageList, stickerPx);
				constraintSet.constrainHeight(R.id.imageList, stickerPx);
			} else {
				int width = attachment.getThumbnailWidth();
				int height = attachment.getThumbnailHeight();
				constraintSet.constrainWidth(R.id.imageList, width);
				constraintSet.constrainHeight(R.id.imageList, height);
			}
		} else {
			constraintSet.constrainWidth(R.id.imageList, WRAP_CONTENT);
			constraintSet.constrainHeight(R.id.imageList, WRAP_CONTENT);
		}
		constraintSet.applyTo(layout);
		adapter.setConversationItem(item);
	}

	private void resetStatusLayoutForText() {
		statusLayout.setBackgroundResource(0);
		statusLayout.setPadding(0, 0, 0, 0);
		time.setTextColor(timeColor);
		setImageTintList(bomb, ColorStateList.valueOf(timeColor));
	}

}
