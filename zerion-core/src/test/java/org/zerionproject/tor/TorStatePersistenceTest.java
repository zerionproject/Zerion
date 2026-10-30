package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zerionproject.tor.TorStateStore.StoredState;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TorStatePersistenceTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	static final class MemoryStore implements TorStateStore {

		@Nullable
		StoredState stored = null;
		int saves = 0;
		boolean refuse = false;

		@Override
		@Nullable
		public StoredState load() {
			return stored;
		}

		@Override
		public boolean save(StoredState state) {
			saves++;
			if (refuse) return false;
			stored = new StoredState(state.bytes.clone(), state.modifiedMs);
			return true;
		}

		@Override
		public void clear() {
			stored = null;
		}
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private File dataDir() throws Exception {
		return tmp.newFolder(".tor");
	}

	private static File write(File dir, String name, byte[] content)
			throws Exception {
		File f = new File(dir, name);
		Files.write(f.toPath(), content);
		return f;
	}

	@Test
	public void restoreWritesTheStoredStateBeforeTorStarts()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.stored = new StoredState(bytes("Guard in=default\n"), 1000L);
		new TorStatePersistence(dir, store).restore();
		File state = new File(dir, "state");
		assertTrue(state.isFile());
		assertArrayEquals(bytes("Guard in=default\n"),
				Files.readAllBytes(state.toPath()));
		assertEquals(1, dir.list().length);
	}

	@Test
	public void restoreWithNothingStoredLeavesNoStateFile()
			throws Exception {
		File dir = dataDir();
		new TorStatePersistence(dir, new MemoryStore()).restore();
		assertFalse(new File(dir, "state").exists());
	}

	@Test
	public void persistStoresTheStateAndShredsEverything()
			throws Exception {
		File dir = dataDir();
		File state = write(dir, "state", bytes("Guard after run\n"));
		write(dir, "cached-microdesc-consensus", bytes("consensus"));
		write(dir, "cached-microdescs.new", bytes("descs"));
		write(dir, "lock", new byte[0]);
		File pt = new File(dir, "pt_state");
		assertTrue(pt.mkdir());
		write(pt, "obfs4_state.json", bytes("{}"));
		MemoryStore store = new MemoryStore();
		new TorStatePersistence(dir, store).persist();
		assertNotNull(store.stored);
		assertArrayEquals(bytes("Guard after run\n"), store.stored.bytes);
		assertTrue(store.stored.modifiedMs > 0);
		assertFalse(state.exists());
		assertFalse(pt.exists());
		assertTrue(dir.isDirectory());
		assertEquals(Arrays.asList("cached-microdesc-consensus",
				"cached-microdescs.new", "lock"), sorted(dir.list()));
	}

	@Test
	public void theLockFileSurvivesRestoreAndPersist() throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.stored = new StoredState(bytes("guards\n"), 1000L);
		File lock = write(dir, "lock", new byte[0]);
		write(dir, "control_auth_cookie", bytes("cookie"));
		new TorStatePersistence(dir, store).restore();
		assertTrue(lock.exists());
		assertFalse(new File(dir, "control_auth_cookie").exists());
		assertEquals(Arrays.asList("lock", "state"), sorted(dir.list()));
		new TorStatePersistence(dir, store).persist();
		assertTrue(lock.exists());
		assertEquals(Arrays.asList("lock"), sorted(dir.list()));
	}

	@Test
	public void aStoreThatRefusesTheImportKeepsThePlaintextState()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.refuse = true;
		File state = write(dir, "state", bytes("guards on disk\n"));
		write(dir, "control_auth_cookie", bytes("cookie"));
		File pt = new File(dir, "pt_state");
		assertTrue(pt.mkdir());
		write(pt, "obfs4_state.json", bytes("{}"));
		new TorStatePersistence(dir, store).restore();
		assertEquals(1, store.saves);
		assertNull(store.stored);
		assertTrue("the only copy of the guards stays", state.exists());
		assertArrayEquals(bytes("guards on disk\n"),
				Files.readAllBytes(state.toPath()));
		assertFalse(pt.exists());
		assertEquals(Arrays.asList("state"), sorted(dir.list()));
	}

	@Test
	public void aPlaintextStateLeftByAKilledProcessIsImportedWhenNewer()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.stored = new StoredState(bytes("old guards\n"), 1000L);
		File state = write(dir, "state", bytes("new guards\n"));
		assertTrue(state.setLastModified(5000L));
		write(dir, "cached-certs", bytes("certs"));
		new TorStatePersistence(dir, store).restore();
		assertArrayEquals(bytes("new guards\n"), store.stored.bytes);
		assertEquals(5000L, store.stored.modifiedMs);
		assertArrayEquals(bytes("new guards\n"),
				Files.readAllBytes(state.toPath()));
		assertTrue(new File(dir, "cached-certs").exists());
		assertEquals(Arrays.asList("cached-certs", "state"),
				sorted(dir.list()));
	}

	@Test
	public void anOlderPlaintextStateLosesAgainstTheStoredCopy()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.stored = new StoredState(bytes("stored guards\n"), 9000L);
		File state = write(dir, "state", bytes("stale guards\n"));
		assertTrue(state.setLastModified(2000L));
		new TorStatePersistence(dir, store).restore();
		assertEquals(0, store.saves);
		assertArrayEquals(bytes("stored guards\n"),
				Files.readAllBytes(state.toPath()));
	}

	@Test
	public void aPlaintextStateWithNothingStoredIsImported()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		write(dir, "state", bytes("only on disk\n"));
		new TorStatePersistence(dir, store).restore();
		assertNotNull(store.stored);
		assertArrayEquals(bytes("only on disk\n"), store.stored.bytes);
		assertArrayEquals(bytes("only on disk\n"),
				Files.readAllBytes(new File(dir, "state").toPath()));
	}

	@Test
	public void anOversizeStateIsDroppedNotStored() throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		byte[] big = new byte[TorStatePersistence.MAX_STATE_BYTES + 1];
		Arrays.fill(big, (byte) 'x');
		File state = write(dir, "state", big);
		new TorStatePersistence(dir, store).persist();
		assertEquals(0, store.saves);
		assertNull(store.stored);
		assertFalse(state.exists());
		write(dir, "state", big);
		new TorStatePersistence(dir, store).restore();
		assertEquals(0, store.saves);
		assertFalse(new File(dir, "state").exists());
	}

	@Test
	public void aStoreThatRefusesStillLeavesNoPlaintextBehind()
			throws Exception {
		File dir = dataDir();
		MemoryStore store = new MemoryStore();
		store.refuse = true;
		File state = write(dir, "state", bytes("guards\n"));
		new TorStatePersistence(dir, store).persist();
		assertEquals(1, store.saves);
		assertFalse(state.exists());
	}

	@Test
	public void shredOverwritesBeforeDeleting() throws Exception {
		File dir = dataDir();
		File f = write(dir, "cached-microdescs", bytes("secret"));
		TorStatePersistence.shred(f);
		assertFalse(f.exists());
	}

	private static java.util.List<String> sorted(String[] names) {
		java.util.List<String> list = new java.util.ArrayList<>(
				Arrays.asList(names));
		java.util.Collections.sort(list);
		return list;
	}
}
