package org.zerionproject.core.db;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.system.SystemClock;
import com.professor.zerion.android.testing.Inert;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.zerionproject.core.db.DatabaseConstants.SCHEMA_VERSION_KEY;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;

public class Migration72_73DeviceTest {

	private static final String[] NEW_TABLES = {"contactLinks",
			"requestAccount", "outgoingRequests", "incomingRequests",
			"requestReplay", "requestBlocks"};

	private final File testDir = new File(androidx.test.platform.app
			.InstrumentationRegistry.getInstrumentation().getTargetContext()
			.getCacheDir(), "migration-72-73-" + System.nanoTime());
	private final SecretKey key = getSecretKey();
	private final DatabaseTypes sqlCipherTypes = new DatabaseTypes("BLOB",
			"BLOB", "BLOB", "INTEGER", "TEXT");

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private SqlCipherDatabase newDatabase() {
		DatabaseConfig config = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(testDir, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(testDir, "key");
			}

			@Nullable
			@Override
			public KeyStrengthener getKeyStrengthener() {
				return null;
			}
		};
		return new SqlCipherDatabase(config, Inert.of(MessageFactory.class),
				new SystemClock());
	}

	@Test
	public void aVersion72DatabaseMigratesTwiceAndKeepsItsPendingContacts()
			throws Exception {
		byte[] pendingId = getRandomId();
		byte[] blob = getRandomBytes(64);
		SqlCipherDatabase db = newDatabase();
		assertFalse(db.open(key, null));
		Connection txn = db.startTransaction();
		db.addIdentity(txn, getIdentity());
		Statement st = txn.createStatement();
		for (String table : NEW_TABLES) st.execute("DROP TABLE " + table);
		st.execute("ALTER TABLE pendingContacts DROP COLUMN expiry");
		st.execute("ALTER TABLE pendingContacts DROP COLUMN rendezvousMode");
		st.close();
		PreparedStatement ps = txn.prepareStatement("INSERT INTO"
				+ " pendingContacts (pendingContactId, publicKey, alias,"
				+ " timestamp, formatVersion) VALUES (?, ?, ?, ?, ?)");
		ps.setBytes(1, pendingId);
		ps.setBytes(2, blob);
		ps.setString(3, "old");
		ps.setLong(4, 1234);
		ps.setInt(5, 1);
		assertEquals(1, ps.executeUpdate());
		ps.close();
		Settings s = new Settings();
		s.putInt(SCHEMA_VERSION_KEY, 72);
		db.mergeSettings(txn, s, DB_SETTINGS_NAMESPACE);
		db.commitTransaction(txn);
		db.close();

		db = newDatabase();
		assertTrue(db.open(key, null));
		txn = db.startTransaction();
		assertEquals(73, db.getSettings(txn, DB_SETTINGS_NAMESPACE)
				.getInt(SCHEMA_VERSION_KEY, -1));
		for (int run = 0; run < 2; run++) {
			new Migration72_73(sqlCipherTypes).migrate(txn);
			Collection<PendingContact> pending = db.getPendingContacts(txn);
			assertEquals(1, pending.size());
			PendingContact p = pending.iterator().next();
			assertArrayEquals(pendingId, p.getId().getBytes());
			assertEquals(0, p.getExpiry());
			assertEquals(0, p.getRendezvousMode());
		}
		java.util.Set<String> indexes = new java.util.HashSet<>();
		Statement q = txn.createStatement();
		java.sql.ResultSet rs = q.executeQuery("SELECT name FROM sqlite_master"
				+ " WHERE type = 'index' AND tbl_name = 'requestReplay'");
		while (rs.next()) indexes.add(rs.getString(1));
		rs.close();
		q.close();
		assertTrue(indexes.contains("requestReplayByOneTimeKey"));
		assertTrue(indexes.contains("requestReplayByLink"));
		PendingContact added = new PendingContact(
				new PendingContactId(getRandomId()),
				new HybridCommitmentPublicKey(getRandomBytes(64)), "alias",
				1000, 1, 5000, 1);
		db.addPendingContact(txn, added);
		PendingContact read = db.getPendingContact(txn, added.getId());
		assertEquals(5000, read.getExpiry());
		assertEquals(1, read.getRendezvousMode());
		byte[] linkId = getRandomId();
		db.addContactLink(txn, new ContactLink(linkId,
				new AuthorId(getRandomId()), ContactLink.KIND_PERMANENT,
				ContactLink.STATE_ACTIVE, "Support", getRandomBytes(1216),
				getRandomBytes(2432), getRandomBytes(32), 1, 0, 0, 0));
		ContactLink link = db.getContactLinks(txn).iterator().next();
		assertEquals("Support", link.getLabel());
		assertEquals(0, link.getLastSharedAt());
		db.addRequestBlock(txn, linkId, getRandomId(), 1);
		assertEquals(1, db.getRequestBlocks(txn, linkId).size());
		db.commitTransaction(txn);
		db.close();
	}
}
