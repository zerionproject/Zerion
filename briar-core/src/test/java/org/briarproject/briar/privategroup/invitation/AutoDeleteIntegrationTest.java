package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.event.GroupInvitationResponseReceivedEvent;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.briar.autodelete.AbstractAutoDeleteTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nullable;

import static org.briarproject.bramble.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHARING;
import static org.briarproject.briar.test.TestEventListener.assertEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AutoDeleteIntegrationTest extends AbstractAutoDeleteTest {

	private PrivateGroup privateGroup;
	private PrivateGroupManager groupManager0;
	private GroupInvitationManager groupInvitationManager0,
			groupInvitationManager1;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();
		groupManager0 = c0.getPrivateGroupManager();
		groupInvitationManager0 = c0.getGroupInvitationManager();
		groupInvitationManager1 = c1.getGroupInvitationManager();
		privateGroup = addPrivateGroup("Testgroup", startTime);
	}

	@Override
	protected ConversationClient getConversationClient(
			BriarIntegrationTestComponent component) {
		return component.getGroupInvitationManager();
	}

	@Test
	public void testInvitationAutoDecline() throws Exception {
		setAutoDeleteTimer(c0, contact1From0.getId(), MIN_AUTO_DELETE_TIMER_MS);

		sendInvitation(privateGroup, contact1From0.getId(), "Join this!");

		assertGroupCount(c0, contactId1From0, 1, 0);
		forEachHeader(c0, contactId1From0, 1, h -> {

			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		});

		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c1, contactId0From1, 1, 1);
		forEachHeader(c1, contactId0From1, 1, h -> {

			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		});

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

		ConversationMessagesDeletedEvent event = assertEvent(c0,
				ConversationMessagesDeletedEvent.class, () ->
						c0.getTimeTravel().addCurrentTimeMillis(1)
		);
		c1.getTimeTravel().addCurrentTimeMillis(1);

		assertEquals(contactId1From0, event.getContactId());

		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		final MessageId messageId0 =
				getMessageHeaders(c1, contactId0From1).get(0).getId();
		markMessageRead(c1, contact0From1, messageId0);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		GroupInvitationResponseReceivedEvent e = assertEvent(c1,
				GroupInvitationResponseReceivedEvent.class, () ->
						c1.getTimeTravel().addCurrentTimeMillis(1)
		);

		assertEquals(contactId0From1, e.getContactId());

		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		forEachHeader(c1, contactId0From1, 1, h -> {

			assertNotEquals(messageId0, h.getId());
			assertTrue(h instanceof GroupInvitationResponse);
			assertEquals(h.getId(), e.getMessageHeader().getId());
			assertFalse(((GroupInvitationResponse) h).wasAccepted());
			assertTrue(((GroupInvitationResponse) h).isAutoDecline());

			assertEquals(MIN_AUTO_DELETE_TIMER_MS,
					h.getAutoDeleteTimer());
		});

		sync1To0(1, true);

		ack0To1(1);
		waitForEvents(c1);

		assertEquals(SHAREABLE, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

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

		MessageId messageId1 =
				getMessageHeaders(c0, contactId1From0).get(0).getId();
		markMessageRead(c0, contact1From0, messageId1);
		assertGroupCount(c0, contactId1From0, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());

		assertEquals(SHAREABLE, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));
		sendInvitation(privateGroup, contact1From0.getId(),
				"Join this faster please!");
		sync0To1(1, true);
		assertGroupCount(c1, contactId0From1, 1, 1);
	}

	@Test
	public void testAutoDeleteDoesNotRemoveOtherSessions() throws Exception {
		PrivateGroup pg = addPrivateGroup("Another one", startTime + 1);

		sendInvitation(pg, contact1From0.getId(), null);
		sync0To1(1, true);
		ack1To0(1);
		waitForEvents(c0);

		assertGroupCount(c0, contactId1From0, 1, 0);
		assertGroupCount(c1, contactId0From1, 1, 1);

		forEachHeader(c0, contactId1From0, 1, h ->
				assertEquals(NO_AUTO_DELETE_TIMER, h.getAutoDeleteTimer()));
		forEachHeader(c1, contactId0From1, 1, h ->
				assertEquals(NO_AUTO_DELETE_TIMER, h.getAutoDeleteTimer()));

		setAutoDeleteTimer(c0, contact1From0.getId(), MIN_AUTO_DELETE_TIMER_MS);

		sendInvitation(privateGroup, contact1From0.getId(), "Join this!");
		sync0To1(1, true);
		ack1To0(1);
		waitForEvents(c0);
		assertGroupCount(c0, contactId1From0, 2, 0);
		assertGroupCount(c1, contactId0From1, 2, 2);

		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 2, 0);
		assertEquals(2, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 2, 2);
		assertEquals(2, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 2, 2);
		assertEquals(2, getMessageHeaders(c1, contactId0From1).size());

		forEachHeader(c1, contactId0From1, 2, h -> {
			try {
				markMessageRead(c1, contact0From1, h.getId());
			} catch (Exception e) {
				fail();
			}
		});
		assertGroupCount(c1, contactId0From1, 2, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 2, 0);
		assertEquals(2, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		GroupInvitationResponseReceivedEvent event = assertEvent(c1,
				GroupInvitationResponseReceivedEvent.class,
				() -> c1.getTimeTravel().addCurrentTimeMillis(1)
		);

		assertEquals(contactId0From1, event.getContactId());
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());

		assertGroupCount(c1, contactId0From1, 2, 0);
		forEachHeader(c1, contactId0From1, 2, h -> {
			if (h instanceof GroupInvitationRequest) {

				assertEquals(pg.getId(),
						((GroupInvitationRequest) h).getNameable().getId());
			} else {
				assertTrue(h instanceof GroupInvitationResponse);
				GroupInvitationResponse r = (GroupInvitationResponse) h;
				assertEquals(h.getId(), event.getMessageHeader().getId());

				assertEquals(privateGroup.getId(), r.getShareableId());
				assertTrue(r.isAutoDecline());
				assertFalse(r.wasAccepted());
			}
		});

		sync1To0(1, true);

		ack0To1(1);
		waitForEvents(c1);

		GroupInvitationResponse autoDeclineMessage = (GroupInvitationResponse)
				getMessageHeaders(c0, contactId1From0).get(1);
		markMessageRead(c0, contact1From0, autoDeclineMessage.getId());
		assertGroupCount(c0, contactId1From0, 2, 0);
		assertGroupCount(c1, contactId0From1, 2, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertGroupCount(c1, contactId0From1, 1, 0);

		groupInvitationManager1.respondToInvitation(contactId0From1, pg, true);

		sync1To0(1, true);

		sync0To1(2, true);
		waitForEvents(c1);
		assertGroupCount(c0, contactId1From0, 2, 1);
		assertGroupCount(c1, contactId0From1, 2, 0);
		forEachHeader(c1, contactId0From1, 2, h -> {
			if (h instanceof GroupInvitationRequest) {

				assertEquals(pg.getId(),
						((GroupInvitationRequest) h).getNameable().getId());
			} else {
				assertTrue(h instanceof GroupInvitationResponse);
				GroupInvitationResponse r = (GroupInvitationResponse) h;

				assertEquals(pg.getId(), r.getShareableId());
				assertFalse(r.isAutoDecline());
				assertTrue(r.wasAccepted());
			}
		});

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 2, 1);
		assertEquals(2, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 2, 0);
		assertEquals(2, getMessageHeaders(c1, contactId0From1).size());

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 2, 1);
		assertEquals(2, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		forEachHeader(c1, contactId0From1, 1, h -> {
			assertTrue(h instanceof GroupInvitationRequest);
			assertTrue(((GroupInvitationRequest) h).wasAnswered());
			assertTrue(((GroupInvitationRequest) h).canBeOpened());
		});

		forEachHeader(c0, contactId1From0, 2, h -> {
			try {
				if (!h.isRead()) markMessageRead(c0, contact1From0, h.getId());
			} catch (Exception e) {
				fail();
			}
		});
		assertGroupCount(c0, contactId1From0, 2, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertGroupCount(c0, contactId1From0, 2, 0);
		assertGroupCount(c1, contactId0From1, 1, 0);

		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertEquals(1, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 0);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		assertEquals(pg,
				c1.getPrivateGroupManager().getPrivateGroup(pg.getId()));
		assertEquals(SHARING, groupInvitationManager0
				.getSharingStatus(contact1From0, pg.getId()));
	}

	@Test
	public void testResponseAfterSenderDeletedInvitation() throws Exception {
		setAutoDeleteTimer(c0, contact1From0.getId(), MIN_AUTO_DELETE_TIMER_MS);

		sendInvitation(privateGroup, contact1From0.getId(), "Join this!");
		assertGroupCount(c0, contactId1From0, 1, 0);

		sync0To1(1, true);

		ack1To0(1);
		waitForEvents(c0);
		assertGroupCount(c1, contactId0From1, 1, 1);

		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertEquals(1, getMessageHeaders(c1, contactId0From1).size());

		markMessageRead(c1, contact0From1,
				getMessageHeaders(c1, contactId0From1).get(0).getId());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);

		sync1To0(1, true);

		ack0To1(1);
		waitForEvents(c1);
		assertGroupCount(c0, contactId1From0, 1, 1);
		assertGroupCount(c1, contactId0From1, 2, 0);

		GroupInvitationResponse message1 = (GroupInvitationResponse)
				getMessageHeaders(c0, contactId1From0).get(0);
		markMessageRead(c0, contact1From0, message1.getId());
		assertGroupCount(c0, contactId1From0, 1, 0);
		assertGroupCount(c1, contactId0From1, 2, 0);

		c0.getTimeTravel().addCurrentTimeMillis(timerLatency);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency);
		assertGroupCount(c0, contactId1From0, 0, 0);
		assertEquals(0, getMessageHeaders(c0, contactId1From0).size());
		assertGroupCount(c1, contactId0From1, 0, 0);
		assertEquals(0, getMessageHeaders(c1, contactId0From1).size());
	}

	private PrivateGroup addPrivateGroup(String name, long timestamp)
			throws DbException {
		PrivateGroup pg = privateGroupFactory.createPrivateGroup(name, author0);
		GroupMessage joinMsg0 = groupMessageFactory
				.createJoinMessage(pg.getId(), timestamp, author0);
		groupManager0.addPrivateGroup(pg, joinMsg0, true);
		return pg;
	}

	private void sendInvitation(PrivateGroup pg, ContactId contactId,
			@Nullable String text) throws DbException {
		DatabaseComponent db0 = c0.getDatabaseComponent();
		long timestamp = db0.transactionWithResult(true, txn ->
				c0.getConversationManager()
						.getTimestampForOutgoingMessage(txn, contactId));
		byte[] signature = groupInvitationFactory.signInvitation(contact1From0,
				pg.getId(), timestamp, author0.getPrivateKey());
		long timer = getAutoDeleteTimer(c0, contactId, timestamp);
		groupInvitationManager0.sendInvitation(pg.getId(), contactId, text,
				timestamp, signature, timer);
	}
}
