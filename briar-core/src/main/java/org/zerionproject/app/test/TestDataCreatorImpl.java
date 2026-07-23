package org.zerionproject.app.test;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupFactory;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.app.api.avatar.AvatarManager;
import org.zerionproject.app.api.avatar.AvatarMessageEncoder;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.test.TestAvatarCreator;
import org.zerionproject.app.api.test.TestDataCreator;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.test.TestData.AUTHOR_NAMES;

@NotNullByDefault
public class TestDataCreatorImpl implements TestDataCreator {
	private final AuthorFactory authorFactory;
	private final Clock clock;
	private final GroupFactory groupFactory;
	private final PrivateMessageFactory privateMessageFactory;

	private final DatabaseComponent db;
	private final IdentityManager identityManager;
	private final CryptoComponent crypto;
	private final ContactManager contactManager;
	private final TransportPropertyManager transportPropertyManager;
	private final MessagingManager messagingManager;
	private final TestAvatarCreator testAvatarCreator;
	private final AvatarMessageEncoder avatarMessageEncoder;

	@IoExecutor
	private final Executor ioExecutor;

	private final Random random = new Random();
	private final Map<Contact, LocalAuthor> localAuthors = new HashMap<>();

	@Inject
	TestDataCreatorImpl(AuthorFactory authorFactory, Clock clock,
			GroupFactory groupFactory,
			PrivateMessageFactory privateMessageFactory,
			DatabaseComponent db,
			IdentityManager identityManager,
			CryptoComponent crypto,
			ContactManager contactManager,
			TransportPropertyManager transportPropertyManager,
			MessagingManager messagingManager,
			TestAvatarCreator testAvatarCreator,
			AvatarMessageEncoder avatarMessageEncoder,
			@IoExecutor Executor ioExecutor) {
		this.authorFactory = authorFactory;
		this.clock = clock;
		this.groupFactory = groupFactory;
		this.privateMessageFactory = privateMessageFactory;
		this.db = db;
		this.identityManager = identityManager;
		this.crypto = crypto;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.messagingManager = messagingManager;
		this.testAvatarCreator = testAvatarCreator;
		this.avatarMessageEncoder = avatarMessageEncoder;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public void createTestData(int numContacts, int numPrivateMsgs,
			int avatarPercent) {
		if (numContacts == 0) throw new IllegalArgumentException();
		if (avatarPercent < 0 || avatarPercent > 100)
			throw new IllegalArgumentException();
		ioExecutor.execute(() -> {
			try {
				createTestDataOnIoExecutor(numContacts, numPrivateMsgs,
						avatarPercent);
			} catch (DbException e) {
			}
		});
	}

	@IoExecutor
	private void createTestDataOnIoExecutor(int numContacts, int numPrivateMsgs,
			int avatarPercent) throws DbException {
		List<Contact> contacts = createContacts(numContacts, avatarPercent);
		createPrivateMessages(contacts, numPrivateMsgs);
	}

	private List<Contact> createContacts(int numContacts, int avatarPercent)
			throws DbException {
		List<Contact> contacts = new ArrayList<>(numContacts);
		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		for (int i = 0; i < numContacts; i++) {
			LocalAuthor remote = getRandomAuthor();
			Contact contact = addContact(localAuthor.getId(), remote,
					random.nextBoolean(), avatarPercent);
			contacts.add(contact);
		}
		return contacts;
	}

	private Contact addContact(AuthorId localAuthorId, LocalAuthor remote,
			boolean alias, int avatarPercent) throws DbException {
		SecretKey secretKey = getSecretKey();
		long timestamp = clock.currentTimeMillis();
		boolean verified = random.nextBoolean();
		Map<TransportId, TransportProperties> props =
				getRandomTransportProperties();

		Contact contact = db.transactionWithResult(false, txn -> {
			ContactId contactId = contactManager.addContact(txn, remote,
					localAuthorId, secretKey, timestamp, true, verified, true);
			if (alias) {
				contactManager.setContactAlias(txn, contactId,
						getRandomAuthorName());
			}
			transportPropertyManager.addRemoteProperties(txn, contactId, props);
			return db.getContact(txn, contactId);
		});
		if (random.nextInt(100) + 1 <= avatarPercent) addAvatar(contact);
		localAuthors.put(contact, remote);
		return contact;
	}

	@Override
	public Contact addContact(String name, boolean alias, boolean avatar)
			throws DbException {
		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		LocalAuthor remote = authorFactory.createLocalAuthor(name);
		int avatarPercent = avatar ? 100 : 0;
		return addContact(localAuthor.getId(), remote, alias, avatarPercent);
	}

	private String getRandomAuthorName() {
		int i = random.nextInt(AUTHOR_NAMES.length);
		return AUTHOR_NAMES[i];
	}

	private LocalAuthor getRandomAuthor() {
		return authorFactory.createLocalAuthor(getRandomAuthorName());
	}

	private SecretKey getSecretKey() {
		byte[] b = new byte[SecretKey.LENGTH];
		random.nextBytes(b);
		return new SecretKey(b);
	}

	private Map<TransportId, TransportProperties> getRandomTransportProperties() {
		Map<TransportId, TransportProperties> props = new HashMap<>();
		TransportProperties lan = new TransportProperties();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			if (sb.length() > 0) sb.append(',');
			sb.append(getRandomLanAddress());
		}
		lan.put(LanTcpConstants.PROP_IP_PORTS, sb.toString());
		String port = String.valueOf(getRandomPortNumber());
		lan.put(LanTcpConstants.PROP_PORT, port);
		props.put(LanTcpConstants.ID, lan);
		TransportProperties tor = new TransportProperties();
		String torAddress = getRandomTorAddress();
		tor.put(TorConstants.PROP_ONION_V3, torAddress);
		props.put(TorConstants.ID, tor);

		return props;
	}

	private String getRandomLanAddress() {
		StringBuilder sb = new StringBuilder();
		if (random.nextInt(5) == 0) {
			sb.append("10.");
			sb.append(random.nextInt(2)).append('.');
		} else {
			sb.append("192.168.");
		}
		sb.append(random.nextInt(2)).append('.');
		sb.append(random.nextInt(255));
		sb.append(':').append(getRandomPortNumber());
		return sb.toString();
	}

	private int getRandomPortNumber() {
		return 32768 + random.nextInt(32768);
	}

	private String getRandomTorAddress() {
		byte[] pubkeyBytes =
				crypto.generateSignatureKeyPair().getPublic().getEncoded();
		return crypto.encodeOnion(pubkeyBytes);
	}

	private void addAvatar(Contact c) throws DbException {
		AuthorId authorId = c.getAuthor().getId();
		GroupId groupId = groupFactory.createGroup(AvatarManager.CLIENT_ID,
				AvatarManager.MAJOR_VERSION, authorId.getBytes()).getId();
		InputStream is;
		try {
			is = testAvatarCreator.getAvatarInputStream();
		} catch (IOException e) {
			return;
		}
		if (is == null) return;
		Message m;
		try {
			m = avatarMessageEncoder.encodeUpdateMessage(groupId, 0,
					"image/jpeg", is).getFirst();
		} catch (IOException e) {
			throw new DbException(e);
		}
		db.transaction(false, txn -> {
			db.setGroupVisibility(txn, c.getId(), groupId, SHARED);
			db.receiveMessage(txn, c.getId(), m);
		});
	}
	private void shareGroup(ContactId contactId, GroupId groupId)
			throws DbException {
		db.transaction(false, txn ->
				db.setGroupVisibility(txn, contactId, groupId, SHARED));
	}

	private void createPrivateMessages(List<Contact> contacts,
			int numPrivateMsgs) throws DbException {
		for (Contact contact : contacts) {
			Group group = messagingManager.getContactGroup(contact);
			shareGroup(contact.getId(), group.getId());
			for (int i = 0; i < numPrivateMsgs; i++) {
				createRandomPrivateMessage(contact.getId(), group.getId(), i);
			}
		}
	}

	private void createRandomPrivateMessage(ContactId contactId,
			GroupId groupId, int num) throws DbException {
		long timestamp = clock.currentTimeMillis() - (long) num * 60 * 1000;
		String text = getRandomText();
		boolean local = random.nextBoolean();
		boolean autoDelete = random.nextBoolean();
		createPrivateMessage(contactId, groupId, text, timestamp, local,
				autoDelete);
	}

	private void createPrivateMessage(ContactId contactId, GroupId groupId,
			String text, long timestamp, boolean local, boolean autoDelete)
			throws DbException {
		long timer = autoDelete ?
				MIN_AUTO_DELETE_TIMER_MS : NO_AUTO_DELETE_TIMER;
		try {
			PrivateMessage m = privateMessageFactory.createPrivateMessage(
					groupId, timestamp, text, emptyList(), timer);
			if (local) {
				messagingManager.addLocalMessage(m);
			} else {
				db.transaction(false, txn ->
						db.receiveMessage(txn, contactId, m.getMessage()));
			}
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	private String getRandomText() {
		int minLength = 3 + random.nextInt(500);
		int maxWordLength = 15;
		StringBuilder sb = new StringBuilder();
		while (sb.length() < minLength) {
			if (sb.length() > 0) sb.append(' ');
			sb.append(getRandomString(random.nextInt(maxWordLength) + 1));
		}
		if (random.nextBoolean()) {
			sb.append(" \uD83D\uDC96 \uD83E\uDD84 \uD83C\uDF08");
		}
		return sb.toString();
	}

}
