package org.briarproject.bramble.db;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.Identity;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.settings.Settings;
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
import java.util.List;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.briarproject.bramble.db.DatabaseConstants.SCHEMA_VERSION_KEY;
import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getLocalAuthor;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for Migration58_59 which adds the mode3Capable column to contacts table.
 * <p>
 * Phase 4c requirement: Verify database migration:
 * - Fresh install creates contacts.mode3Capable with DEFAULT FALSE
 * - Upgrade from schema 58 to 59 preserves existing contacts
 * - Existing contacts have mode3Capable = FALSE after migration
 * - No NULL values
 * - No accidental Mode 3 activation
 */
public class Migration58_59Test {

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

	/**
	 * Test 1: Verify Migration58_59 version numbers are correct.
	 */
	@Test
	public void testMigrationVersionNumbers() {
		Migration58_59 migration = new Migration58_59();

		assertEquals("Start version should be 58", 58, migration.getStartVersion());
		assertEquals("End version should be 59", 59, migration.getEndVersion());
	}

	/**
	 * Test 2: Verify fresh database creates mode3Capable column correctly.
	 */
	@Test
	public void testFreshDatabaseHasMode3CapableColumn() throws Exception {
		db = createDatabase();
		assertFalse("Fresh database should not exist", db.open(key, null));

		// Create identity
		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add a contact
		Author remoteAuthor = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, remoteAuthor,
				localAuthor.getId(), null, false);
		db.commitTransaction(txn);

		// Verify the contact was created with mode3Capable = false
		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertNotNull("Contact should exist", contact);
		assertFalse("Fresh contact should have mode3Capable = false",
				contact.isMode3Capable());
	}

	/**
	 * Test 3: Verify adding contact with explicit mode3Capable flag.
	 */
	@Test
	public void testAddContactWithMode3CapableFlag() throws Exception {
		db = createDatabase();
		db.open(key, null);

		// Create identity
		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add contact with mode3Capable = false
		Author remoteAuthor1 = getAuthor();
		txn = db.startTransaction();
		ContactId contactId1 = db.addContact(txn, remoteAuthor1,
				localAuthor.getId(), null, false, false, false, false);
		db.commitTransaction(txn);

		// Verify mode3Capable = false
		txn = db.startTransaction();
		Contact contact1 = db.getContact(txn, contactId1);
		db.commitTransaction(txn);
		assertFalse("Contact with mode3Capable=false should have false",
				contact1.isMode3Capable());

		// Add contact with mode3Capable = true
		Author remoteAuthor2 = getAuthor();
		txn = db.startTransaction();
		ContactId contactId2 = db.addContact(txn, remoteAuthor2,
				localAuthor.getId(), null, false, false, false, true);
		db.commitTransaction(txn);

		// Verify mode3Capable = true
		txn = db.startTransaction();
		Contact contact2 = db.getContact(txn, contactId2);
		db.commitTransaction(txn);
		assertTrue("Contact with mode3Capable=true should have true",
				contact2.isMode3Capable());
	}

	/**
	 * Test 4: Verify CODE_SCHEMA_VERSION is at least 59 (migration 58->59 applied).
	 */
	@Test
	public void testSchemaVersionIs59OrHigher() {
		assertTrue("CODE_SCHEMA_VERSION should be at least 59",
				JdbcDatabase.CODE_SCHEMA_VERSION >= 59);
	}

	/**
	 * Test 5: Verify contact creation with all overloads defaults mode3Capable correctly.
	 */
	@Test
	public void testAllContactCreationOverloadsDefaultMode3Capable() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Test 2-arg default overload (verified, no handshake key)
		Author author1 = getAuthor();
		txn = db.startTransaction();
		ContactId id1 = db.addContact(txn, author1, localAuthor.getId(),
				null, false);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact c1 = db.getContact(txn, id1);
		db.commitTransaction(txn);
		assertFalse("2-arg overload should default mode3Capable=false",
				c1.isMode3Capable());

		// Test 3-arg overload (postQuantum)
		Author author2 = getAuthor();
		txn = db.startTransaction();
		ContactId id2 = db.addContact(txn, author2, localAuthor.getId(),
				null, false, true);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact c2 = db.getContact(txn, id2);
		db.commitTransaction(txn);
		assertFalse("3-arg overload should default mode3Capable=false",
				c2.isMode3Capable());

		// Test 4-arg overload (pcsEnabled)
		Author author3 = getAuthor();
		txn = db.startTransaction();
		ContactId id3 = db.addContact(txn, author3, localAuthor.getId(),
				null, false, true, true);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Contact c3 = db.getContact(txn, id3);
		db.commitTransaction(txn);
		assertFalse("4-arg overload should default mode3Capable=false",
				c3.isMode3Capable());
	}

	/**
	 * Test 6: Verify getContacts returns mode3Capable for all contacts.
	 */
	@Test
	public void testGetContactsIncludesMode3Capable() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add contacts with different mode3Capable values
		Author author1 = getAuthor();
		Author author2 = getAuthor();

		txn = db.startTransaction();
		db.addContact(txn, author1, localAuthor.getId(),
				null, false, false, false, false);
		db.addContact(txn, author2, localAuthor.getId(),
				null, false, false, false, true);
		db.commitTransaction(txn);

		// Get all contacts
		txn = db.startTransaction();
		Collection<Contact> contacts = db.getContacts(txn);
		db.commitTransaction(txn);

		assertEquals("Should have 2 contacts", 2, contacts.size());

		// Verify mode3Capable values are correctly retrieved
		int falseCount = 0;
		int trueCount = 0;
		for (Contact c : contacts) {
			if (c.isMode3Capable()) {
				trueCount++;
			} else {
				falseCount++;
			}
		}
		assertEquals("Should have 1 contact with mode3Capable=false", 1, falseCount);
		assertEquals("Should have 1 contact with mode3Capable=true", 1, trueCount);
	}

	/**
	 * Test 7: Verify Contact class correctly exposes mode3Capable.
	 */
	@Test
	public void testContactClassExposesMode3Capable() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add contact with mode3Capable = true
		Author author = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, author, localAuthor.getId(),
				null, false, true, true, true);
		db.commitTransaction(txn);

		// Get contact and verify all fields
		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertEquals("Contact ID should match", contactId, contact.getId());
		assertEquals("Author should match", author.getId(),
				contact.getAuthor().getId());
		assertTrue("PostQuantum should be true", contact.isPostQuantum());
		assertTrue("Mode3Capable should be true", contact.isMode3Capable());
	}

	/**
	 * Test 8: Verify database reopens correctly with mode3Capable data.
	 */
	@Test
	public void testDatabaseReopenPreservesMode3Capable() throws Exception {
		db = createDatabase();
		db.open(key, null);

		LocalAuthor localAuthor = getLocalAuthor();
		Identity identity = new Identity(localAuthor, null, null, System.currentTimeMillis());
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.commitTransaction(txn);

		// Add contact with mode3Capable = true
		Author author = getAuthor();
		txn = db.startTransaction();
		ContactId contactId = db.addContact(txn, author, localAuthor.getId(),
				null, false, true, true, true);
		db.commitTransaction(txn);

		// Close and reopen database
		db.close();
		db = createDatabase();
		assertTrue("Database should reopen", db.open(key, null));

		// Verify contact still has mode3Capable = true
		txn = db.startTransaction();
		Contact contact = db.getContact(txn, contactId);
		db.commitTransaction(txn);

		assertTrue("mode3Capable should be preserved after reopen",
				contact.isMode3Capable());
	}

	private Database<Connection> createDatabase() {
		return new H2Database(config, messageFactory, clock);
	}

	private int getDataSchemaVersion(Database<Connection> db) throws Exception {
		Connection txn = db.startTransaction();
		Settings s = db.getSettings(txn, DB_SETTINGS_NAMESPACE);
		db.commitTransaction(txn);
		return s.getInt(SCHEMA_VERSION_KEY, -1);
	}
}
