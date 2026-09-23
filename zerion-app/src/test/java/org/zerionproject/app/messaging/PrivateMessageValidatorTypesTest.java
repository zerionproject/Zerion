package org.zerionproject.app.messaging;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.InputStream;

import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_ACCEPT;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_DECLINE;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_DISSOLVED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_EPOCH_COMMIT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ADDED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LEFT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_REMOVED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ROLE_CHANGED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.app.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.MESH_PREKEY_BUNDLE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.TYPING_INDICATOR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Accept and reject cases for every private message type that had no
 * validator test: attachment manifests and chunks, reactions, typing
 * indicators, link previews, mesh prekey bundles and every group record.
 * Each type has a well-formed body that is accepted with the expected
 * dependencies and the bounds the validator enforces, each of which is shown
 * to reject.
 */
public class PrivateMessageValidatorTypesTest extends BrambleMockTestCase {

	private final BdfReaderFactory bdfReaderFactory =
			context.mock(BdfReaderFactory.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final Clock clock = context.mock(Clock.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final BdfReader reader = context.mock(BdfReader.class);

	private final Group group = getGroup(getClientId(), 123);
	private final Message message = getMessage(group.getId());
	private final long now = message.getTimestamp() + 1000;
	private final byte[] id = getRandomBytes(32);
	private final byte[] key = getRandomBytes(32);
	private final byte[] sig = getRandomBytes(64);

	private final PrivateMessageValidator validator =
			new PrivateMessageValidator(bdfReaderFactory, metadataEncoder,
					clock, crypto);

	@Test
	public void attachmentManifest() throws Exception {
		accept(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 1000L, 2, id,
				BdfList.of(getRandomBytes(32), getRandomBytes(32))), true, 2);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 1000L, 2, id,
				BdfList.of(getRandomBytes(32))), true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 0L, 1, id,
				BdfList.of(getRandomBytes(32))), true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg",
				10L * 1024 * 1024 + 1, 1, id, BdfList.of(getRandomBytes(32))),
				true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 1000L, 101, id,
				BdfList.of(getRandomBytes(32))), true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 1000L, 1,
				getRandomBytes(31), BdfList.of(getRandomBytes(32))), true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "", 1000L, 1, id,
				BdfList.of(getRandomBytes(32))), true);
		reject(BdfList.of(ATTACHMENT_MANIFEST, "image/jpeg", 1000L, 1, id,
				BdfList.of(getRandomBytes(31))), true);
	}

	@Test
	public void attachmentChunk() throws Exception {
		Message chunk = getMessage(group.getId(), 500);
		accept(chunk, BdfList.of(ATTACHMENT_CHUNK, 3, 500), false, 0);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, 3, 499), false);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, 100, 500), false);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, -1, 500), false);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, 0, 0), false);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, 0, 512 * 1024 + 1), false);
		reject(chunk, BdfList.of(ATTACHMENT_CHUNK, 0), false);
	}

	@Test
	public void messageReaction() throws Exception {
		accept(BdfList.of(MESSAGE_REACTION, id, "heart"), true, 0);
		accept(BdfList.of(MESSAGE_REACTION, id, "👍"), true, 0);
		reject(BdfList.of(MESSAGE_REACTION, id, "🙃"), true);
		reject(BdfList.of(MESSAGE_REACTION, id, ""), true);
		reject(BdfList.of(MESSAGE_REACTION, getRandomBytes(31), "heart"), true);
		reject(BdfList.of(MESSAGE_REACTION, id), true);
	}

	@Test
	public void typingIndicator() throws Exception {
		accept(BdfList.of(TYPING_INDICATOR, true), true, 0);
		accept(BdfList.of(TYPING_INDICATOR, false), true, 0);
		reject(BdfList.of(TYPING_INDICATOR, true, 1), true);
		reject(BdfList.of(TYPING_INDICATOR, 1), true);
		reject(BdfList.of(TYPING_INDICATOR), true);
	}

	@Test
	public void linkPreview() throws Exception {
		accept(BdfList.of(LINK_PREVIEW_MESSAGE, "text", "https://example.org",
				"title", null), true, 0);
		accept(BdfList.of(LINK_PREVIEW_MESSAGE, null, "https://example.org",
				"title", "description", getRandomBytes(100)), true, 0);
		reject(BdfList.of(LINK_PREVIEW_MESSAGE, "text", "", "title", null),
				true);
		reject(BdfList.of(LINK_PREVIEW_MESSAGE, "text", "https://example.org",
				getRandomString(513), null), true);
		reject(BdfList.of(LINK_PREVIEW_MESSAGE, "text",
				getRandomString(2049), "title", null), true);
		reject(BdfList.of(LINK_PREVIEW_MESSAGE, "text", "https://example.org",
				"title", getRandomString(1025)), true);
		reject(BdfList.of(LINK_PREVIEW_MESSAGE, "text", "https://example.org"),
				true);
	}

	@Test
	public void meshPrekeyBundle() throws Exception {
		accept(BdfList.of(MESH_PREKEY_BUNDLE, getRandomBytes(100)), true, 0);
		reject(BdfList.of(MESH_PREKEY_BUNDLE), true);
		reject(BdfList.of(MESH_PREKEY_BUNDLE, "not raw"), true);
		reject(BdfList.of(MESH_PREKEY_BUNDLE, getRandomBytes(1), 2), true);
	}

	@Test
	public void groupPost() throws Exception {
		allowHash();
		allowSignature(true);
		accept(BdfList.of(GROUP_POST, id, 5L, key, "name", getRandomBytes(10),
				sig), true, 0);
		accept(BdfList.of(GROUP_POST, id, 5L, key, "name", getRandomBytes(10),
				sig, 60_000L), true, 0);
		reject(BdfList.of(GROUP_POST, id, 0x1_0000_0000L, key, "name",
				getRandomBytes(10), sig), true);
		reject(BdfList.of(GROUP_POST, id, -1L, key, "name", getRandomBytes(10),
				sig), true);
		reject(BdfList.of(GROUP_POST, id, 5L, key, "name", new byte[0], sig),
				true);
		reject(BdfList.of(GROUP_POST, id, 5L, getRandomBytes(31), "name",
				getRandomBytes(10), sig), true);
		reject(BdfList.of(GROUP_POST, id, 5L, key, getRandomString(257),
				getRandomBytes(10), sig), true);
		reject(BdfList.of(GROUP_POST, id, 5L, key, "name", getRandomBytes(10),
				new byte[0]), true);
	}

	@Test
	public void groupPostWithAFailingSignatureIsRejected() throws Exception {
		allowHash();
		allowSignature(false);
		reject(BdfList.of(GROUP_POST, id, 5L, key, "name", getRandomBytes(10),
				sig), true);
	}

	@Test
	public void groupMemberAdded() throws Exception {
		accept(BdfList.of(GROUP_MEMBER_ADDED, id, key, "bob", 1L, now, sig),
				true, 0);
		reject(BdfList.of(GROUP_MEMBER_ADDED, id, key, "", 1L, now, sig), true);
		reject(BdfList.of(GROUP_MEMBER_ADDED, id, key, getRandomString(257),
				1L, now, sig), true);
		reject(BdfList.of(GROUP_MEMBER_ADDED, id, key, "bob", 0x1_0000_0000L,
				now, sig), true);
		reject(BdfList.of(GROUP_MEMBER_ADDED, id, getRandomBytes(33), "bob",
				1L, now, sig), true);
		reject(BdfList.of(GROUP_MEMBER_ADDED, id, key, "bob", 1L, now), true);
	}

	@Test
	public void groupMemberRemoved() throws Exception {
		accept(BdfList.of(GROUP_MEMBER_REMOVED, id, key, 1L, 2L, now, sig),
				true, 0);
		reject(BdfList.of(GROUP_MEMBER_REMOVED, id, key, 1L, 3L, now, sig),
				true);
		reject(BdfList.of(GROUP_MEMBER_REMOVED, id, key, 2L, 1L, now, sig),
				true);
		reject(BdfList.of(GROUP_MEMBER_REMOVED, id, key, -1L, 0L, now, sig),
				true);
		reject(BdfList.of(GROUP_MEMBER_REMOVED, id, key, 1L, 2L, now,
				getRandomBytes(4097)), true);
	}

	@Test
	public void groupMemberLeft() throws Exception {
		allowSignature(true);
		accept(BdfList.of(GROUP_MEMBER_LEFT, id, key, 1L, now, sig), true, 0);
		reject(BdfList.of(GROUP_MEMBER_LEFT, id, key, 0x1_0000_0000L, now,
				sig), true);
		reject(BdfList.of(GROUP_MEMBER_LEFT, id, key, 1L, now), true);
	}

	@Test
	public void groupMemberLeftWithAFailingSignatureIsRejected()
			throws Exception {
		allowSignature(false);
		reject(BdfList.of(GROUP_MEMBER_LEFT, id, key, 1L, now, sig), true);
	}

	@Test
	public void groupDissolved() throws Exception {
		accept(BdfList.of(GROUP_DISSOLVED, id, 1L, now, sig), true, 0);
		reject(BdfList.of(GROUP_DISSOLVED, id, 1L, now), true);
		reject(BdfList.of(GROUP_DISSOLVED, id, -1L, now, sig), true);
		reject(BdfList.of(GROUP_DISSOLVED, getRandomBytes(31), 1L, now, sig),
				true);
	}

	@Test
	public void groupEpochCommit() throws Exception {
		allowHash();
		accept(BdfList.of(GROUP_EPOCH_COMMIT, id, 1L, 2L, getRandomBytes(32),
				sig), true, 0);
		reject(BdfList.of(GROUP_EPOCH_COMMIT, id, 1L, 1L, getRandomBytes(32),
				sig), true);
		reject(BdfList.of(GROUP_EPOCH_COMMIT, id, 1L, 2L, new byte[0], sig),
				true);
		reject(BdfList.of(GROUP_EPOCH_COMMIT, id, 1L, 2L,
				getRandomBytes(4097), sig), true);
		reject(BdfList.of(GROUP_EPOCH_COMMIT, id, 1L, 2L, getRandomBytes(32)),
				true);
	}

	@Test
	public void groupRoleChanged() throws Exception {
		accept(BdfList.of(GROUP_MEMBER_ROLE_CHANGED, id, key, 2L, 1L, now,
				sig), true, 0);
		reject(BdfList.of(GROUP_MEMBER_ROLE_CHANGED, id, key, 3L, 1L, now,
				sig), true);
		reject(BdfList.of(GROUP_MEMBER_ROLE_CHANGED, id, key, -1L, 1L, now,
				sig), true);
		reject(BdfList.of(GROUP_MEMBER_ROLE_CHANGED, id, key, 1L,
				0x1_0000_0000L, now, sig), true);
	}

	@Test
	public void groupMemberListSnapshot() throws Exception {
		allowHash();
		accept(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				BdfList.of(member(0L), member(2L)), sig), true, 0);
		accept(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				new BdfList(), sig), true, 0);
		reject(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				BdfList.of(member(3L)), sig), true);
		reject(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				BdfList.of(BdfList.of(key, "a", 1L, 1L)), sig), true);
		reject(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				BdfList.of(BdfList.of(getRandomBytes(31), "a", 1L, 1L, 0L)),
				sig), true);
		reject(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now,
				BdfList.of(BdfList.of(key, "a", -1L, 1L, 0L)), sig), true);
		BdfList tooMany = new BdfList();
		for (int i = 0; i < 257; i++) tooMany.add(member(0L));
		reject(BdfList.of(GROUP_MEMBER_LIST_SNAPSHOT, id, 1L, now, tooMany,
				sig), true);
	}

	@Test
	public void grouptrInvites() throws Exception {
		accept(BdfList.of(GROUPTR_INVITE_OFFER, id, "Group", getRandomBytes(32),
				"creator", key, now, sig), true, 0);
		reject(BdfList.of(GROUPTR_INVITE_OFFER, id, getRandomString(101),
				getRandomBytes(32), "creator", key, now, sig), true);
		reject(BdfList.of(GROUPTR_INVITE_OFFER, id, "Group", getRandomBytes(31),
				"creator", key, now, sig), true);
		reject(BdfList.of(GROUPTR_INVITE_OFFER, id, "Group", getRandomBytes(32),
				"", key, now, sig), true);
		accept(BdfList.of(GROUPTR_INVITE_ACCEPT, id, now, sig), true, 0);
		accept(BdfList.of(GROUPTR_INVITE_DECLINE, id, now, sig), true, 0);
		reject(BdfList.of(GROUPTR_INVITE_ACCEPT, id, now), true);
		reject(BdfList.of(GROUPTR_INVITE_DECLINE, id, now, new byte[0]), true);
	}

	@Test
	public void unknownAndReservedTypesAreRejected() throws Exception {
		reject(BdfList.of(39, id), false);
		reject(BdfList.of(40, id), false);
		reject(BdfList.of(99, id), false);
		reject(BdfList.of(-1, id), false);
	}

	private BdfList member(long role) {
		return BdfList.of(getRandomBytes(32), "name", 1L, 1L, role);
	}

	private void accept(BdfList body, boolean eof, int dependencies)
			throws Exception {
		accept(message, body, eof, dependencies);
	}

	private void accept(Message m, BdfList body, boolean eof,
			int dependencies) throws Exception {
		expectValidation(body, eof);
		context.checking(new Expectations() {{
			oneOf(metadataEncoder).encode(with(any(BdfDictionary.class)));
			will(returnValue(new Metadata()));
		}});
		MessageContext result = validator.validateMessage(m, group);
		assertEquals(dependencies, result.getDependencies().size());
	}

	private void reject(BdfList body, boolean eof) throws Exception {
		reject(message, body, eof);
	}

	private void reject(Message m, BdfList body, boolean eof)
			throws Exception {
		expectValidation(body, eof);
		try {
			validator.validateMessage(m, group);
			fail("accepted " + body);
		} catch (InvalidMessageException expected) {
		}
	}

	private void expectValidation(BdfList body, boolean eof)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(bdfReaderFactory).createReader(with(any(InputStream.class)),
					with(any(int.class)), with(any(int.class)),
					with(any(boolean.class)));
			will(returnValue(reader));
			oneOf(reader).readList();
			will(returnValue(body));
			if (eof) {
				allowing(reader).eof();
				will(returnValue(true));
			}
		}});
	}

	private void allowHash() {
		context.checking(new Expectations() {{
			allowing(crypto).hash(with(any(String.class)),
					with(any(byte[][].class)));
			will(returnValue(getRandomBytes(32)));
		}});
	}

	private void allowSignature(boolean valid) throws Exception {
		context.checking(new Expectations() {{
			allowing(crypto).verifySignature(with(any(byte[].class)),
					with(any(String.class)), with(any(byte[].class)),
					with(any(PublicKey.class)));
			will(returnValue(valid));
		}});
	}
}
