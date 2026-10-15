package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.EditResult;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_DELETE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_EDIT;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ORIGINAL_KEPT;
import static org.zerionproject.app.messaging.MessagingManagerImpl.EDITS_NAMESPACE;
import static org.zerionproject.app.messaging.MessagingManagerImpl.KEY_ORIGINALS_CHECKED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

@NotNullByDefault
public class EditedTextErasureTest extends BrambleTestCase {

	private static final long HOUR = 60L * 60 * 1000;
	private static final String ORIGINAL = "Meet me at the old harbour at nine";
	private static final String FIRST_EDIT = "Meet me at the north gate at ten";
	private static final String SECOND_EDIT = "Meet me at the station at eleven";

	private final File testDir = getTestDirectory();
	private final long now = System.currentTimeMillis();

	private Device me;
	private Device contact;

	@NotNullByDefault
	private static class Device {

		private final File dir;
		private final SecretKey key = getSecretKey();
		@Nullable
		private ContactGroupScanTestComponent component;
		private DatabaseComponent db;
		private ClientHelper clientHelper;
		private MessagingManager messagingManager;
		private PrivateMessageValidator validator;
		private ContactId contactId;
		private GroupId group;

		private Device(File dir) {
			this.dir = dir;
		}
	}

	@Before
	public void setUp() throws Exception {
		me = new Device(new File(testDir, "me"));
		contact = new Device(new File(testDir, "contact"));
		assertTrue(me.dir.mkdirs());
		assertTrue(contact.dir.mkdirs());
		Identity mine = build(me).getIdentityManager().createIdentity("Me");
		Identity theirs = build(contact).getIdentityManager()
				.createIdentity("Contact");
		start(me, mine);
		start(contact, theirs);
		addContact(me, theirs.getLocalAuthor(), mine, true);
		addContact(contact, mine.getLocalAuthor(), theirs, false);
		assertEquals(me.group, contact.group);
	}

	@After
	public void tearDown() throws Exception {
		stop(me);
		stop(contact);
		deleteTestDirectory(testDir);
	}

	@Test
	public void aContactsEditLeavesNoTraceOfTheOriginal() throws Exception {
		MessageId target = deliverAt(me, text(ORIGINAL), now - HOUR);
		MessageId reply = deliverAt(me, reply(target), now - HOUR + 500);
		assertTrue(stored(me, ORIGINAL));

		assertEquals(MessageState.DELIVERED,
				deliver(me, edit(target, FIRST_EDIT), now - HOUR + 1000));

		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(target));
		assertEquals(FIRST_EDIT, texts(me).get(target));
		assertFalse(texts(me).containsValue(ORIGINAL));
		MessageId quoted = header(me, reply).getReplyToId();
		assertEquals(target, quoted);
		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(quoted));
		assertTrue(header(me, target).isEdited());
		assertFalse("the original is still stored", stored(me, ORIGINAL));
		assertBodyErased(me, target);
	}

	@Test
	public void aSecondEditFromTheContactTakesTheFirstWithIt()
			throws Exception {
		MessageId target = deliverAt(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(target, FIRST_EDIT), now - HOUR + 1000);

		assertEquals(MessageState.DELIVERED,
				deliver(me, edit(target, SECOND_EDIT), now - HOUR + 2000));

		assertEquals(SECOND_EDIT, me.messagingManager.getMessageText(target));
		assertFalse(texts(me).containsValue(FIRST_EDIT));
		assertFalse("the first edit is still stored", stored(me, FIRST_EDIT));
		assertFalse(stored(me, ORIGINAL));
	}

	@Test
	public void anOlderEditArrivingLateIsNotKept() throws Exception {
		MessageId target = deliverAt(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(target, SECOND_EDIT), now - HOUR + 2000);

		assertEquals(MessageState.INVALID,
				deliver(me, edit(target, FIRST_EDIT), now - HOUR + 1000));

		assertEquals(SECOND_EDIT, me.messagingManager.getMessageText(target));
		assertFalse("an outdated edit is stored", stored(me, FIRST_EDIT));
		assertFalse(stored(me, ORIGINAL));
	}

	@Test
	public void editsWaitingForTheirMessageKeepOnlyTheNewestText()
			throws Exception {
		Message original = create(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(original.getId(), FIRST_EDIT), now - HOUR + 1000);
		assertTrue(stored(me, FIRST_EDIT));

		assertEquals(MessageState.DELIVERED, deliver(me,
				edit(original.getId(), SECOND_EDIT), now - HOUR + 2000));
		assertFalse("a replaced waiting edit is stored",
				stored(me, FIRST_EDIT));
		assertEquals(MessageState.INVALID, deliver(me,
				edit(original.getId(), FIRST_EDIT), now - HOUR + 1500));
		assertFalse(stored(me, FIRST_EDIT));

		assertEquals(MessageState.DELIVERED, deliver(me, original));

		assertEquals(SECOND_EDIT,
				me.messagingManager.getMessageText(original.getId()));
		assertTrue(header(me, original.getId()).isEdited());
		awaitGone(me, ORIGINAL);
		assertFalse(stored(me, FIRST_EDIT));
		assertBodyErased(me, original.getId());
		awaitNotKept(me, original.getId());
	}

	@Test
	public void myEditOfAMessageTheContactHasErasesTheOriginalAtOnce()
			throws Exception {
		MessageId mine = own(me, ORIGINAL, now - HOUR);
		acknowledge(me, mine);

		assertEquals(EditResult.EDITED,
				editor(me).editMessage(me.contactId, mine, FIRST_EDIT));

		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(mine));
		assertFalse("the original is still stored", stored(me, ORIGINAL));
		assertBodyErased(me, mine);

		assertEquals(EditResult.EDITED,
				editor(me).editMessage(me.contactId, mine, SECOND_EDIT));

		assertEquals(SECOND_EDIT, me.messagingManager.getMessageText(mine));
		assertFalse(texts(me).containsValue(FIRST_EDIT));
		assertFalse("the first edit is still stored", stored(me, FIRST_EDIT));
		assertEquals(1, localControls(me, MESSAGE_EDIT).size());
	}

	@Test
	public void anEditBeforeTheContactHasTheMessageStillDeliversBoth()
			throws Exception {
		MessageId mine = own(me, ORIGINAL, now - HOUR);
		assertEquals(EditResult.EDITED,
				editor(me).editMessage(me.contactId, mine, FIRST_EDIT));
		assertTrue("the original was erased before the contact had it",
				stored(me, ORIGINAL));
		assertTrue(keepsOriginal(me, mine));
		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(mine));

		List<Message> sent = outbox(me);
		assertEquals(2, sent.size());
		assertEquals(mine, sent.get(0).getId());
		for (Message m : sent) {
			assertEquals(MessageState.DELIVERED, deliver(contact, m));
		}

		assertEquals(FIRST_EDIT, contact.messagingManager.getMessageText(mine));
		assertTrue(header(contact, mine).isEdited());
		assertFalse(stored(contact, ORIGINAL));
		assertTrue(stored(me, ORIGINAL));

		returnAcks(contact, me);

		awaitGone(me, ORIGINAL);
		assertBodyErased(me, mine);
		awaitNotKept(me, mine);
		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(mine));
		assertTrue(header(me, mine).isEdited());
		assertTrue(outbox(me).isEmpty());
	}

	@Test
	public void anEditThatReachesTheContactFirstStillShowsTheNewText()
			throws Exception {
		MessageId mine = own(me, ORIGINAL, now - HOUR);
		editor(me).editMessage(me.contactId, mine, FIRST_EDIT);
		List<Message> sent = outbox(me);
		assertEquals(2, sent.size());

		assertEquals(MessageState.DELIVERED, deliver(contact, sent.get(1)));
		assertEquals(MessageState.DELIVERED, deliver(contact, sent.get(0)));

		assertEquals(FIRST_EDIT, contact.messagingManager.getMessageText(mine));
		awaitGone(contact, ORIGINAL);
		returnAcks(contact, me);
		awaitGone(me, ORIGINAL);
	}

	@Test
	public void onlyTheNewTextComesBackAfterARestart() throws Exception {
		MessageId theirs = deliverAt(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(theirs, FIRST_EDIT), now - HOUR + 1000);
		MessageId mine = own(me, "my " + ORIGINAL, now - HOUR + 2000);
		editor(me).editMessage(me.contactId, mine, "my " + FIRST_EDIT);
		for (Message m : outbox(me)) deliver(contact, m);
		returnAcks(contact, me);
		awaitGone(me, "my " + ORIGINAL);

		restart(me);

		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(theirs));
		assertEquals("my " + FIRST_EDIT,
				me.messagingManager.getMessageText(mine));
		Map<MessageId, String> texts = texts(me);
		assertEquals(FIRST_EDIT, texts.get(theirs));
		assertEquals("my " + FIRST_EDIT, texts.get(mine));
		assertFalse(stored(me, ORIGINAL));
		assertTrue(header(me, theirs).isEdited());
		assertTrue(header(me, mine).isEdited());
	}

	@Test
	public void startingUpErasesOriginalsThatOutlivedTheirEdit()
			throws Exception {
		MessageId theirs = deliverAt(me, text("their " + ORIGINAL),
				now - HOUR);
		markKept(me, theirs, "their " + FIRST_EDIT, now - HOUR + 1000);
		MessageId received = own(me, "received " + ORIGINAL, now - HOUR + 1);
		me.db.transaction(false, txn -> me.db.receiveOffer(txn, me.contactId,
				new Offer(singletonList(received))));
		markKept(me, received, "received " + FIRST_EDIT,
				now - HOUR + 1001);
		MessageId waiting = own(me, "waiting " + ORIGINAL, now - HOUR + 2);
		markKept(me, waiting, "waiting " + FIRST_EDIT, now - HOUR + 1002);
		assertTrue(stored(me, "their " + ORIGINAL));
		assertTrue(stored(me, "received " + ORIGINAL));

		restart(me);

		assertFalse(stored(me, "their " + ORIGINAL));
		assertFalse(stored(me, "received " + ORIGINAL));
		assertEquals("their " + FIRST_EDIT,
				me.messagingManager.getMessageText(theirs));
		assertEquals("received " + FIRST_EDIT,
				me.messagingManager.getMessageText(received));
		assertTrue("a message the contact may not have was erased",
				stored(me, "waiting " + ORIGINAL));
		assertTrue(outboxIds(me).contains(waiting));
		assertFalse(keepsOriginal(me, theirs));
		assertFalse(keepsOriginal(me, received));
		assertTrue(keepsOriginal(me, waiting));
	}

	@Test
	public void aDeliveryDeferredAfterItsWaitingEditKeepsTheBodyUntilDelivered()
			throws Exception {
		Message original = create(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(original.getId(), FIRST_EDIT), now - HOUR + 1000);
		deferDeliveryOnce(me, original.getId());

		receive(me, original);
		awaitState(me, original.getId(), MessageState.PENDING);

		assertTrue("a message waiting for its delivery lost its body",
				stored(me, ORIGINAL));
		assertTrue(keepsOriginal(me, original.getId()));

		restart(me);

		awaitState(me, original.getId(), MessageState.DELIVERED);
		assertEquals(FIRST_EDIT,
				me.messagingManager.getMessageText(original.getId()));
		assertTrue(header(me, original.getId()).isEdited());
		awaitGone(me, ORIGINAL);
		assertBodyErased(me, original.getId());
		awaitNotKept(me, original.getId());
	}

	@Test
	public void anEditForAMessageWaitingForDeliveryWaitsAndErasesItLater()
			throws Exception {
		Message original = create(me, text(ORIGINAL), now - HOUR);
		deferDeliveryOnce(me, original.getId());
		receive(me, original);
		awaitState(me, original.getId(), MessageState.PENDING);

		assertEquals(MessageState.DELIVERED, deliver(me,
				edit(original.getId(), FIRST_EDIT), now - HOUR + 1000));

		assertTrue("a message waiting for its delivery lost its body",
				stored(me, ORIGINAL));
		assertEquals(MessageState.PENDING, me.db.transactionWithResult(true,
				txn -> me.db.getMessageState(txn, original.getId())));

		restart(me);

		awaitState(me, original.getId(), MessageState.DELIVERED);
		assertEquals(FIRST_EDIT,
				me.messagingManager.getMessageText(original.getId()));
		awaitGone(me, ORIGINAL);
		assertBodyErased(me, original.getId());
	}

	@Test
	public void anOpenLooksUpOnlyMessagesThatKeepTheirOriginal()
			throws Exception {
		assertTrue(originalsChecked(me));
		MessageId kept = deliverAt(me, text("kept " + ORIGINAL), now - HOUR);
		markKept(me, kept, "kept " + FIRST_EDIT, now - HOUR + 1000);
		AtomicInteger visits = new AtomicInteger();

		open(me, visits);

		assertEquals("an open read the metadata of every message", 0,
				visits.get());
		assertFalse(stored(me, "kept " + ORIGINAL));
		assertFalse(keepsOriginal(me, kept));
		assertEquals("kept " + FIRST_EDIT,
				me.messagingManager.getMessageText(kept));
	}

	@Test
	public void originalsEditedBeforeTheMarkExistedAreFoundOnce()
			throws Exception {
		MessageId theirs = deliverAt(me, text("their " + ORIGINAL),
				now - HOUR);
		markEdited(me, theirs, "their " + FIRST_EDIT, now - HOUR + 1000);
		MessageId mine = own(me, "my " + ORIGINAL, now - HOUR + 1);
		markEdited(me, mine, "my " + FIRST_EDIT, now - HOUR + 1001);
		forgetOriginalsChecked(me);
		AtomicInteger visits = new AtomicInteger();

		open(me, visits);

		assertTrue(visits.get() > 0);
		assertTrue(originalsChecked(me));
		assertFalse(stored(me, "their " + ORIGINAL));
		assertBodyErased(me, theirs);
		assertTrue("a message the contact may not have was erased",
				stored(me, "my " + ORIGINAL));
		assertTrue(keepsOriginal(me, mine));

		visits.set(0);
		open(me, visits);
		assertEquals("a later open read the metadata of every message", 0,
				visits.get());

		for (Message m : outbox(me)) deliver(contact, m);
		returnAcks(contact, me);
		awaitGone(me, "my " + ORIGINAL);
		awaitNotKept(me, mine);
	}

	@Test
	public void unreadableMetadataSkipsOnlyItsOwnMessageInTheOneTimeCheck()
			throws Exception {
		MessageId theirs = deliverAt(me, text("their " + ORIGINAL),
				now - HOUR);
		markEdited(me, theirs, "their " + FIRST_EDIT, now - HOUR + 1000);
		unreadable(me, now - HOUR + 1, false);
		forgetOriginalsChecked(me);

		open(me, new AtomicInteger());

		assertTrue(originalsChecked(me));
		assertFalse("one unreadable message hid the rest of its chat",
				stored(me, "their " + ORIGINAL));
		assertBodyErased(me, theirs);
	}

	@Test
	public void unreadableMetadataSkipsOnlyItsOwnMessageWhenKeptOnesAreChecked()
			throws Exception {
		assertTrue(originalsChecked(me));
		MessageId kept = deliverAt(me, text("kept " + ORIGINAL), now - HOUR);
		markKept(me, kept, "kept " + FIRST_EDIT, now - HOUR + 1000);
		unreadable(me, now - HOUR + 1, true);

		open(me, new AtomicInteger());

		assertFalse("one unreadable message hid the rest of its chat",
				stored(me, "kept " + ORIGINAL));
		assertBodyErased(me, kept);
		assertFalse(keepsOriginal(me, kept));
	}

	@Test
	public void aMessageThatLostItsBodyBeforeDeliveryIsResolvedAtTheNextStart()
			throws Exception {
		Message lost = create(me, text(ORIGINAL), now - HOUR);
		deferDeliveryOnce(me, lost.getId());
		receive(me, lost);
		awaitState(me, lost.getId(), MessageState.PENDING);
		me.db.transaction(false, txn ->
				me.db.eraseMessageBody(txn, lost.getId()));
		Message dependent = create(me, withAttachment("with it",
				lost.getId()), now - HOUR + 1);
		receive(me, dependent);
		awaitState(me, dependent.getId(), MessageState.PENDING);
		Message waiting = create(me, withAttachment("still coming",
				new MessageId(getRandomId())), now - HOUR + 2);
		receive(me, waiting);
		awaitState(me, waiting.getId(), MessageState.PENDING);
		MessageId edited = deliverAt(me, text("edited " + ORIGINAL),
				now - HOUR + 3);
		deliverAt(me, edit(edited, FIRST_EDIT), now - HOUR + 1000);
		assertBodyErased(me, edited);

		restart(me);

		awaitState(me, lost.getId(), MessageState.INVALID);
		awaitState(me, dependent.getId(), MessageState.INVALID);
		assertFalse(stored(me, ORIGINAL));
		assertEquals(MessageState.PENDING, state(me, waiting.getId()));
		assertNotNull(me.db.transactionWithResult(true, txn ->
				me.db.getMessage(txn, waiting.getId())));
		assertEquals(MessageState.DELIVERED, state(me, edited));
		assertEquals(FIRST_EDIT, me.messagingManager.getMessageText(edited));
	}

	@Test
	public void anEraseThatFailsIsTriedAgain() throws Exception {
		MessageId kept = deliverAt(me, text("kept " + ORIGINAL), now - HOUR);
		markKept(me, kept, "kept " + FIRST_EDIT, now - HOUR + 1000);
		Queue<Runnable> tasks = new ArrayDeque<>();
		EditedOriginalEraser eraser = new EditedOriginalEraser(
				failingWrites(me, new AtomicInteger(1)), tasks::add,
				editor(me));

		eraser.eventOccurred(new MessagesAckedEvent(me.contactId,
				singletonList(kept)));

		assertEquals(2, drain(tasks));
		assertFalse("a failed erase was not tried again",
				stored(me, "kept " + ORIGINAL));
		assertBodyErased(me, kept);
		assertFalse(keepsOriginal(me, kept));
	}

	@Test
	public void anEraseThatKeepsFailingIsLeftToTheNextOpen() throws Exception {
		MessageId kept = deliverAt(me, text("kept " + ORIGINAL), now - HOUR);
		markKept(me, kept, "kept " + FIRST_EDIT, now - HOUR + 1000);
		Queue<Runnable> tasks = new ArrayDeque<>();
		EditedOriginalEraser eraser = new EditedOriginalEraser(
				failingWrites(me, new AtomicInteger(Integer.MAX_VALUE)),
				tasks::add, editor(me));

		eraser.eventOccurred(new MessagesAckedEvent(me.contactId,
				singletonList(kept)));

		assertEquals(EditedOriginalEraser.MAX_ATTEMPTS, drain(tasks));
		assertTrue(stored(me, "kept " + ORIGINAL));
		assertTrue(keepsOriginal(me, kept));

		open(me, new AtomicInteger());

		assertFalse(stored(me, "kept " + ORIGINAL));
		assertBodyErased(me, kept);
	}

	@Test
	public void anErasedMessageCanStillBeEditedReactedToAndDeleted()
			throws Exception {
		MessageId theirs = deliverAt(me, text(ORIGINAL), now - HOUR);
		deliverAt(me, edit(theirs, FIRST_EDIT), now - HOUR + 1000);
		assertBodyErased(me, theirs);

		assertEquals(MessageState.DELIVERED, deliver(me,
				BdfList.of(MESSAGE_REACTION, theirs.getBytes(), "heart"),
				now - HOUR + 1500));
		assertTrue(me.messagingManager.getReactions(me.contactId)
				.containsKey(theirs));
		deliverAt(me, edit(theirs, SECOND_EDIT), now - HOUR + 2000);
		assertEquals(SECOND_EDIT, me.messagingManager.getMessageText(theirs));

		deliverAt(me, BdfList.of(MESSAGE_DELETE, theirs.getBytes()),
				now - HOUR + 3000);

		assertFalse(me.messagingManager.getMessageTexts(me.contactId)
				.containsKey(theirs));
		assertFalse(stored(me, SECOND_EDIT));
	}

	@Test
	public void myErasedMessageCanStillBeEditedAndDeletedForEveryone()
			throws Exception {
		MessageId mine = own(me, ORIGINAL, now - HOUR);
		for (Message m : outbox(me)) deliver(contact, m);
		returnAcks(contact, me);
		editor(me).editMessage(me.contactId, mine, FIRST_EDIT);
		assertBodyErased(me, mine);

		assertEquals(EditResult.EDITED,
				editor(me).editMessage(me.contactId, mine, SECOND_EDIT));
		assertTrue(editor(me).deleteForEveryone(me.contactId,
				singletonList(mine)));

		assertEquals(1, localControls(me, MESSAGE_DELETE).size());
		assertEquals(0, localControls(me, MESSAGE_EDIT).size());
		assertFalse(texts(me).containsKey(mine));
		assertFalse(stored(me, ORIGINAL));
		assertFalse(stored(me, FIRST_EDIT));
		assertFalse(stored(me, SECOND_EDIT));
	}

	private ContactGroupScanTestComponent build(Device d) {
		ContactGroupScanTestComponent c =
				DaggerContactGroupScanTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(d.dir))
						.build();
		ContactGroupScanTestComponent.Helper.injectEagerSingletons(c);
		d.component = c;
		return c;
	}

	private void start(Device d, @Nullable Identity identity)
			throws Exception {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		if (identity != null) {
			c.getIdentityManager().registerIdentity(identity);
		}
		LifecycleManager lifecycle = c.getLifecycleManager();
		lifecycle.startServices(d.key);
		lifecycle.waitForStartup();
		d.db = c.getDatabaseComponent();
		d.clientHelper = c.getClientHelper();
		d.messagingManager = c.getMessagingManager();
		d.validator = c.getPrivateMessageValidator();
	}

	private void stop(@Nullable Device d) throws Exception {
		if (d == null || d.component == null) return;
		LifecycleManager lifecycle = d.component.getLifecycleManager();
		lifecycle.stopServices();
		lifecycle.waitForShutdown();
		d.component = null;
	}

	private void restart(Device d) throws Exception {
		stop(d);
		build(d);
		start(d, null);
	}

	private void addContact(Device d, Author remote, Identity local,
			boolean alice) throws Exception {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		d.contactId = c.getContactManager().addContact(remote, local.getId(),
				getSecretKey(), now, alice, true, true);
		Group g = d.messagingManager.getContactGroup(
				c.getContactManager().getContact(d.contactId));
		d.group = g.getId();
		d.db.transaction(false, txn -> d.db.setGroupVisibility(txn,
				d.contactId, g.getId(), Group.Visibility.SHARED));
	}

	private MessagingManagerImpl editor(Device d) {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		ClientVersioningManager real = c.getClientVersioningManager();
		ClientVersioningManager seen = (ClientVersioningManager)
				Proxy.newProxyInstance(
						ClientVersioningManager.class.getClassLoader(),
						new Class<?>[] {ClientVersioningManager.class},
						(proxy, method, args) -> {
							if (method.getName().equals(
									"getClientMinorVersion")) {
								return 9;
							}
							return method.invoke(real, args);
						});
		return new MessagingManagerImpl(d.db, d.clientHelper, seen,
				c.getMetadataParser(), c.getConversationManager(),
				c.getMessageTracker(), c.getContactGroupFactory(),
				c.getAutoDeleteManager(), c.getStreamingAttachmentWriter(),
				c.getIdentityManager(), d.validator);
	}

	private static BdfList text(String text) {
		return BdfList.of(PRIVATE_MESSAGE, text, new BdfList());
	}

	private static BdfList edit(MessageId target, String text) {
		return BdfList.of(MESSAGE_EDIT, target.getBytes(), text);
	}

	private static BdfList withAttachment(String text, MessageId attachment) {
		return BdfList.of(PRIVATE_MESSAGE, text,
				BdfList.of(BdfList.of(attachment.getBytes(), "image/jpeg")));
	}

	private MessageId unreadable(Device d, long ts, boolean kept)
			throws Exception {
		Message m = create(d, BdfList.of(MESSAGE_REACTION, getRandomId(),
				"heart"), ts);
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry("timestamp", ts),
				new BdfEntry("local", true),
				new BdfEntry("messageType", MESSAGE_REACTION));
		if (kept) meta.put(MSG_KEY_ORIGINAL_KEPT, true);
		d.db.transaction(false, txn ->
				d.clientHelper.addLocalMessage(txn, m, meta, true, false));
		Metadata broken = new Metadata();
		broken.put("broken", new byte[] {(byte) 0xFF});
		d.db.transaction(false, txn ->
				d.db.mergeMessageMetadata(txn, m.getId(), broken));
		return m.getId();
	}

	private MessageState state(Device d, MessageId m) throws Exception {
		return d.db.transactionWithResult(true, txn ->
				d.db.getMessageState(txn, m));
	}

	private static DatabaseComponent failingWrites(Device d,
			AtomicInteger failures) {
		DatabaseComponent real = d.db;
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("transaction")
							&& args[0].equals(false)
							&& failures.getAndDecrement() > 0) {
						throw new DbException();
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private static int drain(Queue<Runnable> tasks) {
		int runs = 0;
		Runnable r;
		while ((r = tasks.poll()) != null) {
			r.run();
			if (++runs > 100) throw new AssertionError("never stops");
		}
		return runs;
	}

	private BdfList reply(MessageId target) {
		return BdfList.of(PRIVATE_MESSAGE, "which one?", new BdfList(), null,
				target.getBytes());
	}

	private Message create(Device d, BdfList body, long ts)
			throws FormatException {
		return d.clientHelper.createMessage(d.group, ts,
				d.clientHelper.toByteArray(body));
	}

	private MessageId own(Device d, String text, long ts) throws Exception {
		Message m = create(d, text(text), ts);
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry("timestamp", ts),
				new BdfEntry("local", true),
				new BdfEntry("read", true),
				new BdfEntry("messageType", PRIVATE_MESSAGE),
				new BdfEntry("hasText", true),
				new BdfEntry("attachmentHeaders", new BdfList()));
		d.db.transaction(false, txn ->
				d.clientHelper.addLocalMessage(txn, m, meta, true, false));
		return m.getId();
	}

	private void markEdited(Device d, MessageId m, String text, long at)
			throws Exception {
		d.db.transaction(false, txn -> d.clientHelper.mergeMessageMetadata(
				txn, m, BdfDictionary.of(new BdfEntry("editedText", text),
						new BdfEntry("editedAt", at))));
	}

	private void markKept(Device d, MessageId m, String text, long at)
			throws Exception {
		markEdited(d, m, text, at);
		d.db.transaction(false, txn -> d.clientHelper.mergeMessageMetadata(
				txn, m, BdfDictionary.of(
						new BdfEntry(MSG_KEY_ORIGINAL_KEPT, true))));
	}

	private boolean keepsOriginal(Device d, MessageId m) throws Exception {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		return d.db.transactionWithResult(true, txn -> c.getMetadataParser()
				.parse(d.db.getMessageMetadataForValidator(txn, m))
				.getBoolean(MSG_KEY_ORIGINAL_KEPT, false));
	}

	private void awaitNotKept(Device d, MessageId m) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (keepsOriginal(d, m)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError(
						"still marked as keeping its original");
			}
			Thread.sleep(20);
		}
	}

	private boolean originalsChecked(Device d) throws Exception {
		return d.db.transactionWithResult(true, txn ->
				d.db.getSettings(txn, EDITS_NAMESPACE))
				.getBoolean(KEY_ORIGINALS_CHECKED, false);
	}

	private void forgetOriginalsChecked(Device d) throws Exception {
		Settings s = new Settings();
		s.putBoolean(KEY_ORIGINALS_CHECKED, false);
		d.db.transaction(false, txn ->
				d.db.mergeSettings(txn, s, EDITS_NAMESPACE));
	}

	private void open(Device d, AtomicInteger wholeGroupReads)
			throws Exception {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		ClientHelper real = d.clientHelper;
		ClientHelper counting = (ClientHelper) Proxy.newProxyInstance(
				ClientHelper.class.getClassLoader(),
				new Class<?>[] {ClientHelper.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.startsWith("visitMessageMetadata")
							|| (name.equals("getMessageMetadataAsDictionary")
							&& args.length == 2
							&& args[1] instanceof GroupId)) {
						wholeGroupReads.incrementAndGet();
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		MessagingManagerImpl opener = new MessagingManagerImpl(d.db, counting,
				c.getClientVersioningManager(), c.getMetadataParser(),
				c.getConversationManager(), c.getMessageTracker(),
				c.getContactGroupFactory(), c.getAutoDeleteManager(),
				c.getStreamingAttachmentWriter(), c.getIdentityManager(),
				d.validator);
		d.db.transaction(false, opener::onDatabaseOpened);
	}

	private void deferDeliveryOnce(Device d, MessageId id) {
		ContactGroupScanTestComponent c = d.component;
		assertNotNull(c);
		IncomingMessageHook real = (IncomingMessageHook) d.messagingManager;
		AtomicBoolean deferred = new AtomicBoolean(false);
		c.getValidationManager().registerIncomingMessageHook(
				MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION,
				(txn, m, meta) -> {
					DeliveryAction action = real.incomingMessage(txn, m, meta);
					if (m.getId().equals(id) && !deferred.getAndSet(true)) {
						throw new DbException();
					}
					return action;
				});
	}

	private void receive(Device d, Message m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveMessage(txn, d.contactId,
				m));
	}

	private void awaitState(Device d, MessageId m, MessageState state)
			throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, m));
			} catch (NoSuchMessageException e) {
				s = null;
			}
			if (s == state) return;
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message is " + s + ", not "
						+ state);
			}
			Thread.sleep(20);
		}
	}

	private void acknowledge(Device d, MessageId m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveAck(txn, d.contactId,
				new Ack(singletonList(m))));
	}

	private List<Message> outbox(Device d) throws Exception {
		Collection<Message> batch = d.db.transactionWithNullableResult(false,
				txn -> d.db.generateBatch(txn, d.contactId, 10_000_000L,
						60_000L));
		List<Message> mine = new ArrayList<>();
		if (batch == null) return mine;
		for (Message m : batch) {
			if (m.getGroupId().equals(d.group)) mine.add(m);
		}
		return mine;
	}

	private Collection<MessageId> outboxIds(Device d) throws Exception {
		List<MessageId> ids = new ArrayList<>();
		for (Message m : outbox(d)) ids.add(m.getId());
		return ids;
	}

	private void returnAcks(Device from, Device to) throws Exception {
		Ack ack = from.db.transactionWithNullableResult(false, txn ->
				from.db.generateAck(txn, from.contactId, 1000));
		assertNotNull(ack);
		to.db.transaction(false, txn ->
				to.db.receiveAck(txn, to.contactId, ack));
	}

	private Collection<MessageId> localControls(Device d, int type)
			throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry("messageType", type),
				new BdfEntry("local", true));
		return d.db.transactionWithResult(true, txn ->
				d.clientHelper.getMessageIds(txn, d.group, query));
	}

	private Map<MessageId, String> texts(Device d) throws Exception {
		return d.messagingManager.getMessageTexts(d.contactId);
	}

	private PrivateMessageHeader header(Device d, MessageId id)
			throws Exception {
		Collection<ConversationMessageHeader> headers =
				d.db.transactionWithResult(true, txn ->
						d.messagingManager.getMessageHeaders(txn,
								d.contactId));
		for (ConversationMessageHeader h : headers) {
			if (h.getId().equals(id)) return (PrivateMessageHeader) h;
		}
		throw new AssertionError("no header for the message");
	}

	private void assertBodyErased(Device d, MessageId m) throws Exception {
		d.db.transaction(true, txn -> {
			try {
				d.db.getMessage(txn, m);
				fail("the stored body is still readable");
			} catch (MessageDeletedException expected) {
			}
		});
	}

	private boolean stored(Device d, String text) throws Exception {
		byte[] needle = text.getBytes(UTF_8);
		return d.db.transactionWithResult(true, txn -> {
			Connection c = (Connection) txn.unbox();
			for (String sql : new String[] {"SELECT raw FROM messages",
					"SELECT value FROM messageMetadata"}) {
				try (Statement s = c.createStatement();
						ResultSet rs = s.executeQuery(sql)) {
					while (rs.next()) {
						byte[] value = rs.getBytes(1);
						if (value != null && contains(value, needle)) {
							return true;
						}
					}
				}
			}
			return false;
		});
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private void awaitGone(Device d, String text) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (stored(d, text)) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("still stored: " + text);
			}
			Thread.sleep(20);
		}
	}

	private MessageId deliverAt(Device d, BdfList body, long ts)
			throws Exception {
		Message m = create(d, body, ts);
		deliver(d, m);
		return m.getId();
	}

	private MessageState deliver(Device d, BdfList body, long ts)
			throws Exception {
		return deliver(d, create(d, body, ts));
	}

	private MessageState deliver(Device d, Message m) throws Exception {
		d.db.transaction(false, txn -> d.db.receiveMessage(txn, d.contactId,
				m));
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			MessageState s;
			try {
				s = d.db.transactionWithResult(true, txn ->
						d.db.getMessageState(txn, m.getId()));
			} catch (NoSuchMessageException removedAfterDelivery) {
				return MessageState.DELIVERED;
			}
			if (s != MessageState.UNKNOWN && s != MessageState.PENDING) {
				return s;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("the message was never validated");
			}
			Thread.sleep(20);
		}
	}
}
