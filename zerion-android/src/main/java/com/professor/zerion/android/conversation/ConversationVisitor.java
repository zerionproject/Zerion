package com.professor.zerion.android.conversation;

import android.content.Context;

import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.briar.api.introduction.IntroductionRequest;
import org.briarproject.briar.api.introduction.IntroductionResponse;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

import static java.util.Collections.emptyList;
import static com.professor.zerion.android.conversation.ConversationRequestItem.RequestType.GROUP;
import static com.professor.zerion.android.conversation.ConversationRequestItem.RequestType.INTRODUCTION;
import static com.professor.zerion.android.util.UiUtils.getContactDisplayName;

import com.professor.zerion.android.conversation.voice.VoiceCallSignal;

@NotNullByDefault
class ConversationVisitor implements
		ConversationMessageVisitor<ConversationItem> {

	private final Context ctx;
	private final TextCache textCache;
	private final AttachmentCache attachmentCache;
	private final LiveData<String> contactName;
	@Nullable
	private final ConversationViewModel viewModel;
	private volatile boolean lazyAttachmentMode = false;

	ConversationVisitor(Context ctx, TextCache textCache,
			AttachmentCache attachmentCache, LiveData<String> contactName,
			@Nullable ConversationViewModel viewModel) {
		this.ctx = ctx;
		this.textCache = textCache;
		this.attachmentCache = attachmentCache;
		this.contactName = contactName;
		this.viewModel = viewModel;
	}

	
	void setLazyAttachmentMode(boolean lazy) {
		this.lazyAttachmentMode = lazy;
	}

	@Override
	@Nullable
	public ConversationItem visitPrivateMessageHeader(PrivateMessageHeader h) {
		if (h.hasText()) {
			String text = textCache.getText(h.getId());
			if (text != null) {
				if (text.startsWith("VOICE_CALL:")) {
					return parseVoiceCallMessage(text, h);
				}
				if (VoiceCallSignal.isSignal(text)) {
					return null;
				}
				if (ConversationSecretNoteItem.isSecretNoteText(text)) {
					ConversationSecretNoteItem secretItem =
							new ConversationSecretNoteItem(h, contactName);
					secretItem.setText(text);
					return secretItem;
				}
			}
		}

		ConversationMessageItem item;
		if (lazyAttachmentMode) {
			if (h.isLocal()) {
				item = new ConversationMessageItem(
						R.layout.list_item_conversation_msg_out, h, contactName);
			} else {
				item = new ConversationMessageItem(
						R.layout.list_item_conversation_msg_in, h, contactName);
			}
		} else {
			List<AttachmentItem> attachments;
			if (h.getAttachmentHeaders().isEmpty()) {
				attachments = emptyList();
			} else {
				attachments = attachmentCache.getAttachmentItems(h);
			}
			if (h.isLocal()) {
				item = new ConversationMessageItem(
						R.layout.list_item_conversation_msg_out, h, contactName,
						attachments);
			} else {
				item = new ConversationMessageItem(
						R.layout.list_item_conversation_msg_in, h, contactName,
						attachments);
			}
		}
		if (h.hasText()) {
			String text = textCache.getText(h.getId());
			if (text != null && !text.startsWith("VOICE_CALL:") &&
					!VoiceCallSignal.isSignal(text)) {
				item.setText(text);
			}
		}

		// Check local reply context first (sender side),
		// then check header's replyToId (receiver side)
		if (viewModel != null) {
			org.briarproject.bramble.api.Pair<MessageId, String> replyContext =
					viewModel.getReplyContext(h.getId());
			if (replyContext != null) {
				item.setReplyToMessageId(replyContext.getFirst());
				item.setReplyToText(replyContext.getSecond());
			} else if (h.getReplyToId() != null) {
				item.setReplyToMessageId(h.getReplyToId());
				String replyText = textCache.getText(h.getReplyToId());
				item.setReplyToText(
						replyText != null ? replyText : "[message]");
			}
		} else if (h.getReplyToId() != null) {
			item.setReplyToMessageId(h.getReplyToId());
			String replyText = textCache.getText(h.getReplyToId());
			item.setReplyToText(
					replyText != null ? replyText : "[message]");
		}

		return item;
	}
	private String getContactNameOrDefault() {
		String name = contactName.getValue();
		return name != null ? name : "";
	}

	@Override
	public ConversationItem visitGroupInvitationRequest(
			GroupInvitationRequest r) {
		if (r.isLocal()) {
			String text = ctx.getString(
					R.string.groups_invitations_invitation_sent,
					getContactNameOrDefault(), r.getName());
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_out, text,
					contactName, r);
		} else {
			String text = ctx.getString(
					R.string.groups_invitations_invitation_received,
					getContactNameOrDefault(), r.getName());
			return new ConversationRequestItem(
					R.layout.list_item_conversation_request, text, contactName,
					GROUP, r);
		}
	}

	@Override
	public ConversationItem visitGroupInvitationResponse(
			GroupInvitationResponse r) {
		if (r.isLocal()) {
			String text;
			if (r.wasAccepted()) {
				text = ctx.getString(
						R.string.groups_invitations_response_accepted_sent,
						getContactNameOrDefault());
			} else if (r.isAutoDecline()) {
				text = ctx.getString(
						R.string.groups_invitations_response_declined_auto,
						getContactNameOrDefault());
			} else {
				text = ctx.getString(
						R.string.groups_invitations_response_declined_sent,
						getContactNameOrDefault());
			}
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_out, text,
					contactName, r);
		} else {
			String text;
			if (r.wasAccepted()) {
				text = ctx.getString(
						R.string.groups_invitations_response_accepted_received,
						getContactNameOrDefault());
			} else {
				text = ctx.getString(
						R.string.groups_invitations_response_declined_received,
						getContactNameOrDefault());
			}
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_in, text,
					contactName, r);
		}
	}

	@Override
	public ConversationItem visitIntroductionRequest(IntroductionRequest r) {
		String name = getContactDisplayName(r.getNameable(), r.getAlias());
		if (r.isLocal()) {
			String text = ctx.getString(R.string.introduction_request_sent,
					getContactNameOrDefault(), name);
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_out, text,
					contactName, r);
		} else {
			String text;
			if (r.wasAnswered()) {
				text = ctx.getString(
						R.string.introduction_request_answered_received,
						getContactNameOrDefault(), name);
			} else if (r.isContact()) {
				text = ctx.getString(
						R.string.introduction_request_exists_received,
						getContactNameOrDefault(), name);
			} else {
				text = ctx.getString(R.string.introduction_request_received,
						getContactNameOrDefault(), name);
			}
			return new ConversationRequestItem(
					R.layout.list_item_conversation_request, text, contactName,
					INTRODUCTION, r);
		}
	}

	@Override
	public ConversationItem visitIntroductionResponse(IntroductionResponse r) {
		String introducedAuthor =
				getContactDisplayName(r.getIntroducedAuthor(),
						r.getIntroducedAuthorInfo().getAlias());
		if (r.isLocal()) {
			String text;
			if (r.wasAccepted()) {
				String suffix = r.canSucceed() ? "\n\n" + ctx.getString(
						R.string.introduction_response_accepted_sent_info,
						introducedAuthor) : "";
				text = ctx.getString(
						R.string.introduction_response_accepted_sent,
						introducedAuthor) + suffix;
			} else if (r.isAutoDecline()) {
				text = ctx.getString(
						R.string.introduction_response_declined_auto,
						introducedAuthor);
			} else {
				text = ctx.getString(
						R.string.introduction_response_declined_sent,
						introducedAuthor);
			}
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_out, text,
					contactName, r);
		} else {
			String text;
			if (r.wasAccepted()) {
				text = ctx.getString(
						R.string.introduction_response_accepted_received,
						getContactNameOrDefault(),
						introducedAuthor);
			} else if (r.isIntroducer()) {
				text = ctx.getString(
						R.string.introduction_response_declined_received,
						getContactNameOrDefault(),
						introducedAuthor);
			} else {
				text = ctx.getString(
						R.string.introduction_response_declined_received_by_introducee,
						getContactNameOrDefault(),
						introducedAuthor);
			}
			return new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_in, text,
					contactName, r);
		}
	}

	@Nullable
	private ConversationItem parseVoiceCallMessage(String text,
			PrivateMessageHeader h) {
		try {
			String payload = text.substring("VOICE_CALL:".length());
			String[] parts = payload.split(":", -1);

			if (parts.length < 2) {
				return new ConversationCallEventItem(
						R.layout.list_item_conversation_call_event,
						h,
						contactName,
						ConversationCallEventItem.CallEventType.CALL_END,
						"",
						null
				);
			}

			String typeStr = parts[0];
			String callId = parts[1];

			ConversationCallEventItem.CallEventType eventType;
			Long durationMs = null;

			switch (typeStr) {
				case "CALL_OFFER":
					eventType = ConversationCallEventItem.CallEventType.CALL_OFFER;
					break;
				case "CALL_ANSWER":
					eventType = ConversationCallEventItem.CallEventType.CALL_ANSWER;
					break;
				case "CALL_END":
					eventType = ConversationCallEventItem.CallEventType.CALL_END;
					if (parts.length >= 3) {
						try {
							durationMs = Long.parseLong(parts[2]);
						} catch (NumberFormatException e) {
						}
					}
					break;
				case "CALL_REJECT":
					eventType = ConversationCallEventItem.CallEventType.CALL_REJECT;
					break;
				default:
					return new ConversationCallEventItem(
							R.layout.list_item_conversation_call_event,
							h,
							contactName,
							ConversationCallEventItem.CallEventType.CALL_END,
							"",
							null
					);
			}

			return new ConversationCallEventItem(
					R.layout.list_item_conversation_call_event,
					h,
					contactName,
					eventType,
					callId,
					durationMs
			);

		} catch (Exception e) {
			return new ConversationCallEventItem(
					R.layout.list_item_conversation_call_event,
					h,
					contactName,
					ConversationCallEventItem.CallEventType.CALL_END,
					"",
					null
			);
		}
	}
	interface TextCache {
		@Nullable
		String getText(MessageId m);
	}

	interface AttachmentCache {
		List<AttachmentItem> getAttachmentItems(PrivateMessageHeader h);
	}
}
