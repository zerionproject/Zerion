package com.professor.zerion.android.conversation;

import android.content.Context;
import android.text.util.Linkify;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.briarproject.nullsafety.NotNullByDefault;

import java.net.URL;
import java.util.Map;

import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.zerionproject.core.util.StringUtils.trim;
import static com.professor.zerion.android.util.UiUtils.formatDate;
import static com.professor.zerion.android.util.UiUtils.formatDuration;
import static com.professor.zerion.android.util.UiUtils.makeLinksClickable;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@UiThread
@NotNullByDefault
abstract class ConversationItemViewHolder extends ViewHolder {

	protected final ConversationListener listener;
	private final View root;
	protected final ConstraintLayout layout;
	@Nullable
	private final OutItemViewHolder outViewHolder;
	private final TextView topNotice;
	protected final TextView text;
	protected final TextView time;
	protected final ImageView bomb;
	@Nullable
	private View replyPreviewContainer;
	@Nullable
	private TextView replyText;
	@Nullable
	private String itemKey = null;
	private float lastTextSizeSp = -1f;
	@Nullable
	private Integer lastBubbleColor = null;
	@Nullable
	private final TextView reactionsView;
	@Nullable
	private final View linkPreviewCard;
	@Nullable
	private final ImageView linkPreviewImage;
	@Nullable
	private final TextView linkPreviewTitle;
	@Nullable
	private final TextView linkPreviewDescription;
	@Nullable
	private final TextView linkPreviewDomain;

	ConversationItemViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v);
		this.listener = listener;
		outViewHolder = isIncoming ? null : new OutItemViewHolder(v);
		root = v;
		topNotice = v.findViewById(R.id.topNotice);
		layout = v.findViewById(R.id.layout);
		text = v.findViewById(R.id.text);
		time = v.findViewById(R.id.time);
		bomb = v.findViewById(R.id.bomb);
		replyPreviewContainer = layout.findViewById(R.id.replyPreviewContainer);
		replyText = layout.findViewById(R.id.replyText);
		reactionsView = layout.findViewById(R.id.reactionsView);
		linkPreviewCard = layout.findViewById(R.id.linkPreviewCard);
		if (linkPreviewCard != null) {
			linkPreviewImage = linkPreviewCard.findViewById(
					R.id.linkPreviewImage);
			linkPreviewTitle = linkPreviewCard.findViewById(
					R.id.linkPreviewTitle);
			linkPreviewDescription = linkPreviewCard.findViewById(
					R.id.linkPreviewDescription);
			linkPreviewDomain = linkPreviewCard.findViewById(
					R.id.linkPreviewDomain);
		} else {
			linkPreviewImage = null;
			linkPreviewTitle = null;
			linkPreviewDescription = null;
			linkPreviewDomain = null;
		}
	}

	void onRecycled() {
	}

	void bindTimeOnly(ConversationItem item) {
		setTopNotice(item);
		time.setText(formatDate(time.getContext(), item.getTime()));
	}

	@CallSuper
	void bind(ConversationItem item, boolean selected) {
		itemKey = item.getKey();
		root.setActivated(selected);

		setTopNotice(item);

		float textSizeSp = com.professor.zerion.android.settings.ChatPreferences
				.getMessageTextSizeSp(text.getContext());
		if (textSizeSp != lastTextSizeSp) {
			text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
					textSizeSp);
			lastTextSizeSp = textSizeSp;
		}

		if (outViewHolder != null) {
			int bubbleColor = com.professor.zerion.android.settings.ChatPreferences
					.getBubbleColor(layout.getContext());
			if (lastBubbleColor == null || lastBubbleColor != bubbleColor) {
				android.graphics.drawable.Drawable bg = layout.getBackground();
				if (bg != null) {
					bg = bg.mutate();
					bg.setColorFilter(bubbleColor,
							android.graphics.PorterDuff.Mode.SRC_IN);
					layout.setBackground(bg);
				}
				lastBubbleColor = bubbleColor;
			}
		}

		if (item.getText() != null) {
			if (item.getText().startsWith("[VOICE:")
					|| com.professor.zerion.android.conversation.voice
							.VoiceMessageChunkFormat.isPart(item.getText())) {
				text.setText("");
			} else {
				String body = trim(item.getText());
				if (!com.professor.zerion.android.channel
						.ChannelInviteSpanUtil.apply(text, body)) {
					text.setText(body);
					if (body.indexOf('.') >= 0 || body.indexOf(':') >= 0) {
						Linkify.addLinks(text, Linkify.WEB_URLS);
						makeLinksClickable(text, listener::onLinkClick);
					}
				}
			}
		}

		long timestamp = item.getTime();
		time.setText(formatDate(time.getContext(), timestamp));

		boolean showBomb = item.getAutoDeleteTimer() != NO_AUTO_DELETE_TIMER;
		bomb.setVisibility(showBomb ? VISIBLE : GONE);
		if (showBomb) {
			bomb.setContentDescription(bomb.getContext()
					.getString(R.string.message_auto_delete_indicator));
		}

		layout.setOnLongClickListener(v -> {
			listener.onMessageLongClick(item);
			return true;
		});

		bindReplyContext(item);
		bindReactions(item);
		bindLinkPreview(item);

		if (outViewHolder != null) outViewHolder.bind(item);
	}

	void bindReactions(ConversationItem item) {
		if (reactionsView == null) return;
		if (item.hasReactions()) {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, Integer> entry :
					item.getReactions().entrySet()) {
				if (sb.length() > 0) sb.append("  ");
				sb.append(entry.getKey());
				if (entry.getValue() > 1) {
					sb.append(" ").append(entry.getValue());
				}
			}
			reactionsView.setText(sb.toString());
			reactionsView.setVisibility(VISIBLE);
			reactionsView.setOnClickListener(v ->
					listener.onReactionClicked(item));
		} else {
			reactionsView.setVisibility(GONE);
		}
	}

	void bindLinkPreview(ConversationItem item) {
		if (linkPreviewCard == null) return;
		LinkPreview preview = item.getLinkPreview();
		if (preview != null) {
			linkPreviewCard.setVisibility(VISIBLE);
			if (linkPreviewTitle != null) {
				linkPreviewTitle.setText(preview.getTitle());
			}
			if (linkPreviewDescription != null) {
				String desc = preview.getDescription();
				if (desc != null && !desc.isEmpty()) {
					linkPreviewDescription.setText(desc);
					linkPreviewDescription.setVisibility(VISIBLE);
				} else {
					linkPreviewDescription.setVisibility(GONE);
				}
			}
			if (linkPreviewDomain != null) {
				try {
					String host = new URL(preview.getUrl()).getHost();
					linkPreviewDomain.setText(host);
				} catch (Exception e) {
					linkPreviewDomain.setText(preview.getUrl());
				}
			}
			if (linkPreviewImage != null) {
				if (preview.hasImage()) {
					linkPreviewImage.setVisibility(VISIBLE);
					com.bumptech.glide.Glide.with(linkPreviewImage)
							.asBitmap()
							.load(preview.getImageData())
							.diskCacheStrategy(com.bumptech.glide.load.engine
									.DiskCacheStrategy.NONE)
							.into(linkPreviewImage);
				} else {
					com.bumptech.glide.Glide.with(linkPreviewImage)
							.clear(linkPreviewImage);
					linkPreviewImage.setVisibility(GONE);
				}
			}
			linkPreviewCard.setOnClickListener(v ->
					listener.onLinkClick(preview.getUrl()));
		} else {
			linkPreviewCard.setVisibility(GONE);
		}
	}

	void bindReplyContext(ConversationItem item) {
		if (replyPreviewContainer != null && replyText != null) {
			if (item.hasReplyContext()) {
				String replyTextContent = item.getReplyToText();
				if (replyTextContent != null) {
					String prefix = item.isIncoming() ? "You: " :
							(item.getContactName() != null ?
									item.getContactName().getValue() + ": " : "");
					replyText.setText(prefix + replyTextContent);
				}
				replyPreviewContainer.setVisibility(VISIBLE);
			} else {
				replyPreviewContainer.setVisibility(GONE);
			}
		}
	}

	boolean isIncoming() {
		return outViewHolder == null;
	}

	@Nullable
	String getItemKey() {
		return itemKey;
	}

	private void setTopNotice(ConversationItem item) {
		if (item.isTimerNoticeVisible()) {
			Context ctx = itemView.getContext();
			topNotice.setVisibility(VISIBLE);
			boolean enabled = item.getAutoDeleteTimer() != NO_AUTO_DELETE_TIMER;
			String duration = enabled ?
					formatDuration(ctx, item.getAutoDeleteTimer()) : "";
			String tapToLearnMore = ctx.getString(R.string.tap_to_learn_more);
			String text;
			if (item.isIncoming()) {
				String name = item.getContactName() != null ?
						item.getContactName().getValue() :
						ctx.getString(R.string.unknown_contact);
				text = enabled ?
						ctx.getString(R.string.auto_delete_msg_contact_enabled,
								name, duration, tapToLearnMore) :
						ctx.getString(R.string.auto_delete_msg_contact_disabled,
								name, tapToLearnMore);
			} else {
				text = enabled ?
						ctx.getString(R.string.auto_delete_msg_you_enabled,
								duration, tapToLearnMore) :
						ctx.getString(R.string.auto_delete_msg_you_disabled,
								tapToLearnMore);
			}
			topNotice.setText(text);
			topNotice.setOnClickListener(
					v -> listener.onAutoDeleteTimerNoticeClicked());
		} else {
			topNotice.setVisibility(GONE);
		}
	}

}
