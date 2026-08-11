package org.zerionproject.app.client;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.client.MessageTracker;
import org.jmock.Expectations;
import org.junit.Test;

import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_LATEST_MSG;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_MSG_COUNT;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_STORED_MESSAGE_ID;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_UNREAD_COUNT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class MessageTrackerTest extends BrambleMockTestCase {

	protected final GroupId groupId = new GroupId(TestUtils.getRandomId());
	protected final ClientHelper clientHelper =
			context.mock(ClientHelper.class);
	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final Clock clock = context.mock(Clock.class);
	private final MessageId messageId = new MessageId(TestUtils.getRandomId());
	private final MessageTracker messageTracker =
			new MessageTrackerImpl(db, clientHelper, clock);
	private final BdfDictionary dictionary = BdfDictionary.of(
			new BdfEntry(GROUP_KEY_STORED_MESSAGE_ID, messageId)
	);

	@Test
	public void testInitializeGroupCount() throws Exception {
		Transaction txn = new Transaction(null, false);
		long now = 42L;
		BdfDictionary dictionary = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_MSG_COUNT, 0),
				new BdfEntry(GROUP_KEY_UNREAD_COUNT, 0),
				new BdfEntry(GROUP_KEY_LATEST_MSG, now)
		);
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).mergeGroupMetadata(txn, groupId, dictionary);
		}});
		messageTracker.initializeGroupCount(txn, groupId);
	}

	@Test
	public void testMessageStore() throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper).mergeGroupMetadata(groupId, dictionary);
		}});
		messageTracker.storeMessageId(groupId, messageId);
	}

	@Test
	public void testMessageLoad() throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper).getGroupMetadataAsDictionary(groupId);
			will(returnValue(dictionary));
		}});
		MessageId loadedId = messageTracker.loadStoredMessageId(groupId);
		assertNotNull(loadedId);
		assertEquals(messageId, loadedId);
	}

}
