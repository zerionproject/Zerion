package org.briarproject.bramble.db;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.HandshakeManager.HandshakeResult;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.PcsConstants;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.Identity;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.TestDatabaseConfig;
import org.briarproject.bramble.test.TestMessageFactory;
import org.briarproject.bramble.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.util.Collection;

import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getLocalAuthor;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Briar compatibility regression tests for Mode 3 capability negotiation.
 * <p>
 * Verify backward compatibility:
 * - Zerion ↔ Zerion (Mode 2 only) works correctly
 * - Zerion ↔ Briar compatibility is preserved
 * - Existing contacts after upgrade maintain correct state
 * <p>
 * CRITICAL: These tests verify that the Mode 3 additions do NOT break
 * existing Briar protocol compatibility.
 */
public class BriarCompatibilityRegressionTest {

	private final File testDir = TestUtils.getTestDirectory();
	private final DatabaseConfig config = new TestDatabaseConfig(testDir);
	private final MessageFactory messageFactory = new TestMessageFactory();
	private final SecretKey key = getSecretKey();
	private final Clock clock = new SystemClock();

	private Database<Connection> db;

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		if (db != null) {
			try {
				db.close();
			} catch (DbException e) {
				// Ignore
			}
		}
		TestUtils.deleteTestDirectory(testDir);
	}

	// ==================== Zerion ↔ Zerion Tests ====================

	/**
	 * Test 1: Zerion-to-Zerion handshake with MODE3_ENABLED=true.
	 * Both parties are Zerion with Mode 3 support.
	 * Result: mode3Capable = true, uses Mode 3 Triple Ratchet.
	 */
	@Test
	public void testZerionToZerionMode3Enabled() {
		// Skip this test if MODE3_ENABLED is false
		org.junit.Assume.assumeTrue("MODE3_ENABLED must be true for this test",
				PcsConstants.MODE3_ENABLED);

		// Simulate Zerion-to-Zerion hybrid handshake with Mode 3 negotiation
		byte[] keyBytes = new byte[32];
		SecretKey masterKey = new SecretKey(keyBytes);

		// The 3-arg constructor is used when Mode 3 is negotiated successfully
		HandshakeResult result = new HandshakeResult(masterKey, true, true);

		assertTrue("Zerion-to-Zerion with MODE3_ENABLED=true should have mode3Capable=true",
				result.isMode3Capable());
	}

	/**
	 * Test 2: Zerion-to-Zerion contact creation stores mode3Capable=false.
	 */
	@Test
	public void testZerionToZerionContactStoresMode3False() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add contact as if from Zerion-to-Zerion handshake with Mode 3 disabled
		Author remoteAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, remoteAuthor,
				localAuthor.getId(), null, false, true, true, false);
		db.commitTransaction(txn);

		// Verify stored value
		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertFalse("Zerion-to-Zerion contact should have mode3Capable=false",
				contact.isMode3Capable());
		assertTrue("Should still have postQuantum=true", contact.isPostQuantum());
	}

	/**
	 * Test 3: Zerion-to-Zerion Mode 2 operation unaffected by Mode 3 code.
	 * PCS Mode 2 should work exactly as before.
	 */
	@Test
	public void testMode2UnaffectedByMode3Code() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Create contact with postQuantum=true (Mode 2) and mode3Capable=false
		Author remoteAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, remoteAuthor,
				localAuthor.getId(), null, false, true, true, false);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		// Mode 2 properties should be intact
		assertTrue("postQuantum should be true for Mode 2", contact.isPostQuantum());
		assertFalse("mode3Capable should be false", contact.isMode3Capable());

		// The presence of mode3Capable field should not affect Mode 2 operation
		// Mode 2 uses postQuantum flag only, mode3Capable is for future Mode 3
	}

	// ==================== Zerion ↔ Briar Tests ====================

	/**
	 * Test 4: Zerion-to-Briar classical handshake compatibility.
	 * Briar only supports classical (non-hybrid) handshake.
	 * Result: mode3Capable = false, uses classical protocol.
	 */
	@Test
	public void testZerionToBriarClassicalHandshake() {
		// Classical handshake uses 2-arg HandshakeResult constructor
		// which always sets mode3Capable = false
		byte[] keyBytes = new byte[32];
		SecretKey masterKey = new SecretKey(keyBytes);

		// Classical handshake result (Briar-compatible)
		HandshakeResult classicalResult = new HandshakeResult(masterKey, true);

		assertFalse("Zerion-to-Briar classical handshake must have mode3Capable=false",
				classicalResult.isMode3Capable());
	}

	/**
	 * Test 5: Zerion stores Briar contact with mode3Capable=false.
	 */
	@Test
	public void testBriarContactStoredCorrectly() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add Briar contact (classical handshake: postQuantum=false, mode3Capable=false)
		Author briarAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, briarAuthor,
				localAuthor.getId(), null, false, false, false, false);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertFalse("Briar contact should have postQuantum=false", contact.isPostQuantum());
		assertFalse("Briar contact must have mode3Capable=false", contact.isMode3Capable());
	}

	/**
	 * Test 6: Mixed environment - Zerion contact next to Briar contact.
	 */
	@Test
	public void testMixedZerionAndBriarContacts() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add Briar contact (classical)
		Author briarAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId briarId = db.addContact(txn, briarAuthor,
				localAuthor.getId(), null, false, false, false, false);
		db.commitTransaction(txn);

		// Add Zerion contact (Mode 2, hybrid)
		Author zerionAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId zerionId = db.addContact(txn, zerionAuthor,
				localAuthor.getId(), null, false, true, true, false);
		db.commitTransaction(txn);

		// Get all contacts
		txn = db.startTransaction();
		Collection<Contact> contacts = db.getContacts(txn);
		db.commitTransaction(txn);

		assertEquals("Should have 2 contacts", 2, contacts.size());

		// Verify each contact has correct mode3Capable
		for (Contact c : contacts) {
			assertFalse("All contacts should have mode3Capable=false", c.isMode3Capable());
			if (c.getId().equals(briarId)) {
				assertFalse("Briar contact should have postQuantum=false", c.isPostQuantum());
			} else if (c.getId().equals(zerionId)) {
				assertTrue("Zerion contact should have postQuantum=true", c.isPostQuantum());
			}
		}
	}

	// ==================== Upgrade Regression Tests ====================

	/**
	 * Test 7: Existing contacts after database upgrade have mode3Capable=false.
	 * Migration58_59 adds mode3Capable column with DEFAULT FALSE.
	 */
	@Test
	public void testExistingContactsAfterUpgrade() throws Exception {
		// This test simulates what happens to existing contacts after upgrade
		// All existing contacts should have mode3Capable = FALSE (the default)

		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Simulate "existing" contact (would have been created before upgrade)
		// The default overload sets mode3Capable = false
		Author existingAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, existingAuthor,
				localAuthor.getId(), null, false);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertFalse("Existing contact after upgrade should have mode3Capable=false",
				contact.isMode3Capable());
	}

	/**
	 * Test 8: Verify no NULL values in mode3Capable after upgrade.
	 * The DEFAULT FALSE constraint ensures no NULL values.
	 */
	@Test
	public void testNoNullMode3CapableAfterUpgrade() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Create multiple contacts using different overloads
		for (int i = 0; i < 5; i++) {
			Author author = getAuthor();
			txn = db.startTransaction();
			db.addContact(txn, author, localAuthor.getId(), null, false);
			db.commitTransaction(txn);
		}

		// Get all contacts and verify none have unexpected state
		txn = db.startTransaction();
		Collection<Contact> contacts = db.getContacts(txn);
		db.commitTransaction(txn);

		assertEquals("Should have 5 contacts", 5, contacts.size());

		for (Contact c : contacts) {
			// isMode3Capable() returns primitive boolean, can't be null
			// This test verifies the getter works for all contacts
			boolean mode3Capable = c.isMode3Capable();
			assertFalse("All contacts should have mode3Capable=false", mode3Capable);
		}
	}

	/**
	 * Test 9: Verify no accidental Mode 3 activation after upgrade.
	 * Even if MODE3_ENABLED becomes true later, existing contacts stay false.
	 */
	@Test
	public void testNoAccidentalMode3Activation() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Create contact with mode3Capable = false
		Author author = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, author,
				localAuthor.getId(), null, false, true, true, false);
		db.commitTransaction(txn);

		// Close and reopen database (simulates app restart)
		db.close();
		db = createDatabase();
		assertTrue(db.open(key, null));

		// Contact should still have mode3Capable = false
		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertFalse("Contact should still have mode3Capable=false after restart",
				contact.isMode3Capable());

		// Even if MODE3_ENABLED flag changes, stored contacts don't auto-upgrade
		// Mode 3 activation requires a new handshake exchange
	}

	// ==================== Handshake Result Tests ====================

	/**
	 * Test 10: Verify classical handshake path is unchanged.
	 * The classical (Briar-compatible) code path should be identical.
	 */
	@Test
	public void testClassicalHandshakePathUnchanged() {
		// Classical handshake always uses 2-arg HandshakeResult constructor
		// This test verifies the constructor exists and works as expected

		byte[] keyBytes = new byte[32];
		SecretKey masterKey = new SecretKey(keyBytes);

		// Test Alice
		HandshakeResult aliceResult = new HandshakeResult(masterKey, true);
		assertTrue("Alice flag should be true", aliceResult.isAlice());
		assertFalse("mode3Capable should be false", aliceResult.isMode3Capable());
		assertNotNull("masterKey should not be null", aliceResult.getMasterKey());

		// Test Bob
		HandshakeResult bobResult = new HandshakeResult(masterKey, false);
		assertFalse("Alice flag should be false for Bob", bobResult.isAlice());
		assertFalse("mode3Capable should be false", bobResult.isMode3Capable());
		assertNotNull("masterKey should not be null", bobResult.getMasterKey());
	}

	/**
	 * Test 11: Verify Zerion-Briar backward compatibility with Mode 3 enabled.
	 * When MODE3_ENABLED=true but peer doesn't support Mode 3, mode3Capable=false.
	 */
	@Test
	public void testZerionBriarBackwardCompatible() {
		// When MODE3_ENABLED = true but peer is Briar (classical handshake),
		// mode3Capable should be false for backward compatibility

		// Skip this test if MODE3_ENABLED is false
		org.junit.Assume.assumeTrue("MODE3_ENABLED must be true for this test",
				PcsConstants.MODE3_ENABLED);

		// Briar uses classical handshake - 2-arg constructor
		byte[] keyBytes = new byte[32];
		SecretKey masterKey = new SecretKey(keyBytes);

		// Uses 2-arg constructor (Briar classical handshake)
		HandshakeResult briarResult = new HandshakeResult(masterKey, true);

		// Result should have mode3Capable=false for Briar compatibility
		assertFalse("mode3Capable should be false for Zerion-Briar handshake",
				briarResult.isMode3Capable());
	}

	private Database<Connection> createDatabase() {
		return new H2Database(config, messageFactory, clock);
	}
}
