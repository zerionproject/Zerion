package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.app.api.introduction.IntroductionManager.CLIENT_ID;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSES;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class IntroductionRequestMarkingTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MessageParser messageParser =
			context.mock(MessageParser.class);
	private final SessionParser sessionParser =
			context.mock(SessionParser.class);
	private final SessionEncoder sessionEncoder =
			context.mock(SessionEncoder.class);
	private final IntroductionCrypto crypto =
			context.mock(IntroductionCrypto.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);

	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group groupA = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group groupB = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Transaction txn = new Transaction(null, false);
	private final SessionId sessionId = new SessionId(getRandomId());
	private final LocalAuthor introducer = getLocalAuthor();
	private final Contact a = contact(1);
	private final Contact b = contact(2);
	private final Message requestToA = new Message(
			new MessageId(getRandomId()), groupA.getId(), 5L, new byte[1]);
	private final Message requestToB = new Message(
			new MessageId(getRandomId()), groupB.getId(), 5L, new byte[1]);
	private final Message storage = new Message(new MessageId(getRandomId()),
			localGroup.getId(), 1L, new byte[1]);
	private final List<MessageId> marked = new ArrayList<>();

	@Test
	public void startingAnIntroductionMarksTheRequestToEachContact()
			throws Exception {
		IntroductionManagerImpl manager = manager(true);
		expectNewSession();

		manager.makeIntroduction(txn, a, b, "hello");

		assertEquals(Arrays.asList(requestToA.getId(), requestToB.getId()),
				marked);
	}

	@Test
	public void anIntroductionThatSendsNoRequestMarksNothing()
			throws Exception {
		IntroductionManagerImpl manager = manager(false);
		expectNewSession();

		manager.makeIntroduction(txn, a, b, "hello");

		assertEquals(Collections.emptyList(), marked);
	}

	private void expectNewSession() throws Exception {
		BdfDictionary query = new BdfDictionary();
		BdfDictionary encoded = new BdfDictionary();
		context.checking(new Expectations() {{
			oneOf(identityManager).getLocalAuthor(txn);
			will(returnValue(introducer));
			oneOf(crypto).getSessionId(introducer, a.getAuthor(),
					b.getAuthor());
			will(returnValue(sessionId));
			oneOf(sessionParser).getSessionQuery(sessionId);
			will(returnValue(query));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId(), query);
			will(returnValue(Collections.emptyMap()));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, a);
			will(returnValue(groupA));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, b);
			will(returnValue(groupB));
			oneOf(crypto).isAlice(a.getAuthor().getId(),
					b.getAuthor().getId());
			will(returnValue(true));
			oneOf(clientHelper).createMessageForStoringMetadata(
					localGroup.getId());
			will(returnValue(storage));
			oneOf(db).addLocalMessage(with(txn), with(storage),
					with(any(Metadata.class)), with(false), with(false));
			oneOf(sessionEncoder).encodeIntroducerSession(
					with(any(IntroducerSession.class)));
			will(returnValue(encoded));
			oneOf(clientHelper).mergeMessageMetadata(txn, storage.getId(),
					encoded);
		}});
	}

	private IntroductionManagerImpl manager(boolean sendsRequests) {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
		}});
		IntroducerProtocolEngine engine = new IntroducerProtocolEngine(db,
				clientHelper, context.mock(ContactManager.class, "cm"),
				contactGroupFactory, context.mock(MessageTracker.class, "mt"),
				identityManager, context.mock(AuthorManager.class, "am"),
				messageParser, context.mock(MessageEncoder.class),
				context.mock(ClientVersioningManager.class, "cv"),
				context.mock(AutoDeleteManager.class),
				context.mock(ConversationManager.class),
				context.mock(Clock.class)) {
			@Override
			public IntroducerSession onRequestAction(Transaction t,
					IntroducerSession s, @Nullable String text) {
				if (!sendsRequests) return s;
				return new IntroducerSession(s.getSessionId(),
						AWAIT_RESPONSES, 5L,
						new IntroducerSession.Introducee(s.getIntroduceeA(),
								requestToA),
						new IntroducerSession.Introducee(s.getIntroduceeB(),
								requestToB));
			}
		};
		return new IntroductionManagerImpl(db, clientHelper,
				context.mock(ClientVersioningManager.class),
				context.mock(MetadataParser.class),
				context.mock(MessageTracker.class),
				contactGroupFactory, context.mock(ContactManager.class),
				messageParser, sessionEncoder, sessionParser, engine, null,
				crypto, identityManager, context.mock(AuthorManager.class),
				marked::add);
	}

	private static Contact contact(int id) {
		Author author = getAuthor();
		return new Contact(new ContactId(id), author,
				new AuthorId(getRandomId()), null, null, true);
	}
}
