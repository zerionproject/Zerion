package com.professor.zerion.android.conversation;

import org.zerionproject.app.api.conversation.ConversationMessageHeader;
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

	public String getFormattedDuration(android.content.Context ctx) {
		if (durationMs == null) {
			return "";
		}

		long seconds = durationMs / 1000;
		if (seconds < 60) {
			return ctx.getString(com.professor.zerion.R.string
					.call_duration_sec, seconds);
		}

		long minutes = seconds / 60;
		long remainingSeconds = seconds % 60;
		return ctx.getString(com.professor.zerion.R.string
				.call_duration_min_sec, minutes, remainingSeconds);
	}

	public String getCallEventText(android.content.Context ctx) {
		boolean isOutgoing = !isIncoming();

		switch (eventType) {
			case CALL_OFFER:
			case CALL_ANSWER:
				return ctx.getString(isOutgoing
						? com.professor.zerion.R.string.call_event_outgoing
						: com.professor.zerion.R.string.call_event_incoming);
			case CALL_END:
				if (durationMs != null && durationMs > 0) {
					return ctx.getString(com.professor.zerion.R.string
							.call_event_generic) + " · "
							+ getFormattedDuration(ctx);
				}
				return ctx.getString(com.professor.zerion.R.string
						.call_event_generic);
			case CALL_REJECT:
				return ctx.getString(isOutgoing
						? com.professor.zerion.R.string.call_event_declined
						: com.professor.zerion.R.string.call_event_missed);
			default:
				return ctx.getString(com.professor.zerion.R.string
						.call_event_generic);
		}
	}
}
