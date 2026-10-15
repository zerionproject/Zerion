package org.zerionproject.core.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class DeletedContentPurgeDeviceTest {

	private static final int MARKER_COPIES = 2048;

	private final File dir = new File(androidx.test.platform.app
			.InstrumentationRegistry.getInstrumentation().getTargetContext()
			.getCacheDir(), "deleted-content-purge-" + System.nanoTime());
	private final File dbFile = new File(dir, "plain.sqlite");
	private final File walFile = new File(dir, "plain.sqlite-wal");
	private final byte[] marker = ("gone" + toHexString(getRandomBytes(6)))
			.getBytes(StandardCharsets.US_ASCII);
	private final byte[] keptMarker = ("kept" + toHexString(getRandomBytes(6)))
			.getBytes(StandardCharsets.US_ASCII);

	@Before
	public void setUp() {
		System.loadLibrary("sqlcipher");
		assertTrue(dir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(dir);
	}

	@Test
	public void deletedContentIsInNeitherFileAfterThePurge() throws Exception {
		Connection c = open();
		Connection idle = open();
		try {
			createMessagesTable(c);
			insert(c, marker, 1);
			insert(c, keptMarker, 2);
			assertTrue("the test must see the content it wrote",
					stored(marker));
			delete(c, marker);

			assertTrue(SqlCipherDatabase.checkpoint(c));

			assertFalse("the deleted content is still in the database file",
					contains(dbFile, marker));
			assertFalse("the deleted content is still in the log",
					contains(walFile, marker));
			assertTrue("the content that was not deleted must be kept",
					contains(dbFile, keptMarker));
		} finally {
			idle.close();
			c.close();
		}
	}

	@Test
	public void withoutThePurgeDeletedContentStaysOnDisk() throws Exception {
		Connection c = open();
		try {
			createMessagesTable(c);
			insert(c, marker, 1);
			delete(c, marker);

			assertTrue("the files must still hold the deleted content when"
					+ " no purge has run, or this test proves nothing",
					stored(marker));
		} finally {
			c.close();
		}
	}

	private Connection open() throws SQLException {
		return SqlCipherDatabase.openWithPassphrase(dbFile, new byte[0]);
	}

	private boolean stored(byte[] content) throws IOException {
		return contains(dbFile, content) || contains(walFile, content);
	}

	private static void createMessagesTable(Connection c)
			throws SQLException {
		try (Statement s = c.createStatement()) {
			s.executeUpdate("CREATE TABLE messages"
					+ " (messageId BLOB NOT NULL,"
					+ " groupId BLOB NOT NULL,"
					+ " timestamp INTEGER NOT NULL,"
					+ " length INTEGER NOT NULL,"
					+ " raw BLOB,"
					+ " PRIMARY KEY (messageId))");
			s.executeUpdate("CREATE INDEX messagesByGroupId"
					+ " ON messages (groupId)");
		}
	}

	private static void insert(Connection c, byte[] content, long timestamp)
			throws SQLException {
		c.setAutoCommit(false);
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO messages"
				+ " (messageId, groupId, timestamp, length, raw)"
				+ " VALUES (?, ?, ?, ?, ?)")) {
			byte[] small = repeat(content, 4);
			byte[] large = repeat(content, MARKER_COPIES);
			ps.setBytes(1, getRandomBytes(32));
			ps.setBytes(2, content);
			ps.setLong(3, timestamp);
			ps.setInt(4, small.length);
			ps.setBytes(5, small);
			ps.executeUpdate();
			ps.setBytes(1, getRandomBytes(32));
			ps.setBytes(2, content);
			ps.setLong(3, timestamp);
			ps.setInt(4, large.length);
			ps.setBytes(5, large);
			ps.executeUpdate();
		}
		c.commit();
		c.setAutoCommit(true);
	}

	private static void delete(Connection c, byte[] content)
			throws SQLException {
		c.setAutoCommit(false);
		try (PreparedStatement ps = c.prepareStatement(
				"DELETE FROM messages WHERE groupId = ?")) {
			ps.setBytes(1, content);
			ps.executeUpdate();
		}
		c.commit();
		c.setAutoCommit(true);
	}

	private static byte[] repeat(byte[] content, int copies) {
		byte[] out = new byte[content.length * copies];
		for (int i = 0; i < copies; i++) {
			System.arraycopy(content, 0, out, i * content.length,
					content.length);
		}
		return out;
	}

	private static boolean contains(File f, byte[] content)
			throws IOException {
		if (!f.exists()) return false;
		byte[] bytes = Files.readAllBytes(f.toPath());
		outer:
		for (int i = 0; i <= bytes.length - content.length; i++) {
			for (int j = 0; j < content.length; j++) {
				if (bytes[i + j] != content[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
