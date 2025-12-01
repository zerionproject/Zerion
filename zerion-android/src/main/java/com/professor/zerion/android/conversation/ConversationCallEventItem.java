package com.professor.zerion.android.conversation;

import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@NotNullByDefault
public class ConversationCallEventItem extends ConversationItem {

	public enum CallEventType {
		CALL_OFFER,
		CALL_ANSWER,
		CALL_END,
		CALL_REJECT
	}

	private final CallEventType eventType;
	private final String callId;
	@Nullable
	private final Long durationMs;

	ConversationCallEventItem(@LayoutRes int layoutRes,
			ConversationMessageHeader h,
			LiveData<String> contactName,
			CallEventType eventType,
			String callId,
			@Nullable Long durationMs) {
		super(layoutRes, h, contactName);
		this.eventType = eventType;
		this.callId = callId;
		this.durationMs = durationMs;
	}

	public CallEventType getEventType() {
		return eventType;
	}

	public String getCallId() {
		return callId;
	}

	@Nullable
	public Long getDurationMs() {
		return durationMs;
	}

	public String getFormattedDuration() {
		if (durationMs == null) {
			return "";
		}

		long seconds = durationMs / 1000;
		if (seconds < 60) {
			return seconds + " sec";
		}

		long minutes = seconds / 60;
		long remainingSeconds = seconds % 60;
		return minutes + " min " + remainingSeconds + " sec";
	}

	public String getCallEventText() {
		boolean isOutgoing = !isIncoming();

		switch (eventType) {
			case CALL_OFFER:
				return isOutgoing ? "Outgoing secure voice call" : "Incoming secure voice call";
			case CALL_ANSWER:
				return isOutgoing ? "Outgoing secure voice call" : "Incoming secure voice call";
			case CALL_END:
				if (durationMs != null && durationMs > 0) {
					return "Secure voice call — " + getFormattedDuration();
				}
				return "Secure voice call";
			case CALL_REJECT:
				return isOutgoing ? "Call declined" : "Missed secure voice call";
			default:
				return "Voice call";
		}
	}
}
