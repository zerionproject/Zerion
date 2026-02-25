package com.professor.zerion.android.conversation;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import org.briarproject.briar.api.messaging.LinkPreview;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

import static org.briarproject.bramble.util.StringUtils.toHexString;

@NotThreadSafe
@NotNullByDefault
public abstract class ConversationItem {

	@LayoutRes
	private final int layoutRes;
	@Nullable
	protected String text;
	private final MessageId id;
	private final GroupId groupId;
	private final long time, autoDeleteTimer;
	private final boolean isIncoming;
	private final LiveData<String> contactName;
	private boolean read, sent, seen, showTimerNotice;
	@Nullable
	private MessageId replyToMessageId;
	@Nullable
	private String replyToText;

	ConversationItem(@LayoutRes int layoutRes, ConversationMessageHeader h,
			LiveData<String> contactName) {
		this.layoutRes = layoutRes;
		this.text = null;
		this.id = h.getId();
		this.groupId = h.getGroupId();
		this.time = h.getTimestamp();
		this.autoDeleteTimer = h.getAutoDeleteTimer();
		this.read = h.isRead();
		this.sent = h.isSent();
		this.seen = h.isSeen();
		this.isIncoming = !h.isLocal();
		this.contactName = contactName;
		this.showTimerNotice = false;
	}

	@LayoutRes
	int getLayout() {
		return layoutRes;
	}

	public MessageId getId() {
		return id;
	}

	String getKey() {
		return toHexString(id.getBytes());
	}

	GroupId getGroupId() {
		return groupId;
	}

	void setText(String text) {
		this.text = text;
	}

	@Nullable
	public String getText() {
		return text;
	}

	public long getTime() {
		return time;
	}

	public long getAutoDeleteTimer() {
		return autoDeleteTimer;
	}

	boolean isRead() {
		return read;
	}

	void markRead() {
		read = true;
	}

	boolean isSent() {
		return sent;
	}

	void setSent(boolean sent) {
		this.sent = sent;
	}

	boolean isSeen() {
		return seen;
	}

	void setSeen(boolean seen) {
		this.seen = seen;
	}

	public boolean isIncoming() {
		return isIncoming;
	}

	public LiveData<String> getContactName() {
		return contactName;
	}

	boolean setTimerNoticeVisible(boolean visible) {
		if (this.showTimerNotice != visible) {
			this.showTimerNotice = visible;
			return true;
		}
		return false;
	}

	boolean isTimerNoticeVisible() {
		return showTimerNotice;
	}

	@Nullable
	public MessageId getReplyToMessageId() {
		return replyToMessageId;
	}

	public void setReplyToMessageId(@Nullable MessageId replyToMessageId) {
		this.replyToMessageId = replyToMessageId;
	}

	@Nullable
	public String getReplyToText() {
		return replyToText;
	}

	public void setReplyToText(@Nullable String replyToText) {
		this.replyToText = replyToText;
	}

	public boolean hasReplyContext() {
		return replyToMessageId != null && replyToText != null;
	}

	private final Map<String, Integer> reactions = new HashMap<>();

	public Map<String, Integer> getReactions() {
		return reactions;
	}

	public void addReaction(String emoji) {
		reactions.merge(emoji, 1, Integer::sum);
	}

	public void removeReaction(String emoji) {
		Integer count = reactions.get(emoji);
		if (count != null) {
			if (count <= 1) reactions.remove(emoji);
			else reactions.put(emoji, count - 1);
		}
	}

	public boolean hasReactions() {
		return !reactions.isEmpty();
	}

	@Nullable
	private LinkPreview linkPreview;

	@Nullable
	public LinkPreview getLinkPreview() {
		return linkPreview;
	}

	public void setLinkPreview(@Nullable LinkPreview linkPreview) {
		this.linkPreview = linkPreview;
	}

	public boolean hasLinkPreview() {
		return linkPreview != null;
	}
}
