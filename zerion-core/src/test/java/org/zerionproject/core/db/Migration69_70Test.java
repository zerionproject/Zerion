package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;

public class Migration69_70Test extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private HyperSqlDatabase db;

	@Before
	public void setUp() {
		assumeTrue(isCryptoStrengthUnlimited());
		assertTrue(testDir.mkdirs());
		db = new HyperSqlDatabase(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void linkContactsAreUnverifiedAndDeadKeysAreDeleted()
			throws Exception {
		db.open(getSecretKey(), null);
		Identity identity = getIdentity();
		SecretKey root = getSecretKey();
		ContactId[] ids = inTransaction(txn -> {
			db.addIdentity(txn, identity);
			ContactId link = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(),
					getAgreementPublicKey(), true, true);
			ContactId nearby = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true, true);
			ContactId introduced = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, false, false);
			db.setPcsMode2SessionState(txn, link, PCS_DIRECTION_SEND, root,
					0, 0, root, new AgreementPrivateKey(getRandomBytes(32)),
					new AgreementPublicKey(getRandomBytes(32)), null, true,
					new byte[] {1, 2, 3});
			PreparedStatement ps = txn.prepareStatement(
					"INSERT INTO pqRatchetState (contactId, epochStartTime,"
							+ " ourDecapsKey) VALUES (?, ?, ?)");
			ps.setInt(1, link.getInt());
			ps.setLong(2, 1L);
			ps.setBytes(3, new byte[] {9, 9, 9});
			ps.executeUpdate();
			ps.close();
			return new ContactId[] {link, nearby, introduced};
		});

		for (int run = 0; run < 2; run++) {
			inTransaction(txn -> {
				new Migration69_70().migrate(txn);
				return null;
			});
		}

		inTransaction(txn -> {
			assertFalse(db.getContact(txn, ids[0]).isVerified());
			assertTrue(db.getContact(txn, ids[1]).isVerified());
			assertFalse(db.getContact(txn, ids[2]).isVerified());
			Object[] row = db.getPcsMode2SessionState(txn, ids[0],
					PCS_DIRECTION_SEND);
			assertArrayEquals(root.getBytes(), (byte[]) row[3]);
			assertNull(row[4]);
			assertNull(row[5]);
			assertNull(row[8]);
			assertFalse(db.containsPqRatchetState(txn, ids[0]));
			ResultSet rs = txn.createStatement().executeQuery(
					"SELECT COUNT(*) FROM pcsSkippedKeys");
			assertTrue(rs.next());
			assertEquals(0, rs.getInt(1));
			rs.close();
			return null;
		});
	}

	private interface Task<R> {
		R run(Connection txn) throws Exception;
	}

	private <R> R inTransaction(Task<R> task) throws Exception {
		Connection txn = db.startTransaction();
		boolean committed = false;
		try {
			R result = task.run(txn);
			db.commitTransaction(txn);
			committed = true;
			return result;
		} finally {
			if (!committed) db.abortTransaction(txn);
		}
	}
}
