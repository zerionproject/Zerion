package org.zerionproject.core.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.Versions;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.junit.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getTransportId;

public class EagerSimplexOutgoingSessionTest extends BrambleMockTestCase {

	private static final int MAX_LATENCY = Integer.MAX_VALUE;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final StreamWriter streamWriter = context.mock(StreamWriter.class);
	private final SyncRecordWriter recordWriter =
			context.mock(SyncRecordWriter.class);

	private final ContactId contactId = getContactId();
	private final TransportId transportId = getTransportId();
	private final Ack ack =
			new Ack(singletonList(new MessageId(getRandomId())));
	private final Message message = getMessage(new GroupId(getRandomId()),
			MAX_MESSAGE_BODY_LENGTH);
	private final Message message1 = getMessage(new GroupId(getRandomId()),
			MAX_MESSAGE_BODY_LENGTH);

	@Test
	public void testNothingToSendEagerly() throws Exception {
		EagerSimplexOutgoingSession session =
				new EagerSimplexOutgoingSession(db, eventBus, contactId,
						transportId, MAX_LATENCY, streamWriter, recordWriter);

		Transaction noAckTxn = new Transaction(null, false);
		Transaction noIdsTxn = new Transaction(null, true);

		context.checking(new DbExpectations() {{

			oneOf(eventBus).addListener(session);

			oneOf(recordWriter).writeVersions(with(any(Versions.class)));

			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));

			oneOf(db).transactionWithResult(with(true),
					withDbCallable(noIdsTxn));
			oneOf(db).getUnackedMessagesToSend(noIdsTxn, contactId);
			will(returnValue(emptyList()));

			oneOf(streamWriter).sendEndOfStream();

			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testSomethingToSendEagerly() throws Exception {
		EagerSimplexOutgoingSession session =
				new EagerSimplexOutgoingSession(db, eventBus, contactId,
						transportId, MAX_LATENCY, streamWriter, recordWriter);

		Transaction ackTxn = new Transaction(null, false);
		Transaction noAckTxn = new Transaction(null, false);
		Transaction idsTxn = new Transaction(null, true);
		Transaction msgTxn = new Transaction(null, false);
		Transaction msgTxn1 = new Transaction(null, false);

		context.checking(new DbExpectations() {{

			oneOf(eventBus).addListener(session);

			oneOf(recordWriter).writeVersions(with(any(Versions.class)));

			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(ackTxn));
			oneOf(db).generateAck(ackTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(ack));
			oneOf(recordWriter).writeAck(ack);

			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));

			oneOf(db).transactionWithResult(with(true), withDbCallable(idsTxn));
			oneOf(db).getUnackedMessagesToSend(idsTxn, contactId);
			will(returnValue(asList(message.getId(), message1.getId())));

			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).getMessageToSend(msgTxn, contactId, message.getId(),
					MAX_LATENCY, true);
			will(returnValue(null));

			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn1));
			oneOf(db).getMessageToSend(msgTxn1, contactId, message1.getId(),
					MAX_LATENCY, true);
			will(returnValue(message1));
			oneOf(recordWriter).writeMessage(message1);

			oneOf(streamWriter).sendEndOfStream();

			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}
}
