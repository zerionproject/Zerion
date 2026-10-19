package com.professor.zerion.android.conversation;

import android.content.Context;
import android.text.util.Linkify;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.accessibility.RowReading;
import com.professor.zerion.android.accessibility.TouchTargets;
import com.professor.zerion.android.selection.RowActions;
import com.professor.zerion.android.selection.SelectedFill;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionRows;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.sync.MessageId;

import java.net.URL;
import java.util.Map;
import java.util.Set;

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
	private TextView replyAuthor;
	@Nullable
	private String itemKey = null;
	private float lastTextSizeSp = -1f;
	@Nullable
	private Integer lastBubbleColor = null;
	private boolean bare = false;
	@Nullable
	private com.professor.zerion.android.settings.MessageStyle messageStyle = null;
	@Nullable
	private final android.graphics.drawable.Drawable defaultBubbleBackground;
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

	private java.util.function.BooleanSupplier selecting = () -> false;

	void setSelecting(java.util.function.BooleanSupplier selecting) {
		this.selecting = selecting;
	}

	ConversationItemViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v);
		this.listener = listener;
		outViewHolder = isIncoming ? null : new OutItemViewHolder(v);
		root = v;
		topNotice = v.findViewById(R.id.topNotice);
		layout = v.findViewById(R.id.layout);
		defaultBubbleBackground = layout.getBackground();
		if (isIncoming) SelectedFill.received(layout);
		text = v.findViewById(R.id.text);
		time = v.findViewById(R.id.time);
		bomb = v.findViewById(R.id.bomb);
		replyPreviewContainer = layout.findViewById(R.id.replyPreviewContainer);
		replyText = layout.findViewById(R.id.replyText);
		replyAuthor = layout.findViewById(R.id.replyAuthor);
		reactionsView = layout.findViewById(R.id.reactionsView);
		if (reactionsView != null) {
			TouchTargets.atLeast48dp(reactionsView,
					layout.findViewById(R.id.statusLayout));
		}
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

	void setMessageStyle(com.professor.zerion.android.settings.MessageStyle style) {
		messageStyle = style;
	}

	private com.professor.zerion.android.settings.MessageStyle messageStyle() {
		com.professor.zerion.android.settings.MessageStyle s = messageStyle;
		if (s == null) {
			s = com.professor.zerion.android.settings.MessageStyle.read(text.getContext());
			messageStyle = s;
		}
		return s;
	}

	void bindTimeOnly(ConversationItem item) {
		setTopNotice(item);
		bindTime(item);
	}

	private void bindTime(ConversationItem item) {
		if (outViewHolder == null) {
			time.setText(timeLabel(item));
		} else {
			outViewHolder.bind(item, time, timeLabel(item));
		}
	}

	private String timeLabel(ConversationItem item) {
		String timeText = formatDate(time.getContext(), item.getTime());
		if (item instanceof ConversationMessageItem
				&& ((ConversationMessageItem) item).getHeader().isMesh()) {
			timeText = timeText + " · "
					+ time.getContext().getString(R.string.via_mesh);
		}
		if (item instanceof ConversationMessageItem
				&& ((ConversationMessageItem) item).isEdited()) {
			timeText = timeText + " · "
					+ time.getContext().getString(R.string.message_edited);
		}
		return timeText;
	}

	protected void applyStickerTextStyle() {
		text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 64f);
		lastTextSizeSp = 64f;
		bareBubble();
	}

	protected void applyDefaultTextStyle() {
		float textSizeSp = messageStyle().getTextSizeSp();
		text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp);
		lastTextSizeSp = textSizeSp;
		restoreBubble();
	}

	protected void bareBubble() {
		if (bare) return;
		SelectedFill.bare(layout);
		bare = true;
		lastBubbleColor = null;
	}

	protected void restoreBubble() {
		if (bare && defaultBubbleBackground != null
				&& defaultBubbleBackground.getConstantState() != null) {
			layout.setBackground(defaultBubbleBackground.getConstantState()
					.newDrawable().mutate());
		}
		if (bare && outViewHolder == null) SelectedFill.received(layout);
		bare = false;
		paintBubble();
	}

	private void paintBubble() {
		if (bare || outViewHolder == null) return;
		int bubbleColor = messageStyle().getBubbleColor();
		if (lastBubbleColor != null && lastBubbleColor == bubbleColor) return;
		SelectedFill.own(layout, bubbleColor);
		lastBubbleColor = bubbleColor;
		tintOutgoingVoice(bubbleColor);
	}

	protected void paintPart(View part) {
		if (outViewHolder == null) SelectedFill.received(part);
		else SelectedFill.own(part, messageStyle().getBubbleColor());
	}

	private void tintOutgoingVoice(int bubbleColor) {
		View play = layout.findViewById(R.id.playPauseButton);
		if (play instanceof com.google.android.material.button.MaterialButton) {
			((com.google.android.material.button.MaterialButton) play)
					.setIconTint(android.content.res.ColorStateList
							.valueOf(bubbleColor));
		}
	}

	@CallSuper
	void bind(ConversationItem item, boolean selected) {
		itemKey = item.getKey();
		SelectionRows.mark(root, selected);

		setTopNotice(item);

		float textSizeSp = messageStyle().getTextSizeSp();
		if (textSizeSp != lastTextSizeSp) {
			text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
					textSizeSp);
			lastTextSizeSp = textSizeSp;
		}

		paintBubble();

		bindBody(item);

		bindTime(item);

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
		SelectionRows.nameLongPress(layout);
		layout.setOnClickListener(v -> listener.onMessageClick(item));
		SelectionRows.passPresses(text, layout, selecting);
		RowReading.partOf(text);
		RowReading.oneItem(layout);
		RowActions.bind(layout, rowActions(item));

		bindReplyContext(item);
		bindReactions(item);
		bindLinkPreview(item);
	}

	void bindBody(ConversationItem item) {
		if (item.getText() == null) return;
		if (item.getText().startsWith("[VOICE:")
				|| com.professor.zerion.android.conversation.voice
						.VoiceMessageChunkFormat.isPart(item.getText())) {
			text.setText("");
			return;
		}
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
			reactionsView.setOnClickListener(v -> {
				if (selecting.getAsBoolean()) listener.onMessageClick(item);
				else listener.onReactionClicked(item);
			});
			reactionsView.setOnLongClickListener(v ->
					layout.performLongClick());
		} else {
			reactionsView.setVisibility(GONE);
			TouchTargets.drop(reactionsView);
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
					Context ctx = replyText.getContext();
					String name = item.getContactName() != null ?
							item.getContactName().getValue() : null;
					String author = quoteAuthor(item.getReplyToLocal(),
							ctx.getString(R.string.reply_prefix_you),
							name != null ? name
									: ctx.getString(R.string.unknown_contact));
					if (item.isReplyToEdited()) {
						String edited = ctx.getString(R.string.message_edited);
						author = author == null ? edited
								: author + " · " + edited;
					}
					if (replyAuthor != null) {
						replyAuthor.setText(author);
						replyAuthor.setVisibility(author == null ? GONE : VISIBLE);
						replyText.setText(replyTextContent);
					} else {
						replyText.setText(author == null ? replyTextContent
								: author + ": " + replyTextContent);
					}
				}
				MessageId quoted = item.getReplyToMessageId();
				if (quoted != null) {
					replyPreviewContainer.setOnClickListener(v ->
							listener.onQuoteClicked(quoted));
					replyPreviewContainer.setOnLongClickListener(v ->
							layout.performLongClick());
				} else {
					replyPreviewContainer.setOnClickListener(null);
					replyPreviewContainer.setOnLongClickListener(null);
					replyPreviewContainer.setClickable(false);
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
	static String quoteAuthor(@Nullable Boolean quotedLocal, String you,
			String contactName) {
		if (quotedLocal == null) return null;
		return quotedLocal ? you : contactName;
	}

	@Nullable
	String getItemKey() {
		return itemKey;
	}

	@Nullable
	RowActions.Row rowActions(ConversationItem item) {
		return new RowActions.Row() {
			@Override
			public Set<SelectionAction> offered() {
				return listener.rowActions(item);
			}

			@Override
			public boolean perform(SelectionAction action) {
				return listener.onRowAction(item, action);
			}
		};
	}

	private void setTopNotice(ConversationItem item) {
		if (topNotice == null) return;
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
