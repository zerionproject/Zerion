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

import java.io.File;
import java.lang.reflect.Proxy;
import java.sql.Connection;

import javax.annotation.Nullable;

public final class HyperSqlDatabaseForTests {

	private HyperSqlDatabaseForTests() {
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
		HyperSqlDatabase db = new HyperSqlDatabase(config, messageFactory,
				new SystemClock());
		ShutdownManager shutdown = (ShutdownManager) Proxy.newProxyInstance(
				ShutdownManager.class.getClassLoader(),
				new Class<?>[] {ShutdownManager.class},
				(proxy, method, args) -> method.getReturnType() == int.class
						? 0 : method.getReturnType() == boolean.class
						? Boolean.TRUE : null);
		DatabaseComponentImpl<Connection> component =
				new DatabaseComponentImpl<>(db, Connection.class, eventBus,
						Runnable::run, shutdown);
		component.open(key, null);
		return component;
	}
}
