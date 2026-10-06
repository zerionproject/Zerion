package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.fail;
import static org.zerionproject.app.api.introduction.IntroductionManager.CLIENT_ID;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.api.introduction.Role.INTRODUCEE;
import static org.zerionproject.app.api.introduction.Role.INTRODUCER;
import static org.zerionproject.app.introduction.MessageType.ABORT;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class IntroductionSessionSenderTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MessageParser messageParser =
			context.mock(MessageParser.class);
	private final SessionParser sessionParser =
			context.mock(SessionParser.class);

	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Transaction txn = new Transaction(null, false);
	private final GroupId strangersGroup = new GroupId(getRandomId());
	private final SessionId sessionId = new SessionId(getRandomId());
	private final BdfDictionary meta = new BdfDictionary();
	private final BdfDictionary query = new BdfDictionary();
	private final BdfDictionary stored = new BdfDictionary();
	private final Message abort = new Message(new MessageId(getRandomId()),
			strangersGroup, 1L, new byte[1]);

	@Test
	public void anAbortFromAContactThatIsNotTheIntroducerIsRefused()
			throws Exception {
		IntroduceeProtocolEngine introduceeEngine =
				new IntroduceeProtocolEngine(db, clientHelper,
						context.mock(ContactManager.class, "cm1"),
						contactGroupFactory,
						context.mock(MessageTracker.class, "mt1"),
						context.mock(IdentityManager.class, "im1"),
						context.mock(AuthorManager.class, "am1"),
						messageParser, context.mock(MessageEncoder.class),
						context.mock(IntroductionCrypto.class),
						context.mock(KeyManager.class),
						context.mock(TransportPropertyManager.class),
						context.mock(ClientVersioningManager.class, "cv1"),
						context.mock(AutoDeleteManager.class),
						context.mock(ConversationManager.class),
						context.mock(Clock.class));
		IntroductionManagerImpl manager = manager(null, introduceeEngine);

		Author introducer = getAuthor();
		Author stranger = getAuthor();
		IntroduceeSession session = IntroduceeSession.getInitial(
				new GroupId(getRandomId()), sessionId, introducer, true,
				getAuthor());
		ContactId strangerId = new ContactId(9);
		Contact strangerContact = new Contact(strangerId, stranger,
				new AuthorId(getRandomId()), null, null, true);
		expectStoredSession(INTRODUCEE);
		context.checking(new Expectations() {{
			oneOf(sessionParser).parseIntroduceeSession(strangersGroup,
					stored);
			will(returnValue(session));
			allowing(clientHelper).getContactId(txn, strangersGroup);
			will(returnValue(strangerId));
			allowing(db).getContact(txn, strangerId);
			will(returnValue(strangerContact));
		}});
		try {
			manager.incomingMessage(txn, abort, new BdfList(), meta);
			fail("a stranger's abort reached the session");
		} catch (FormatException expected) {
		}
	}

	@Test
	public void anAbortFromAContactOutsideAnIntroducersSessionIsRefused()
			throws Exception {
		IntroducerProtocolEngine introducerEngine =
				new IntroducerProtocolEngine(db, clientHelper,
						context.mock(ContactManager.class, "cm2"),
						contactGroupFactory,
						context.mock(MessageTracker.class, "mt2"),
						context.mock(IdentityManager.class, "im2"),
						context.mock(AuthorManager.class, "am2"),
						messageParser, context.mock(MessageEncoder.class),
						context.mock(ClientVersioningManager.class, "cv2"),
						context.mock(AutoDeleteManager.class),
						context.mock(ConversationManager.class),
						context.mock(Clock.class));
		IntroductionManagerImpl manager = manager(introducerEngine, null);

		IntroducerSession session = new IntroducerSession(sessionId,
				new GroupId(getRandomId()), getAuthor(),
				new GroupId(getRandomId()), getAuthor());
		expectStoredSession(INTRODUCER);
		context.checking(new Expectations() {{
			oneOf(sessionParser).parseIntroducerSession(stored);
			will(returnValue(session));
			allowing(messageParser).parseAbortMessage(abort, new BdfList());
			will(returnValue(new AbortMessage(abort.getId(), strangersGroup,
					1L, null, sessionId)));
		}});
		try {
			manager.incomingMessage(txn, abort, new BdfList(), meta);
			fail("a stranger's abort reached the session");
		} catch (FormatException expected) {
		}
	}

	private IntroductionManagerImpl manager(
			IntroducerProtocolEngine introducerEngine,
			IntroduceeProtocolEngine introduceeEngine) {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
		}});
		return new IntroductionManagerImpl(db, clientHelper,
				context.mock(ClientVersioningManager.class),
				context.mock(MetadataParser.class),
				context.mock(MessageTracker.class),
				contactGroupFactory, context.mock(ContactManager.class),
				messageParser, context.mock(SessionEncoder.class),
				sessionParser, introducerEngine, introduceeEngine, null,
				context.mock(IdentityManager.class),
				context.mock(AuthorManager.class));
	}

	private void expectStoredSession(
			org.zerionproject.app.api.introduction.Role role)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(messageParser).parseMetadata(meta);
			will(returnValue(new MessageMetadata(ABORT, sessionId, 1L, false,
					false, false, false, -1L, false)));
			oneOf(sessionParser).getSessionQuery(sessionId);
			will(returnValue(query));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId(), query);
			will(returnValue(Collections.singletonMap(
					new MessageId(getRandomId()), stored)));
			oneOf(sessionParser).getRole(stored);
			will(returnValue(role));
		}});
	}
}
