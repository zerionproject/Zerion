package org.zerionproject.core.db;

import android.database.Cursor;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.StringUtils;
import com.professor.zerion.android.testing.Inert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;

public class ContactRemovalRowsDeviceTest {

	private final File testDir = new File(androidx.test.platform.app
			.InstrumentationRegistry.getInstrumentation().getTargetContext()
			.getCacheDir(), "contact-removal-rows-" + System.nanoTime());
	private final SecretKey dbKey = getSecretKey();
	private final byte[] keyCopy = dbKey.getBytes().clone();
	private MessageFactory messageFactory;
	private DatabaseComponent db;

	@Before
	public void setUp() throws Exception {
		CryptoComponent crypto =
				org.zerionproject.core.crypto.CryptoForTests.create();
		Constructor<?> ctor = Class.forName(
				"org.zerionproject.core.sync.MessageFactoryImpl")
				.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		messageFactory = (MessageFactory) ctor.newInstance(crypto);
		db = SqlCipherDatabaseForTests.open(testDir, dbKey,
				Inert.of(EventBus.class), messageFactory);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aRemovedContactLeavesNothingForTheNextContactWithItsId()
			throws Exception {
		Identity identity = getIdentity();
		Group group = getGroup(getClientId(), 1);
		Message m = messageFactory.createMessage(group.getId(),
				System.currentTimeMillis(), getRandomBytes(200));
		ContactId first = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			ContactId c = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			db.addGroup(txn, group);
			db.setGroupVisibility(txn, c, group.getId(), SHARED);
			db.addLocalMessage(txn, m, new Metadata(), true, false);
			return c;
		});
		Collection<MessageId> queued = db.transactionWithResult(false,
				txn -> db.getMessagesToSend(txn, first, Long.MAX_VALUE,
						60_000));
		assertEquals("the message is queued for the first contact",
				Collections.singletonList(m.getId()), new ArrayList<>(queued));

		db.transaction(false, txn -> {
			db.removeGroup(txn, group);
			db.removeContact(txn, first);
		});
		ContactId second = db.transactionWithResult(false, txn ->
				db.addContact(txn, getAuthor(),
						identity.getLocalAuthor().getId(), null, true));
		Collection<MessageId> offered = db.transactionWithResult(false,
				txn -> db.getMessagesToSend(txn, second, Long.MAX_VALUE,
						60_000));
		db.close();
		db = null;

		Object raw = openRaw(SqlCipherDatabaseForTests.databaseFile(testDir)
				.getAbsolutePath(), SqlCipherDatabase.hexPassphrase(keyCopy));
		long foreignKeys;
		long statusRows;
		long messageRows;
		try {
			foreignKeys = count(raw, "PRAGMA foreign_keys");
			statusRows = count(raw,
					"SELECT COUNT(*) FROM statuses WHERE contactId = "
							+ first.getInt());
			messageRows = count(raw,
					"SELECT COUNT(*) FROM messages WHERE groupId = X'"
							+ StringUtils.toHexString(group.getId().getBytes())
							+ "'");
		} finally {
			raw.getClass().getMethod("close").invoke(raw);
		}
		String observed = "first id " + first.getInt() + ", second id "
				+ second.getInt() + ", foreign_keys " + foreignKeys
				+ ", status rows of the removed contact " + statusRows
				+ ", messages of the removed group " + messageRows
				+ ", offered to the second contact " + offered.size();
		assertTrue("the new contact must not be offered the removed"
				+ " contact's queue: " + observed, offered.isEmpty());
		assertEquals("no status row of the removed contact remains: "
				+ observed, 0, statusRows);
		assertEquals("no message of the removed group remains: "
				+ observed, 0, messageRows);
		assertTrue("a removed contact's id is never given to another"
				+ " contact: " + observed, second.getInt() != first.getInt());
	}

	@Test
	public void rowsLeftByAnOlderBuildAreRemovedOnUpgrade() throws Exception {
		Identity identity = getIdentity();
		Group group = getGroup(getClientId(), 1);
		Message m = messageFactory.createMessage(group.getId(),
				System.currentTimeMillis(), getRandomBytes(200));
		ContactId[] ids = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			ContactId kept = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			ContactId gone = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			db.addGroup(txn, group);
			db.setGroupVisibility(txn, kept, group.getId(), SHARED);
			db.setGroupVisibility(txn, gone, group.getId(), SHARED);
			db.addLocalMessage(txn, m, new Metadata(), true, false);
			return new ContactId[] {kept, gone};
		});
		ContactId kept = ids[0];
		ContactId gone = ids[1];
		db.close();
		db = null;

		String path = SqlCipherDatabaseForTests.databaseFile(testDir)
				.getAbsolutePath();
		Object raw = openRaw(path, SqlCipherDatabase.hexPassphrase(keyCopy));
		long leftBefore;
		try {
			execute(raw, "DELETE FROM contacts WHERE contactId = "
					+ gone.getInt());
			execute(raw, "UPDATE settings SET value = '67'"
					+ " WHERE namespace = 'db' AND settingKey = 'schemaVersion'");
			execute(raw, "DELETE FROM settings WHERE namespace = 'db'"
					+ " AND settingKey = 'contactIdHighWater'");
			leftBefore = count(raw, "SELECT COUNT(*) FROM statuses"
					+ " WHERE contactId = " + gone.getInt());
		} finally {
			raw.getClass().getMethod("close").invoke(raw);
		}
		assertEquals("the old build left the removed contact's status", 1,
				leftBefore);

		db = SqlCipherDatabaseForTests.open(testDir, dbKey,
				Inert.of(EventBus.class), messageFactory);
		ContactId next = db.transactionWithResult(false, txn ->
				db.addContact(txn, getAuthor(),
						identity.getLocalAuthor().getId(), null, true));
		db.close();
		db = null;

		raw = openRaw(path, SqlCipherDatabase.hexPassphrase(keyCopy));
		long goneStatuses;
		long goneVisibilities;
		long keptStatuses;
		long violations;
		try {
			goneStatuses = count(raw, "SELECT COUNT(*) FROM statuses"
					+ " WHERE contactId = " + gone.getInt());
			goneVisibilities = count(raw, "SELECT COUNT(*)"
					+ " FROM groupVisibilities WHERE contactId = "
					+ gone.getInt());
			keptStatuses = count(raw, "SELECT COUNT(*) FROM statuses"
					+ " WHERE contactId = " + kept.getInt());
			violations = count(raw,
					"SELECT COUNT(*) FROM pragma_foreign_key_check");
		} finally {
			raw.getClass().getMethod("close").invoke(raw);
		}
		String observed = "removed " + gone.getInt() + " statuses "
				+ goneStatuses + " visibilities " + goneVisibilities
				+ ", kept " + kept.getInt() + " statuses " + keptStatuses
				+ ", foreign key violations " + violations + ", next id "
				+ next.getInt();
		assertEquals(observed, 0, goneStatuses);
		assertEquals(observed, 0, goneVisibilities);
		assertEquals(observed, 1, keptStatuses);
		assertEquals(observed, 0, violations);
		assertTrue(observed, next.getInt() > gone.getInt());
	}

	@Test
	public void everyForeignKeyIsCoveredByAnIndex() throws Exception {
		db.close();
		db = null;
		Object raw = openRaw(SqlCipherDatabaseForTests.databaseFile(testDir)
				.getAbsolutePath(), SqlCipherDatabase.hexPassphrase(keyCopy));
		java.util.List<String> uncovered = new ArrayList<>();
		try {
			for (String[] t : rows(raw, "SELECT name FROM sqlite_master"
					+ " WHERE type = 'table'")) {
				String table = t[0];
				java.util.Map<Integer, java.util.List<String>> fks =
						new java.util.TreeMap<>();
				for (String[] fk : rows(raw,
						"PRAGMA foreign_key_list(" + table + ")")) {
					int id = Integer.parseInt(fk[0]);
					java.util.List<String> cols = fks.get(id);
					if (cols == null) {
						cols = new ArrayList<>();
						fks.put(id, cols);
					}
					cols.add(fk[3]);
				}
				if (fks.isEmpty()) continue;
				java.util.List<java.util.List<String>> indexes =
						new ArrayList<>();
				for (String[] idx : rows(raw,
						"PRAGMA index_list(" + table + ")")) {
					java.util.List<String> cols = new ArrayList<>();
					for (String[] c : rows(raw,
							"PRAGMA index_info('" + idx[1] + "')")) {
						cols.add(c[2]);
					}
					indexes.add(cols);
				}
				for (java.util.List<String> fkCols : fks.values()) {
					boolean covered = false;
					for (java.util.List<String> idxCols : indexes) {
						if (idxCols.size() >= fkCols.size()
								&& idxCols.subList(0, fkCols.size())
								.equals(fkCols)) {
							covered = true;
							break;
						}
					}
					if (!covered) uncovered.add(table + fkCols);
				}
			}
		} finally {
			raw.getClass().getMethod("close").invoke(raw);
		}
		assertTrue("foreign keys without an index: " + uncovered,
				uncovered.isEmpty());
	}

	private static java.util.List<String[]> rows(Object raw, String sql)
			throws Exception {
		Method rawQuery = null;
		for (Method m : raw.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (m.getName().equals("rawQuery") && p.length == 2
					&& p[0] == String.class && p[1].isArray()) {
				rawQuery = m;
				break;
			}
		}
		if (rawQuery == null) throw new AssertionError("no rawQuery");
		Cursor c = (Cursor) rawQuery.invoke(raw, sql, null);
		java.util.List<String[]> out = new ArrayList<>();
		try {
			while (c.moveToNext()) {
				String[] row = new String[c.getColumnCount()];
				for (int i = 0; i < row.length; i++) row[i] = c.getString(i);
				out.add(row);
			}
		} finally {
			c.close();
		}
		return out;
	}

	private static void execute(Object raw, String sql) throws Exception {
		raw.getClass().getMethod("execSQL", String.class).invoke(raw, sql);
	}

	private static Object openRaw(String path, byte[] passphrase)
			throws Exception {
		Class<?> cls = Class.forName(
				"net.zetetic.database.sqlcipher.SQLiteDatabase");
		for (Method m : cls.getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (m.getName().equals("openOrCreateDatabase") && p.length == 5
					&& p[0] == String.class && p[1] == byte[].class) {
				return m.invoke(null, path, passphrase, null, null, null);
			}
		}
		throw new AssertionError("no openOrCreateDatabase(String, byte[], ...)");
	}

	private static long count(Object raw, String sql) throws Exception {
		Method rawQuery = null;
		for (Method m : raw.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (m.getName().equals("rawQuery") && p.length == 2
					&& p[0] == String.class && p[1].isArray()) {
				rawQuery = m;
				break;
			}
		}
		if (rawQuery == null) throw new AssertionError("no rawQuery");
		Cursor c = (Cursor) rawQuery.invoke(raw, sql, null);
		try {
			return c.moveToFirst() ? c.getLong(0) : -1;
		} finally {
			c.close();
		}
	}
}
