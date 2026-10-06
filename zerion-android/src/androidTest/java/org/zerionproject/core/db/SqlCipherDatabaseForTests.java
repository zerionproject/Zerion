package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.ShutdownManager;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.system.SystemClock;
import com.professor.zerion.android.testing.Inert;

import java.io.File;
import java.sql.Connection;

import javax.annotation.Nullable;

public final class SqlCipherDatabaseForTests {

	private SqlCipherDatabaseForTests() {
	}

	public static DatabaseComponent open(File root, SecretKey key)
			throws DbException {
		return open(root, key, Inert.of(EventBus.class),
				Inert.of(MessageFactory.class));
	}

	public static DatabaseComponent open(File root, SecretKey key,
			EventBus eventBus, MessageFactory messageFactory)
			throws DbException {
		DatabaseConfig config = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(root, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(root, "key");
			}

			@Nullable
			@Override
			public KeyStrengthener getKeyStrengthener() {
				return null;
			}
		};
		SqlCipherDatabase db = new SqlCipherDatabase(config,
				messageFactory, new SystemClock());
		DatabaseComponentImpl<Connection> component =
				new DatabaseComponentImpl<>(db, Connection.class,
						eventBus, Runnable::run,
						Inert.of(ShutdownManager.class));
		component.open(key, null);
		return component;
	}

	public static File databaseFile(File root) {
		return new File(new File(root, "db"), "db.sqlite");
	}
}
