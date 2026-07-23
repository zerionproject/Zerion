package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.util.StringUtils.utf8IsTooLong;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;

@Immutable
@NotNullByDefault
class PrivateMessageFactoryImpl implements PrivateMessageFactory {

	private final ClientHelper clientHelper;

	@Inject
	PrivateMessageFactoryImpl(ClientHelper clientHelper) {
		this.clientHelper = clientHelper;
	}

	@Override
	public PrivateMessage createLegacyPrivateMessage(GroupId groupId,
			long timestamp, String text) throws FormatException {
		if (utf8IsTooLong(text, MAX_PRIVATE_MESSAGE_TEXT_LENGTH))
			throw new IllegalArgumentException();
		BdfList body = BdfList.of(text);
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		return new PrivateMessage(m);
	}

	@Override
	public PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers)
			throws FormatException {
		validateTextAndAttachmentHeaders(text, headers);
		BdfList attachmentList = serialiseAttachmentHeaders(headers);
		BdfList body = BdfList.of(PRIVATE_MESSAGE, text, attachmentList);
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		return new PrivateMessage(m, text != null, headers);
	}

	@Override
	public PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers,
			long autoDeleteTimer) throws FormatException {
		return createPrivateMessage(groupId, timestamp, text, headers,
				autoDeleteTimer, null);
	}

	@Override
	public PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers,
			long autoDeleteTimer, @Nullable MessageId replyToId)
			throws FormatException {
		validateTextAndAttachmentHeaders(text, headers);
		BdfList attachmentList = serialiseAttachmentHeaders(headers);
		Long timer = autoDeleteTimer == NO_AUTO_DELETE_TIMER ?
				null : autoDeleteTimer;
		BdfList body;
		if (replyToId != null) {
			body = BdfList.of(PRIVATE_MESSAGE, text, attachmentList, timer,
					replyToId.getBytes());
		} else {
			body = BdfList.of(PRIVATE_MESSAGE, text, attachmentList, timer);
		}
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		return new PrivateMessage(m, text != null, headers, autoDeleteTimer,
				replyToId);
	}

	private void validateTextAndAttachmentHeaders(@Nullable String text,
			List<AttachmentHeader> headers) {
		if (text == null) {
			if (headers.isEmpty()) throw new IllegalArgumentException();
		} else if (utf8IsTooLong(text, MAX_PRIVATE_MESSAGE_TEXT_LENGTH)) {
			throw new IllegalArgumentException();
		}
	}

	private BdfList serialiseAttachmentHeaders(List<AttachmentHeader> headers) {
		BdfList attachmentList = new BdfList();
		for (AttachmentHeader a : headers) {
			attachmentList.add(
					BdfList.of(a.getMessageId(), a.getContentType()));
		}
		return attachmentList;
	}
}
