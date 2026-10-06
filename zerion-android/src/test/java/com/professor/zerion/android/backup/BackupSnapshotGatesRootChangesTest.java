package com.professor.zerion.android.backup;

import android.app.Application;

import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.transport.RootKeyStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupSnapshotGatesRootChangesTest {

	private final ContactId contact = new ContactId(3);
	private final List<String> writes = new ArrayList<>();
	private final byte[] root = new byte[SecretKey.LENGTH];
	private final AtomicReference<Boolean> storedDuringSnapshot =
			new AtomicReference<>();
	private final AtomicBoolean pauseWritten = new AtomicBoolean(false);
	private RootKeyStore store;

	private DatabaseComponent db() {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					switch (name) {
						case "transaction":
						case "transactionWithResult":
						case "transactionWithNullableResult":
							return runInTransaction(args[1]);
						case "getPcsMode2SessionState":
							int slot = (Integer) args[2];
							if (slot != DatabaseComponent.PCS_DIRECTION_SEND) {
								return null;
							}
							return new Object[] {root, 0, 0, root, null, null,
									null, true, null};
						case "setPcsMode2SessionState":
						case "removePcsSessionState":
							writes.add(name);
							return null;
						case "mergeSettings":
							Settings s = (Settings) args[1];
							if (s.containsKey(RootKeyStore.KEY_PAUSED_UNTIL)) {
								pauseWritten.set(true);
							}
							return null;
						case "getSettings":
							return new Settings();
						default:
							return null;
					}
				});
	}

	private Object runInTransaction(Object body) throws Throwable {
		Transaction txn = new Transaction(connection(), false);
		for (Method m : body.getClass().getMethods()) {
			if (m.getName().equals("run") || m.getName().equals("call")) {
				m.setAccessible(true);
				try {
					return m.invoke(body, txn);
				} catch (java.lang.reflect.InvocationTargetException e) {
					throw e.getCause();
				}
			}
		}
		throw new AssertionError();
	}

	private Connection connection() {
		Statement statement = (Statement) Proxy.newProxyInstance(
				Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
				(proxy, method, args) -> {
					if (method.getName().equals("execute")) {
						String sql = (String) args[0];
						int q = sql.indexOf('\'');
						String target = sql.substring(q + 1, sql.length() - 1)
								.replace("''", "'");
						try (FileOutputStream out = new FileOutputStream(target)) {
							out.write(new byte[64]);
						}
						int before = writes.size();
						boolean stored = store.storePending(contact, 0,
								new SecretKey(new byte[SecretKey.LENGTH]), false);
						storedDuringSnapshot.set(stored
								|| writes.size() > before);
						return true;
					}
					if (method.getReturnType() == boolean.class) return false;
					return null;
				});
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] {Connection.class},
				(proxy, method, args) -> {
					if (method.getName().equals("createStatement")) {
						return statement;
					}
					if (method.getReturnType() == boolean.class) return false;
					return null;
				});
	}

	@Test
	public void aRootChangeDuringTheSnapshotIsRefusedAndThePauseFollows()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		DatabaseComponent db = db();
		store = new RootKeyStore(db);
		AndroidAccountManager accounts = mock(AndroidAccountManager.class);
		when(accounts.getDatabaseKey()).thenReturn(
				new SecretKey(new byte[SecretKey.LENGTH]));
		ProfileManager profiles = mock(ProfileManager.class);
		when(profiles.getActiveProfileId()).thenReturn("p");
		when(profiles.readDisplayName("p")).thenReturn("Name");
		IdentityManager identities = mock(IdentityManager.class);
		AccountBackupManager manager = new AccountBackupManager(app, db,
				accounts, profiles, identities, store);

		byte[] bundle = manager.snapshotBundle();

		assertTrue(bundle.length > 0);
		assertEquals("a root change while the copy was written was refused",
				Boolean.FALSE, storedDuringSnapshot.get());
		assertTrue("the pause was written", pauseWritten.get());
		assertFalse("the gate is lifted afterwards", store.isSnapshotting());
		File[] left = app.getCacheDir().listFiles(
				(dir, n) -> n.startsWith("zbk-"));
		assertTrue(left == null || left.length == 0);
	}
}
