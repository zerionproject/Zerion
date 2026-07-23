package org.zerionproject.app.autodelete;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.test.BriarIntegrationTest;
import org.zerionproject.app.test.BriarIntegrationTestComponent;
import org.zerionproject.app.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Collections.sort;
import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public abstract class AbstractAutoDeleteTest extends
		BriarIntegrationTest<BriarIntegrationTestComponent> {

	protected final long startTime = System.currentTimeMillis();

	protected abstract ConversationClient getConversationClient(
			BriarIntegrationTestComponent component);

	@Override
	protected void createComponents() {
		BriarIntegrationTestComponent component =
				DaggerBriarIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		c0 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t0Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		c1 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t1Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		c2 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t2Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);

		try {
			c0.getTimeTravel().setCurrentTimeMillis(startTime);
			c1.getTimeTravel().setCurrentTimeMillis(startTime + 1);
			c2.getTimeTravel().setCurrentTimeMillis(startTime + 2);
		} catch (InterruptedException e) {
			fail();
		}
	}

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();

		c0.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c1.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c2.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
	}

	protected List<ConversationMessageHeader> getMessageHeaders(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationClient conversationClient =
				getConversationClient(component);
		return sortHeaders(db.transactionWithResult(true, txn ->
				conversationClient.getMessageHeaders(txn, contactId)));
	}

	@SuppressWarnings({"UseCompareMethod", "Java8ListSort"})
	protected List<ConversationMessageHeader> sortHeaders(
			Collection<ConversationMessageHeader> in) {
		List<ConversationMessageHeader> out = new ArrayList<>(in);
		sort(out, (a, b) ->
				Long.valueOf(a.getTimestamp()).compareTo(b.getTimestamp()));
		return out;
	}

	@FunctionalInterface
	protected interface HeaderConsumer {
		void accept(ConversationMessageHeader header) throws DbException;
	}

	protected void forEachHeader(BriarIntegrationTestComponent component,
			ContactId contactId, int size, HeaderConsumer consumer)
			throws Exception {
		List<ConversationMessageHeader> headers =
				getMessageHeaders(component, contactId);
		assertEquals(size, headers.size());
		for (ConversationMessageHeader h : headers) consumer.accept(h);
	}

	protected long getAutoDeleteTimer(BriarIntegrationTestComponent component,
			ContactId contactId) throws DbException {
		DatabaseComponent db = component.getDatabaseComponent();
		AutoDeleteManager autoDeleteManager = component.getAutoDeleteManager();
		return db.transactionWithResult(false,
				txn -> autoDeleteManager.getAutoDeleteTimer(txn, contactId));
	}

	protected void markMessageRead(BriarIntegrationTestComponent component,
			Contact contact, MessageId messageId) throws Exception {
		ConversationManager conversationManager =
				component.getConversationManager();
		ConversationClient conversationClient =
				getConversationClient(component);
		GroupId groupId = conversationClient.getContactGroup(contact).getId();
		conversationManager.setReadFlag(groupId, messageId, true);
		waitForEvents(component);
	}

	protected void assertGroupCount(BriarIntegrationTestComponent component,
			ContactId contactId, int messageCount, int unreadCount)
			throws DbException {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationClient conversationClient =
				getConversationClient(component);

		GroupCount gc = db.transactionWithResult(true, txn ->
				conversationClient.getGroupCount(txn, contactId));
		assertEquals("messageCount", messageCount, gc.getMsgCount());
		assertEquals("unreadCount", unreadCount, gc.getUnreadCount());
	}
}
