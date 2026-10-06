package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupConversionLoadTest {

	private static final long EPOCH = 4L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void bodiesAreNotReadUnderTheWriteLock() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		for (int i = 0; i < 5; i++) {
			node.store(a, groupId, aKey, EPOCH, getRandomBytes(100_000),
					node.now + i, 0L);
		}

		node.manager.onDatabaseOpened(new Transaction(null, false));

		assertEquals("post body bytes read while the write lock was held",
				0L, node.bodyBytesUnderWriteLock());
		for (MessageId id : node.storedIds()) {
			BdfDictionary meta = node.metadata(id);
			assertNotNull(meta);
			assertNull(meta.get("groupCiphertext"));
			assertEquals(100_000L, (long) meta.getLong("groupBodyLength"));
		}
	}

	@Test
	public void aPostWhoseBodyIsGoneDoesNotStopTheConversion()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		MessageId first = node.store(a, groupId, aKey, EPOCH,
				getRandomBytes(1000), node.now, 0L);
		MessageId broken = node.store(a, groupId, aKey, EPOCH,
				getRandomBytes(1000), node.now + 1, 0L);
		MessageId last = node.store(a, groupId, aKey, EPOCH,
				getRandomBytes(1000), node.now + 2, 0L);
		node.makeMetadataUnparseable(broken);
		node.deleteBody(broken);

		node.manager.onDatabaseOpened(new Transaction(null, false));

		for (MessageId id : Arrays.asList(first, last)) {
			BdfDictionary meta = node.metadata(id);
			assertNotNull(meta);
			assertNull("a post after the broken one was not converted",
					meta.get("groupCiphertext"));
		}
		node.forgetReads();
		node.manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals("the conversion ran again at the next start", 0,
				node.metadataReads());
		assertTrue(node.isStored(broken));
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
