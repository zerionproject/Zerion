package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.briar.test.BriarIntegrationTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.briarproject.briar.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import static java.util.Collections.emptySet;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.INVITE_SENT;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHARING;
import static org.briarproject.briar.test.BriarTestUtils.assertGroupCount;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GroupInvitationIntegrationTest
		extends BriarIntegrationTest<BriarIntegrationTestComponent> {

	private PrivateGroup privateGroup;
	private PrivateGroupManager groupManager0, groupManager1;
	private GroupInvitationManager groupInvitationManager0,
			groupInvitationManager1;
	private ConversationManager conversationManager1;
	private Group g1From0, g0From1;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();

		groupManager0 = c0.getPrivateGroupManager();
		groupManager1 = c1.getPrivateGroupManager();
		groupInvitationManager0 = c0.getGroupInvitationManager();
		groupInvitationManager1 = c1.getGroupInvitationManager();
		conversationManager1 = c1.getConversationManager();
		g1From0 = groupInvitationManager0.getContactGroup(contact1From0);
		g0From1 = groupInvitationManager1.getContactGroup(contact0From1);

		privateGroup =
				privateGroupFactory.createPrivateGroup("Testgroup", author0);
		long joinTime = c0.getClock().currentTimeMillis();
		GroupMessage joinMsg0 = groupMessageFactory
				.createJoinMessage(privateGroup.getId(), joinTime, author0);
		groupManager0.addPrivateGroup(privateGroup, joinMsg0, true);
	}

	@Override
	protected void createComponents() {
		BriarIntegrationTestComponent component =
				DaggerBriarIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		c0 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t0Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		c1 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t1Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		c2 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t2Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);
	}

	@Test
	public void testSendInvitation() throws Exception {
		long timestamp = c0.getClock().currentTimeMillis();
		String text = "Hi!";
		sendInvitation(timestamp, text);

		sync0To1(1, true);

		Collection<GroupInvitationItem> invitations =
				groupInvitationManager1.getInvitations();
		assertEquals(1, invitations.size());
		GroupInvitationItem item = invitations.iterator().next();
		assertEquals(contact0From1, item.getCreator());
		assertEquals(privateGroup, item.getShareable());
		assertEquals(privateGroup.getId(), item.getId());
		assertEquals(privateGroup.getName(), item.getName());
		assertFalse(item.isSubscribed());

		Collection<ConversationMessageHeader> messages = getMessages0From1();
		assertEquals(1, messages.size());
		GroupInvitationRequest request =
				(GroupInvitationRequest) messages.iterator().next();
		assertEquals(text, request.getText());
		assertEquals(author0, request.getNameable().getCreator());
		assertEquals(timestamp, request.getTimestamp());
		assertEquals(privateGroup.getName(), request.getNameable().getName());
		assertFalse(request.isLocal());
		assertFalse(request.isRead());
		assertFalse(request.canBeOpened());
		assertFalse(request.wasAnswered());
	}

	@Test
	public void testInvitationDecline() throws Exception {
		long timestamp = c0.getClock().currentTimeMillis();
		sendInvitation(timestamp, null);

		sync0To1(1, true);
		assertFalse(groupInvitationManager1.getInvitations().isEmpty());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);

		Collection<ConversationMessageHeader> messages = getMessages0From1();
		assertEquals(2, messages.size());
		boolean foundResponse = false;
		for (ConversationMessageHeader m : messages) {
			if (m instanceof GroupInvitationResponse) {
				foundResponse = true;
				GroupInvitationResponse response = (GroupInvitationResponse) m;
				assertEquals(privateGroup.getId(), response.getShareableId());
				assertTrue(response.isLocal());
				assertFalse(response.wasAccepted());
			}
		}
		assertTrue(foundResponse);

		sync1To0(1, true);

		messages = getMessages1From0();
		assertEquals(2, messages.size());
		foundResponse = false;
		for (ConversationMessageHeader m : messages) {
			if (m instanceof GroupInvitationResponse) {
				foundResponse = true;
				GroupInvitationResponse response = (GroupInvitationResponse) m;
				assertEquals(privateGroup.getId(), response.getShareableId());
				assertFalse(response.isLocal());
				assertFalse(response.wasAccepted());
			}
		}
		assertTrue(foundResponse);

		assertTrue(groupInvitationManager1.getInvitations().isEmpty());

		assertEquals(0, groupManager1.getPrivateGroups().size());
	}

	@Test
	public void testInvitationDeclineWithAutoDelete() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		setAutoDeleteTimer(c1, contactId0From1, MIN_AUTO_DELETE_TIMER_MS);

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		assertTrue(groupManager1.getPrivateGroups().isEmpty());

		for (ConversationMessageHeader h : getMessages1From0()) {
			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		}
		for (ConversationMessageHeader h : getMessages0From1()) {
			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		}
	}

	@Test
	public void testInvitationAccept() throws Exception {
		long timestamp = c0.getClock().currentTimeMillis();
		sendInvitation(timestamp, null);

		Collection<ConversationMessageHeader> messages = getMessages1From0();
		assertEquals(1, messages.size());
		assertMessageState(messages.iterator().next(), true, false, false);

		sync0To1(1, true);
		assertFalse(groupInvitationManager1.getInvitations().isEmpty());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);

		messages = getMessages0From1();
		assertEquals(2, messages.size());
		boolean foundResponse = false;
		for (ConversationMessageHeader m : messages) {
			if (m instanceof GroupInvitationResponse) {
				foundResponse = true;
				GroupInvitationResponse response = (GroupInvitationResponse) m;
				assertMessageState(response, true, false, false);
				assertEquals(privateGroup.getId(), response.getShareableId());
				assertTrue(response.wasAccepted());
			} else {
				GroupInvitationRequest request = (GroupInvitationRequest) m;
				assertEquals(privateGroup, request.getNameable());
				assertTrue(request.wasAnswered());
				assertTrue(request.canBeOpened());
			}
		}
		assertTrue(foundResponse);

		sync1To0(1, true);

		messages = getMessages1From0();
		assertEquals(2, messages.size());
		foundResponse = false;
		for (ConversationMessageHeader m : messages) {
			if (m instanceof GroupInvitationResponse) {
				foundResponse = true;
				GroupInvitationResponse response = (GroupInvitationResponse) m;
				assertEquals(privateGroup.getId(), response.getShareableId());
				assertTrue(response.wasAccepted());
			}
		}
		assertTrue(foundResponse);

		assertTrue(groupInvitationManager1.getInvitations().isEmpty());

		Collection<PrivateGroup> groups = groupManager1.getPrivateGroups();
		assertEquals(1, groups.size());
		assertEquals(privateGroup, groups.iterator().next());
	}

	@Test
	public void testInvitationAcceptWithAutoDelete() throws Exception {

		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		setAutoDeleteTimer(c1, contactId0From1, MIN_AUTO_DELETE_TIMER_MS);

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		sync1To0(1, true);

		Collection<PrivateGroup> groups = groupManager1.getPrivateGroups();
		assertEquals(1, groups.size());
		assertEquals(privateGroup, groups.iterator().next());

		for (ConversationMessageHeader h : getMessages1From0()) {
			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		}
		for (ConversationMessageHeader h : getMessages0From1()) {
			assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer());
		}
	}

	@Test
	public void testGroupCount() throws Exception {
		long timestamp = c0.getClock().currentTimeMillis();
		sendInvitation(timestamp, null);

		Group g1 = groupInvitationManager0.getContactGroup(contact1From0);
		assertGroupCount(messageTracker0, g1.getId(), 1, 0, timestamp);

		sync0To1(1, true);

		Group g0 = groupInvitationManager1.getContactGroup(contact0From1);
		assertGroupCount(messageTracker1, g0.getId(), 1, 1, timestamp);
		ConversationMessageHeader m = getMessages0From1().iterator().next();

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);

		assertGroupCount(messageTracker1, g0.getId(), 2, 1);

		conversationManager1.setReadFlag(g0.getId(), m.getId(), true);
		assertGroupCount(messageTracker1, g0.getId(), 2, 0);

		sync1To0(1, true);

		assertGroupCount(messageTracker0, g1.getId(), 2, 1);
	}

	@Test
	public void testMultipleInvitations() throws Exception {
		sendInvitation(c0.getClock().currentTimeMillis(), null);

		assertEquals(INVITE_SENT, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

		sync0To1(1, true);
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		assertEquals(SHAREABLE, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

		sendInvitation(c0.getClock().currentTimeMillis(), "Second Invitation");
		sync0To1(1, true);
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		sync1To0(1, true);

		assertEquals(SHARING, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

		try {
			sendInvitation(c0.getClock().currentTimeMillis(),
					"Third Invitation");
			fail();
		} catch (ProtocolStateException e) {

		}
	}

	@Test(expected = ProtocolStateException.class)
	public void testInvitationsWithSameTimestamp() throws Exception {
		long timestamp = c0.getClock().currentTimeMillis();
		sendInvitation(timestamp, null);
		sync0To1(1, true);

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		sendInvitation(timestamp, "Second Invitation");
		sync0To1(1, true);

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
	}

	@Test(expected = ProtocolStateException.class)
	public void testCreatorLeavesBeforeInvitationAccepted() throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);

		sync0To1(1, true);

		assertEquals(1, groupManager0.getPrivateGroups().size());
		groupManager0.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager0.getPrivateGroups().size());

		sync0To1(1, true);

		assertEquals(0, groupManager1.getPrivateGroups().size());
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
	}

	@Test
	public void testCreatorLeavesBeforeInvitationDeclined() throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);

		sync0To1(1, true);

		assertEquals(1, groupManager0.getPrivateGroups().size());
		groupManager0.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager0.getPrivateGroups().size());

		sync0To1(1, true);

		assertTrue(groupInvitationManager1.getInvitations().isEmpty());

		assertEquals(0, groupManager1.getPrivateGroups().size());
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
	}

	@Test
	public void testCreatorLeavesConcurrentlyWithInvitationAccepted()
			throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);

		sync0To1(1, true);

		assertEquals(1, groupManager0.getPrivateGroups().size());
		groupManager0.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager0.getPrivateGroups().size());

		assertEquals(0, groupManager1.getPrivateGroups().size());
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		assertEquals(1, groupManager1.getPrivateGroups().size());
		assertFalse(groupManager1.isDissolved(privateGroup.getId()));

		sync1To0(1, true);

		sync0To1(1, true);

		assertTrue(groupManager1.isDissolved(privateGroup.getId()));
	}

	@Test
	public void testCreatorLeavesConcurrentlyWithInvitationDeclined()
			throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);

		sync0To1(1, true);

		assertEquals(1, groupManager0.getPrivateGroups().size());
		groupManager0.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager0.getPrivateGroups().size());

		assertEquals(0, groupManager1.getPrivateGroups().size());
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		assertEquals(0, groupManager1.getPrivateGroups().size());

		sync1To0(1, true);

		sync0To1(1, true);
	}

	@Test
	public void testCreatorLeavesConcurrentlyWithMemberLeaving()
			throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);

		sync0To1(1, true);

		assertEquals(0, groupManager1.getPrivateGroups().size());
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		assertEquals(1, groupManager1.getPrivateGroups().size());

		sync1To0(1, true);

		sync0To1(2, true);

		sync1To0(1, true);

		assertEquals(1, groupManager0.getPrivateGroups().size());
		groupManager0.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager0.getPrivateGroups().size());

		groupManager1.removePrivateGroup(privateGroup.getId());
		assertEquals(0, groupManager1.getPrivateGroups().size());

		sync0To1(1, true);

		sync1To0(1, true);
	}

	@Test
	public void testDeletingAllMessagesWhenCompletingSession()
			throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		assertFalse(deleteAllMessages1From0().allDeleted());
		assertTrue(deleteAllMessages1From0().hasInvitationSessionInProgress());
		assertEquals(1, getMessages1From0().size());
		assertFalse(deleteAllMessages0From1().allDeleted());
		assertTrue(deleteAllMessages0From1().hasInvitationSessionInProgress());
		assertEquals(1, getMessages0From1().size());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		sync1To0(1, true);

		assertGroupCount(messageTracker0, g1From0.getId(), 2, 1);
		assertGroupCount(messageTracker1, g0From1.getId(), 2, 1);

		assertTrue(deleteAllMessages1From0().allDeleted());
		assertEquals(0, getMessages1From0().size());
		assertTrue(deleteAllMessages1From0()
				.allDeleted());
		assertGroupCount(messageTracker0, g1From0.getId(), 0, 0);

		assertFalse(deleteAllMessages0From1().allDeleted());
		assertTrue(deleteAllMessages0From1().hasInvitationSessionInProgress());
		assertEquals(2, getMessages0From1().size());

		sync0To1(2, true);

		assertTrue(deleteAllMessages0From1().allDeleted());
		assertEquals(0, getMessages0From1().size());

		assertTrue(deleteAllMessages0From1().allDeleted());
		assertGroupCount(messageTracker1, g0From1.getId(), 0, 0);

		groupManager1.removePrivateGroup(privateGroup.getId());
		sync1To0(1, true);

		assertEquals(0, getMessages1From0().size());
		assertEquals(0, getMessages0From1().size());
	}

	@Test
	public void testDeletingAllMessagesWhenDeclining() throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		Group g1From0 = groupInvitationManager0.getContactGroup(contact1From0);
		Group g0From1 = groupInvitationManager1.getContactGroup(contact0From1);
		assertGroupCount(messageTracker0, g1From0.getId(), 2, 1);
		assertGroupCount(messageTracker1, g0From1.getId(), 2, 1);

		assertTrue(deleteAllMessages1From0().allDeleted());
		assertEquals(0, getMessages1From0().size());

		assertTrue(deleteAllMessages1From0().allDeleted());

		assertFalse(deleteAllMessages0From1().allDeleted());
		assertTrue(deleteAllMessages0From1().hasInvitationSessionInProgress());
		assertEquals(2, getMessages0From1().size());

		ack0To1(1);

		assertTrue(deleteAllMessages0From1().allDeleted());
		assertEquals(0, getMessages0From1().size());

		assertTrue(deleteAllMessages0From1().allDeleted());
		assertGroupCount(messageTracker1, g0From1.getId(), 0, 0);

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		assertFalse(deleteAllMessages1From0().allDeleted());
		assertTrue(deleteAllMessages1From0().hasInvitationSessionInProgress());
		assertFalse(deleteAllMessages0From1().allDeleted());
		assertTrue(deleteAllMessages0From1().hasInvitationSessionInProgress());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		ack0To1(1);

		assertGroupCount(messageTracker1, g0From1.getId(), 2, 1);
		assertGroupCount(messageTracker0, g1From0.getId(), 2, 1);

		assertTrue(deleteAllMessages1From0().allDeleted());
		assertTrue(deleteAllMessages0From1().allDeleted());
		assertGroupCount(messageTracker1, g0From1.getId(), 0, 0);
		assertGroupCount(messageTracker0, g1From0.getId(), 0, 0);
	}

	@Test
	public void testDeletingSomeMessages() throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);

		Collection<ConversationMessageHeader> m0 = getMessages1From0();
		assertEquals(1, m0.size());
		MessageId messageId = m0.iterator().next().getId();
		Set<MessageId> toDelete = new HashSet<>();
		toDelete.add(messageId);
		assertFalse(deleteMessages1From0(toDelete).allDeleted());
		assertTrue(deleteMessages1From0(toDelete)
				.hasInvitationSessionInProgress());
		assertFalse(deleteMessages0From1(toDelete).allDeleted());
		assertTrue(deleteMessages0From1(toDelete)
				.hasInvitationSessionInProgress());

		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, false);
		sync1To0(1, true);

		assertFalse(deleteMessages1From0(toDelete).allDeleted());
		assertTrue(
				deleteMessages1From0(toDelete).hasNotAllInvitationSelected());
		assertFalse(deleteMessages0From1(toDelete).allDeleted());
		assertTrue(
				deleteMessages0From1(toDelete).hasNotAllInvitationSelected());

		m0 = getMessages1From0();
		assertEquals(2, m0.size());
		for (ConversationMessageHeader h : m0) {
			if (!h.getId().equals(messageId)) toDelete.add(h.getId());
		}
		assertGroupCount(messageTracker0, g1From0.getId(), 2, 1);
		assertTrue(deleteMessages1From0(toDelete).allDeleted());
		assertEquals(0, getMessages1From0().size());

		assertTrue(deleteMessages1From0(toDelete).allDeleted());
		assertGroupCount(messageTracker0, g1From0.getId(), 0, 0);

		assertFalse(deleteMessages0From1(toDelete).allDeleted());
		assertTrue(deleteMessages0From1(toDelete)
				.hasInvitationSessionInProgress());
		assertEquals(2, getMessages0From1().size());
		assertGroupCount(messageTracker1, g0From1.getId(), 2, 1);

		ack0To1(1);

		assertTrue(deleteMessages0From1(toDelete).allDeleted());
		assertEquals(0, getMessages0From1().size());
		assertGroupCount(messageTracker1, g0From1.getId(), 0, 0);

		assertTrue(deleteMessages0From1(toDelete).allDeleted());
	}

	@Test
	public void testDeletingEmptySet() throws Exception {
		assertTrue(deleteMessages0From1(emptySet()).allDeleted());
	}

	@Test
	public void testInvitationAfterReAddingContacts() throws Exception {

		sendInvitation(c0.getClock().currentTimeMillis(), null);
		sync0To1(1, true);
		groupInvitationManager1
				.respondToInvitation(contactId0From1, privateGroup, true);
		sync1To0(1, true);

		sync0To1(2, true);
		sync1To0(1, true);

		assertEquals(SHARING, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

		removeAllContacts();

		assertFalse(groupManager0.isDissolved(privateGroup.getId()));
		assertTrue(groupManager1.isDissolved(privateGroup.getId()));

		addDefaultContacts();

		assertEquals(SHARING, groupInvitationManager0
				.getSharingStatus(contact1From0, privateGroup.getId()));

		groupManager1.removePrivateGroup(privateGroup.getId());
	}

	private Collection<ConversationMessageHeader> getMessages1From0()
			throws DbException {
		return db0.transactionWithResult(true, txn -> groupInvitationManager0
				.getMessageHeaders(txn, contactId1From0));
	}

	private Collection<ConversationMessageHeader> getMessages0From1()
			throws DbException {
		return db1.transactionWithResult(true, txn -> groupInvitationManager1
				.getMessageHeaders(txn, contactId0From1));
	}

	private DeletionResult deleteAllMessages1From0() throws DbException {
		return db0.transactionWithResult(false, txn -> groupInvitationManager0
				.deleteAllMessages(txn, contactId1From0));
	}

	private DeletionResult deleteAllMessages0From1() throws DbException {
		return db1.transactionWithResult(false, txn -> groupInvitationManager1
				.deleteAllMessages(txn, contactId0From1));
	}

	private DeletionResult deleteMessages1From0(Set<MessageId> toDelete)
			throws DbException {
		return db0.transactionWithResult(false, txn -> groupInvitationManager0
				.deleteMessages(txn, contactId1From0, toDelete));
	}

	private DeletionResult deleteMessages0From1(Set<MessageId> toDelete)
			throws DbException {
		return db1.transactionWithResult(false, txn -> groupInvitationManager1
				.deleteMessages(txn, contactId0From1, toDelete));
	}

	private void sendInvitation(long timestamp, @Nullable String text)
			throws DbException {
		byte[] signature = groupInvitationFactory.signInvitation(contact1From0,
				privateGroup.getId(), timestamp, author0.getPrivateKey());
		long timer = getAutoDeleteTimer(c0, contactId1From0, timestamp);
		groupInvitationManager0.sendInvitation(privateGroup.getId(),
				contactId1From0, text, timestamp, signature, timer);
	}

}
