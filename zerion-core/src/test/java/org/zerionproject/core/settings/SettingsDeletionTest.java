package org.zerionproject.core.settings;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class SettingsDeletionTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private DatabaseComponent db;
	private SettingsManager settings;

	@Before
	public void setUp() throws Exception {
		assertTrue(testDir.mkdirs());
		db = HyperSqlDatabaseForTests.open(testDir, key, new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
			}
		}, new TestMessageFactory());
		settings = SettingsManagerOverDatabase.create(db);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void deletedKeysLeaveNoRowBehind() throws Exception {
		Settings s = new Settings();
		s.put("a", "1");
		s.put("b", "2");
		s.put("c", "3");
		settings.mergeSettings(s, "ns");
		settings.deleteSettings("ns", Arrays.asList("a", "c", "absent"));
		Settings left = settings.getSettings("ns");
		assertEquals(1, left.size());
		assertEquals("2", left.get("b"));
		assertFalse(left.containsKey("a"));
	}

	@Test
	public void deletedNamespacesLeaveNoRowBehind() throws Exception {
		Map<String, Settings> batch = new LinkedHashMap<>();
		for (int i = 0; i < 50; i++) {
			Settings s = new Settings();
			s.put("p", "value " + i);
			batch.put("posts:" + i, s);
		}
		Settings other = new Settings();
		other.put("x", "kept");
		batch.put("other", other);
		settings.mergeSettings(batch);
		assertEquals("value 7", settings.getSettings("posts:7").get("p"));
		settings.deleteNamespaces(batch.keySet().stream()
				.filter(n -> n.startsWith("posts:"))
				.collect(java.util.stream.Collectors.toList()));
		for (int i = 0; i < 50; i++) {
			assertTrue(settings.getSettings("posts:" + i).isEmpty());
		}
		assertEquals("kept", settings.getSettings("other").get("x"));
		settings.deleteNamespaces(Collections.singletonList("absent"));
	}
}
