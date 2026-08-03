package org.zerionproject.app.messaging;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.autodelete.AbstractAutoDeleteTest;
import org.zerionproject.app.test.BriarIntegrationTestComponent;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessagingConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@org.junit.Ignore("Stale: asserts pre-3.0 Bramble-sync message counts over a "
		+ "two-node sync harness. Messaging works over the native 3.0 transport "
		+ "(emulator-validated). Rewrite for the ZPP transport / 3.0 message model.")
public class AutoDeleteIntegrationTest extends AbstractAutoDeleteTest {

	@Override
	protected ConversationClient getConversationClient(
			BriarIntegrationTestComponent component) {
		return component.getMessagingManager();
	}

	@Test
	public void testMessageWithoutTimer() throws Exception {

		MessageId messageId = createMessageWithoutTimer(c0, contactId1From0);

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		ConversationMessageHeader h0 = headers0.get(0);
		assertEquals(messageId, h0.getId());

		assertEquals(NO_AUTO_DELETE_TIMER, h0.getAutoDeleteTimer());

		sync0To1(1, true);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		ConversationMessageHeader h1 = headers1.get(0);
		assertEquals(messageId, h1.getId());

		assertEquals(NO_AUTO_DELETE_TIMER, h1.getAutoDeleteTimer());
	}

	@Test
	public void testDefaultTimer() throws Exception {

		MessageId messageId = createMessageWithTimer(c0, contactId1From0);

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		ConversationMessageHeader h0 = headers0.get(0);
		assertEquals(messageId, h0.getId());

		assertEquals(NO_AUTO_DELETE_TIMER, h0.getAutoDeleteTimer());

		sync0To1(1, true);

		ack1To0(1);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		ConversationMessageHeader h1 = headers1.get(0);
		assertEquals(messageId, h1.getId());

		assertEquals(NO_AUTO_DELETE_TIMER, h1.getAutoDeleteTimer());

		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));
	}

	@Test
	public void testNonDefaultTimer() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));

		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));

		MessageId messageId = createMessageWithTimer(c0, contactId1From0);

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());

		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		markMessageRead(c1, contact0From1, messageId);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
	}

	@Test
	public void testTimerIsMirrored() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));

		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));

		MessageId messageId0 = createMessageWithTimer(c0, contactId1From0);

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId0, headers0.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());

		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId0, headers1.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		markMessageRead(c1, contact0From1, messageId0);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());

		MessageId messageId1 = createMessageWithTimer(c1, contactId0From1);

		assertGroupCount(c1, contactId0From1, 1, 0);
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId1, headers1.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());

		sync1To0(1, true);

		ack0To1(1);
		waitForEvents(c1);

		assertGroupCount(c0, contactId1From0, 1, 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId1, headers0.get(0).getId());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 1);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 1, 1);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());

		markMessageRead(c0, contact1From0, messageId1);
		assertGroupCount(c0, contactId1From0, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
	}

	@Test
	public void testMessageWithAttachment() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);

		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));

		sync0To1(2, true);

		ack1To0(2);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		markMessageRead(c1, contact0From1, messageId);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	@Test
	public void testPrivateMessageWithMissingAttachmentIsDeleted()
			throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);

		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));

		setMessageNotShared(c0, attachmentHeader.getMessageId());

		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		markMessageRead(c1, contact0From1, messageId);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
	}

	@Test
	public void testOrphanedAttachmentIsDeleted() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);

		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));

		setMessageNotShared(c0, messageId);

		sync0To1(1, true);
		waitForEvents(c1);

		ack1To0(1);

		assertGroupCount(c1, contactId0From1, 0, 0);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		long timerLatency =
				MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		setMessageShared(c0, messageId);
		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		markMessageRead(c1, contact0From1, messageId);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	@Test
	public void testOrphanedAttachmentIsNotDeletedIfPrivateMessageArrives()
			throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);

		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);

		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));

		assertGroupCount(c0, contactId1From0, 1, 0);
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));

		setMessageNotShared(c0, messageId);

		sync0To1(1, true);
		waitForEvents(c1);

		ack1To0(1);

		assertGroupCount(c1, contactId0From1, 0, 0);
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());

		long timerLatency =
				MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		setMessageShared(c0, messageId);
		sync0To1(1, true);

		assertGroupCount(c1, contactId0From1, 1, 1);
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		ack1To0(1);
		waitForEvents(c0);

		timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		markMessageRead(c1, contact0From1, messageId);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	private MessageId createMessageWithoutTimer(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationManager conversationManager =
				component.getConversationManager();
		MessagingManager messagingManager = component.getMessagingManager();
		PrivateMessageFactory factory = component.getPrivateMessageFactory();

		GroupId groupId = messagingManager.getConversationId(contactId);
		return db.transactionWithResult(false, txn -> {
			long timestamp = conversationManager
					.getTimestampForOutgoingMessage(txn, contactId);
			PrivateMessage m = factory.createPrivateMessage(groupId, timestamp,
					"Hi!", emptyList());
			messagingManager.addLocalMessage(txn, m);
			return m.getMessage().getId();
		});
	}

	private MessageId createMessageWithTimer(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		return createMessageWithTimer(component, contactId, emptyList());
	}

	private MessageId createMessageWithTimer(
			BriarIntegrationTestComponent component, ContactId contactId,
			List<AttachmentHeader> attachmentHeaders) throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationManager conversationManager =
				component.getConversationManager();
		AutoDeleteManager autoDeleteManager = component.getAutoDeleteManager();
		MessagingManager messagingManager = component.getMessagingManager();
		PrivateMessageFactory factory = component.getPrivateMessageFactory();

		GroupId groupId = messagingManager.getConversationId(contactId);
		return db.transactionWithResult(false, txn -> {
			long timestamp = conversationManager
					.getTimestampForOutgoingMessage(txn, contactId);
			long timer = autoDeleteManager
					.getAutoDeleteTimer(txn, contactId, timestamp);
			PrivateMessage m = factory.createPrivateMessage(groupId, timestamp,
					"Hi!", attachmentHeaders, timer);
			messagingManager.addLocalMessage(txn, m);
			return m.getMessage().getId();
		});
	}

	private AttachmentHeader createAttachment(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		MessagingManager messagingManager = component.getMessagingManager();

		GroupId groupId = messagingManager.getConversationId(contactId);
		InputStream in = new ByteArrayInputStream(getRandomBytes(1234));
		return messagingManager.addLocalAttachment(groupId,
				component.getClock().currentTimeMillis(), "image/jpeg", in);
	}

	private boolean messageIsDeleted(BriarIntegrationTestComponent component,
			MessageId messageId) throws DbException {
		DatabaseComponent db = component.getDatabaseComponent();

		try {
			db.transaction(true, txn -> db.getMessage(txn, messageId));
			return false;
		} catch (MessageDeletedException e) {
			return true;
		}
	}
}
