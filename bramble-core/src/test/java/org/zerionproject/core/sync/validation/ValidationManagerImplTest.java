package org.zerionproject.core.sync.validation;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchGroupException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.sync.validation.MessageValidator;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.ImmediateExecutor;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_SHARE;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.INVALID;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
import static org.zerionproject.core.api.sync.validation.MessageState.UNKNOWN;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class ValidationManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final MessageValidator validator =
			context.mock(MessageValidator.class);
	private final IncomingMessageHook hook =
			context.mock(IncomingMessageHook.class);

	private final Executor dbExecutor = new ImmediateExecutor();
	private final Executor validationExecutor = new ImmediateExecutor();
	private final ClientId clientId = getClientId();
	private final int majorVersion = 123;
	private final Group group = getGroup(clientId, majorVersion);
	private final GroupId groupId = group.getId();
	private final Message message = getMessage(groupId);
	private final Message message1 = getMessage(groupId);
	private final Message message2 = getMessage(groupId);
	private final MessageId messageId = message.getId();
	private final MessageId messageId1 = message1.getId();
	private final MessageId messageId2 = message2.getId();

	private final Metadata metadata = new Metadata();
	private final MessageContext validResult = new MessageContext(metadata);
	private final ContactId contactId = getContactId();
	private final MessageContext validResultWithDependencies =
			new MessageContext(metadata, singletonList(messageId1));

	private final ValidationManagerImpl vm =
			new ValidationManagerImpl(db, dbExecutor, validationExecutor);

	public ValidationManagerImplTest() {
		vm.registerMessageValidator(clientId, majorVersion, validator);
		vm.registerIncomingMessageHook(clientId, majorVersion, hook);
	}

	@Test
	public void testStartAndStop() throws Exception {
		expectGetMessagesToValidate();
		expectGetPendingMessages();
		expectGetMessagesToShare();

		vm.startService();
		vm.stopService();
	}

	@Test
	public void testMessagesAreValidatedAtStartup() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, true);
		Transaction txn3 = new Transaction(null, false);

		expectGetMessagesToValidate(messageId, messageId1);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn2));
			oneOf(db).getMessage(txn2, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));

			oneOf(db).transaction(with(false), withDbRunnable(txn3));
			oneOf(db).getMessageState(txn3, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn3, messageId1, INVALID);
			oneOf(db).deleteMessage(txn3, messageId1);
			oneOf(db).deleteMessageMetadata(txn3, messageId1);

			oneOf(db).getMessageDependents(txn3, messageId1);
			will(returnValue(emptyMap()));
		}});

		expectGetPendingMessages();
		expectGetMessagesToShare();

		vm.startService();
	}

	@Test
	public void testPendingMessagesAreDeliveredAtStartup() throws Exception {
		Transaction txn = new Transaction(null, false);
		Transaction txn1 = new Transaction(null, false);

		expectGetMessagesToValidate();
		expectGetPendingMessages(messageId);

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).getMessageState(txn, messageId);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));

			oneOf(db).getMessage(txn, messageId);
			will(returnValue(message));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn, messageId);
			will(returnValue(new Metadata()));

			oneOf(hook).incomingMessage(txn, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn, messageId);
			will(returnValue(singletonMap(messageId2, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).getMessageState(txn1, messageId2);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn1, messageId2);
			will(returnValue(singletonMap(messageId1, DELIVERED)));

			oneOf(db).getMessage(txn1, messageId2);
			will(returnValue(message2));
			oneOf(db).getGroup(txn1, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn1, messageId2);
			will(returnValue(metadata));

			oneOf(hook).incomingMessage(txn1, message2, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId2, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId2);
			will(returnValue(emptyMap()));
		}});

		expectGetMessagesToShare();

		vm.startService();
	}

	@Test
	public void testMessagesAreSharedAtStartup() throws Exception {
		Transaction txn = new Transaction(null, false);
		Transaction txn1 = new Transaction(null, false);

		expectGetMessagesToValidate();
		expectGetPendingMessages();
		expectGetMessagesToShare(messageId);

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).setMessageShared(txn, messageId);
			oneOf(db).getMessageDependencies(txn, messageId);
			will(returnValue(singletonMap(messageId2, DELIVERED)));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).setMessageShared(txn1, messageId2);
			oneOf(db).getMessageDependencies(txn1, messageId2);
			will(returnValue(emptyMap()));
		}});

		vm.startService();
	}

	@Test
	public void testIncomingMessagesAreShared() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));

			oneOf(db).setMessageShared(txn1, messageId);

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).setMessageShared(txn2, messageId1);
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testValidationContinuesAfterNoSuchMessageException()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, false);

		expectGetMessagesToValidate(messageId, messageId1);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getMessage(txn, messageId);
			will(throwException(new NoSuchMessageException()));

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn1));
			oneOf(db).getMessage(txn1, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn1, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn2, messageId1, INVALID);
			oneOf(db).deleteMessage(txn2, messageId1);
			oneOf(db).deleteMessageMetadata(txn2, messageId1);

			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(emptyMap()));
		}});

		expectGetPendingMessages();
		expectGetMessagesToShare();

		vm.startService();
	}

	@Test
	public void testValidationContinuesAfterNoSuchGroupException()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, true);
		Transaction txn2 = new Transaction(null, false);

		expectGetMessagesToValidate(messageId, messageId1);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getMessage(txn, messageId);
			will(returnValue(message));

			oneOf(db).getGroup(txn, groupId);
			will(throwException(new NoSuchGroupException()));

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn1));
			oneOf(db).getMessage(txn1, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn1, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message1, group);
			will(throwException(new InvalidMessageException()));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn2, messageId1, INVALID);
			oneOf(db).deleteMessage(txn2, messageId1);
			oneOf(db).deleteMessageMetadata(txn2, messageId1);

			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(emptyMap()));
		}});

		expectGetPendingMessages();
		expectGetMessagesToShare();

		vm.startService();
	}

	@Test
	public void testNonLocalMessagesAreValidatedWhenAdded() throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testLocalMessagesAreNotValidatedWhenAdded() {
		vm.eventOccurred(new MessageAddedEvent(message, null));
	}

	@Test
	public void testMessagesWithUndeliveredDependenciesArePending()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, UNKNOWN)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);
			oneOf(db).setMessageState(txn1, messageId, PENDING);
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testMessagesWithDeliveredDependenciesGetDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());
			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, DELIVERED)));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testMessagesWithInvalidDependenciesAreInvalid()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResultWithDependencies));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).addMessageDependencies(txn1, message,
					validResultWithDependencies.getDependencies());

			oneOf(db).getMessageDependencies(txn1, messageId);
			will(returnValue(singletonMap(messageId1, INVALID)));

			oneOf(db).getMessageState(txn1, messageId);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn1, messageId, INVALID);
			oneOf(db).deleteMessage(txn1, messageId);
			oneOf(db).deleteMessageMetadata(txn1, messageId);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(singletonMap(messageId2, UNKNOWN)));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId2);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn2, messageId2, INVALID);
			oneOf(db).deleteMessage(txn2, messageId2);
			oneOf(db).deleteMessageMetadata(txn2, messageId2);
			oneOf(db).getMessageDependents(txn2, messageId2);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testRecursiveInvalidation() throws Exception {
		MessageId messageId3 = new MessageId(getRandomId());
		MessageId messageId4 = new MessageId(getRandomId());
		Map<MessageId, MessageState> twoDependents = new LinkedHashMap<>();
		twoDependents.put(messageId1, PENDING);
		twoDependents.put(messageId2, PENDING);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, false);
		Transaction txn5 = new Transaction(null, false);
		Transaction txn6 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(throwException(new InvalidMessageException()));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).getMessageState(txn1, messageId);
			will(returnValue(UNKNOWN));
			oneOf(db).setMessageState(txn1, messageId, INVALID);
			oneOf(db).deleteMessage(txn1, messageId);
			oneOf(db).deleteMessageMetadata(txn1, messageId);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(twoDependents));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn2, messageId1, INVALID);
			oneOf(db).deleteMessage(txn2, messageId1);
			oneOf(db).deleteMessageMetadata(txn2, messageId1);

			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(singletonMap(messageId3, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn3));
			oneOf(db).getMessageState(txn3, messageId2);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn3, messageId2, INVALID);
			oneOf(db).deleteMessage(txn3, messageId2);
			oneOf(db).deleteMessageMetadata(txn3, messageId2);

			oneOf(db).getMessageDependents(txn3, messageId2);
			will(returnValue(singletonMap(messageId3, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn4));
			oneOf(db).getMessageState(txn4, messageId3);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn4, messageId3, INVALID);
			oneOf(db).deleteMessage(txn4, messageId3);
			oneOf(db).deleteMessageMetadata(txn4, messageId3);

			oneOf(db).getMessageDependents(txn4, messageId3);
			will(returnValue(singletonMap(messageId4, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn5));
			oneOf(db).getMessageState(txn5, messageId3);
			will(returnValue(INVALID));

			oneOf(db).transaction(with(false), withDbRunnable(txn6));
			oneOf(db).getMessageState(txn6, messageId4);
			will(returnValue(PENDING));
			oneOf(db).setMessageState(txn6, messageId4, INVALID);
			oneOf(db).deleteMessage(txn6, messageId4);
			oneOf(db).deleteMessageMetadata(txn6, messageId4);

			oneOf(db).getMessageDependents(txn6, messageId4);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testPendingDependentsGetDelivered() throws Exception {
		Message message3 = getMessage(groupId);
		Message message4 = getMessage(groupId);
		MessageId messageId3 = message3.getId();
		MessageId messageId4 = message4.getId();
		Map<MessageId, MessageState> twoDependents = new LinkedHashMap<>();
		twoDependents.put(messageId1, PENDING);
		twoDependents.put(messageId2, PENDING);
		Map<MessageId, MessageState> twoDependencies = new LinkedHashMap<>();
		twoDependencies.put(messageId1, DELIVERED);
		twoDependencies.put(messageId2, DELIVERED);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);
		Transaction txn3 = new Transaction(null, false);
		Transaction txn4 = new Transaction(null, false);
		Transaction txn5 = new Transaction(null, false);
		Transaction txn6 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(twoDependents));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(singletonMap(messageId, DELIVERED)));

			oneOf(db).getMessage(txn2, messageId1);
			will(returnValue(message1));
			oneOf(db).getGroup(txn2, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn2, messageId1);
			will(returnValue(metadata));

			oneOf(hook).incomingMessage(txn2, message1, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn2, messageId1, DELIVERED);

			oneOf(db).getMessageDependents(txn2, messageId1);
			will(returnValue(singletonMap(messageId3, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn3));
			oneOf(db).getMessageState(txn3, messageId2);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn3, messageId2);
			will(returnValue(singletonMap(messageId, DELIVERED)));

			oneOf(db).getMessage(txn3, messageId2);
			will(returnValue(message2));
			oneOf(db).getGroup(txn3, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn3, messageId2);
			will(returnValue(metadata));

			oneOf(hook).incomingMessage(txn3, message2, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn3, messageId2, DELIVERED);

			oneOf(db).getMessageDependents(txn3, messageId2);
			will(returnValue(singletonMap(messageId3, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn4));
			oneOf(db).getMessageState(txn4, messageId3);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn4, messageId3);
			will(returnValue(twoDependencies));

			oneOf(db).getMessage(txn4, messageId3);
			will(returnValue(message3));
			oneOf(db).getGroup(txn4, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn4, messageId3);
			will(returnValue(metadata));

			oneOf(hook).incomingMessage(txn4, message3, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn4, messageId3, DELIVERED);

			oneOf(db).getMessageDependents(txn4, messageId3);
			will(returnValue(singletonMap(messageId4, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn5));
			oneOf(db).getMessageState(txn5, messageId3);
			will(returnValue(DELIVERED));

			oneOf(db).transaction(with(false), withDbRunnable(txn6));
			oneOf(db).getMessageState(txn6, messageId4);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn6, messageId4);
			will(returnValue(singletonMap(messageId3, DELIVERED)));

			oneOf(db).getMessage(txn6, messageId4);
			will(returnValue(message4));
			oneOf(db).getGroup(txn6, groupId);
			will(returnValue(group));
			oneOf(db).getMessageMetadataForValidator(txn6, messageId4);
			will(returnValue(metadata));

			oneOf(hook).incomingMessage(txn6, message4, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn6, messageId4, DELIVERED);

			oneOf(db).getMessageDependents(txn6, messageId4);
			will(returnValue(emptyMap()));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	@Test
	public void testOnlyReadyPendingDependentsGetDelivered() throws Exception {
		Map<MessageId, MessageState> twoDependencies = new LinkedHashMap<>();
		twoDependencies.put(messageId, DELIVERED);
		twoDependencies.put(messageId2, UNKNOWN);
		Transaction txn = new Transaction(null, true);
		Transaction txn1 = new Transaction(null, false);
		Transaction txn2 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(group));

			oneOf(validator).validateMessage(message, group);
			will(returnValue(validResult));

			oneOf(db).transaction(with(false), withDbRunnable(txn1));
			oneOf(db).mergeMessageMetadata(txn1, messageId, metadata);

			oneOf(hook).incomingMessage(txn1, message, metadata);
			will(returnValue(ACCEPT_DO_NOT_SHARE));
			oneOf(db).setMessageState(txn1, messageId, DELIVERED);

			oneOf(db).getMessageDependents(txn1, messageId);
			will(returnValue(singletonMap(messageId1, PENDING)));

			oneOf(db).transaction(with(false), withDbRunnable(txn2));
			oneOf(db).getMessageState(txn2, messageId1);
			will(returnValue(PENDING));
			oneOf(db).getMessageDependencies(txn2, messageId1);
			will(returnValue(twoDependencies));
		}});

		vm.eventOccurred(new MessageAddedEvent(message, contactId));
	}

	private void expectGetMessagesToValidate(MessageId... ids)
			throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getMessagesToValidate(txn);
			will(returnValue(asList(ids)));
		}});
	}

	private void expectGetPendingMessages(MessageId... ids) throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getPendingMessages(txn);
			will(returnValue(asList(ids)));
		}});
	}

	private void expectGetMessagesToShare(MessageId... ids) throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getMessagesToShare(txn);
			will(returnValue(asList(ids)));
		}});
	}
}
