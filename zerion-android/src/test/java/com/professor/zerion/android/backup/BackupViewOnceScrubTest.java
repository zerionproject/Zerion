package com.professor.zerion.android.backup;

import android.app.Application;

import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.transport.RootKeyStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupViewOnceScrubTest {

	private final List<String> sql = new ArrayList<>();

	private DatabaseComponent db() {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "transaction":
						case "transactionWithResult":
						case "transactionWithNullableResult":
							return run(args[1]);
						case "getSettings":
							return new Settings();
						default:
							return null;
					}
				});
	}

	private Object run(Object body) throws Throwable {
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
				Statement.class.getClassLoader(),
				new Class<?>[] {Statement.class},
				(proxy, method, args) -> {
					if (method.getName().equals("execute")) {
						String s = (String) args[0];
						sql.add(s.startsWith("VACUUM INTO") ? "VACUUM INTO" : s);
						if (s.startsWith("VACUUM INTO")) {
							int q = s.indexOf('\'');
							String target = s.substring(q + 1, s.length() - 1)
									.replace("''", "'");
							try (FileOutputStream out =
									new FileOutputStream(target)) {
								out.write(new byte[64]);
							}
						}
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
					if (method.getName().equals("prepareStatement")) {
						return prepared((String) args[0]);
					}
					if (method.getReturnType() == boolean.class) return false;
					return null;
				});
	}

	private PreparedStatement prepared(String text) {
		byte[][] bound = new byte[1][];
		return (PreparedStatement) Proxy.newProxyInstance(
				PreparedStatement.class.getClassLoader(),
				new Class<?>[] {PreparedStatement.class},
				(proxy, method, args) -> {
					if (method.getName().equals("setBytes")) {
						bound[0] = (byte[]) args[1];
						return null;
					}
					if (method.getName().equals("executeUpdate")) {
						sql.add(text + " " + StringUtils.toHexString(bound[0]));
						return 1;
					}
					if (method.getReturnType() == boolean.class) return false;
					if (method.getReturnType() == int.class) return 0;
					return null;
				});
	}

	private void backUp(Collection<MessageId> viewOnce) throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		DatabaseComponent db = db();
		AndroidAccountManager accounts = mock(AndroidAccountManager.class);
		when(accounts.getDatabaseKey()).thenReturn(
				new SecretKey(new byte[SecretKey.LENGTH]));
		ProfileManager profiles = mock(ProfileManager.class);
		when(profiles.getActiveProfileId()).thenReturn("p");
		when(profiles.readDisplayName("p")).thenReturn("Name");
		MessagingManager messaging = mock(MessagingManager.class);
		when(messaging.getUnclearedViewOnceMedia(any())).thenReturn(viewOnce);
		AccountBackupManager manager = new AccountBackupManager(app, db,
				accounts, profiles, mock(IdentityManager.class),
				new RootKeyStore(db), messaging,
				mock(org.zerionproject.core.api.contact.request
						.ContactRequestManager.class));
		manager.copyAccount((bundle, length) -> {
			byte[] buf = new byte[4096];
			while (bundle.read(buf) != -1) {
			}
		});
	}

	@Test
	public void aBackupCarriesNoUnopenedViewOnceMedia() throws Exception {
		MessageId photo = new MessageId(getRandomId());
		MessageId chunk = new MessageId(getRandomId());
		String s = AccountBackupManager.SNAPSHOT_SCHEMA;

		backUp(Arrays.asList(photo, chunk));

		assertEquals("VACUUM INTO", sql.get(0));
		assertTrue(sql.get(1).startsWith("ATTACH DATABASE '"));
		assertTrue(sql.get(1).endsWith("' AS " + s));
		assertEquals("PRAGMA " + s + ".secure_delete = ON", sql.get(2));
		for (MessageId id : Arrays.asList(photo, chunk)) {
			String hex = StringUtils.toHexString(id.getBytes());
			assertTrue("the body stays in the copy", sql.contains("UPDATE "
					+ s + ".messages SET raw = NULL, length = 0"
					+ " WHERE messageId = ? " + hex));
			assertTrue(sql.contains("UPDATE " + s + ".statuses SET deleted"
					+ " = TRUE, length = 0 WHERE messageId = ? " + hex));
		}
		assertEquals("DETACH DATABASE " + s, sql.get(sql.size() - 1));
	}

	@Test
	public void aBackupWithoutViewOnceMediaIsAPlainCopy() throws Exception {
		backUp(Collections.emptyList());

		assertEquals(Collections.singletonList("VACUUM INTO"), sql);
	}
}
