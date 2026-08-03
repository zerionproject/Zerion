package org.zerionproject.core.db;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;

class SqlCipherConnection implements Connection {

	private final SQLiteDatabase db;
	private boolean inTransaction = false;
	private boolean closed = false;

	SqlCipherConnection(SQLiteDatabase db) {
		this.db = db;
	}

	SQLiteDatabase getDatabase() {
		return db;
	}

	@Override
	public void setAutoCommit(boolean autoCommit) throws SQLException {
		if (!autoCommit && !inTransaction) {
			db.beginTransactionNonExclusive();
			inTransaction = true;
		} else if (autoCommit && inTransaction) {
			db.setTransactionSuccessful();
			db.endTransaction();
			inTransaction = false;
		}
	}

	@Override
	public boolean getAutoCommit() throws SQLException {
		return !inTransaction;
	}

	@Override
	public void commit() throws SQLException {
		if (inTransaction) {
			db.setTransactionSuccessful();
			db.endTransaction();
			inTransaction = false;
		}
	}

	@Override
	public void rollback() throws SQLException {
		if (inTransaction) {
			db.endTransaction();
			inTransaction = false;
		}
	}

	@Override
	public Statement createStatement() throws SQLException {
		return new SqlCipherStatement(db);
	}

	@Override
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		return new SqlCipherPreparedStatement(db, sql);
	}

	@Override
	public void close() throws SQLException {
		if (!closed) {
			if (inTransaction) {
				try {
					db.endTransaction();
				} catch (Exception ignored) {}
				inTransaction = false;
			}
			db.close();
			closed = true;
		}
	}

	@Override
	public boolean isClosed() throws SQLException {
		return closed;
	}

	@Override public DatabaseMetaData getMetaData() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setReadOnly(boolean b) throws SQLException {}
	@Override public boolean isReadOnly() throws SQLException { return false; }
	@Override public void setCatalog(String s) throws SQLException {}
	@Override public String getCatalog() throws SQLException { return null; }
	@Override public void setTransactionIsolation(int i) throws SQLException {}
	@Override public int getTransactionIsolation() throws SQLException { return Connection.TRANSACTION_SERIALIZABLE; }
	@Override public SQLWarning getWarnings() throws SQLException { return null; }
	@Override public void clearWarnings() throws SQLException {}
	@Override public Statement createStatement(int t, int c) throws SQLException { return createStatement(); }
	@Override public PreparedStatement prepareStatement(String s, int t, int c) throws SQLException { return prepareStatement(s); }
	@Override public CallableStatement prepareCall(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public String nativeSQL(String s) throws SQLException { return s; }
	@Override public CallableStatement prepareCall(String s, int t, int c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Map<String, Class<?>> getTypeMap() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setTypeMap(Map<String, Class<?>> m) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setHoldability(int h) throws SQLException {}
	@Override public int getHoldability() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Savepoint setSavepoint() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Savepoint setSavepoint(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void rollback(Savepoint s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void releaseSavepoint(Savepoint s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Statement createStatement(int t, int c, int h) throws SQLException { return createStatement(); }
	@Override public PreparedStatement prepareStatement(String s, int t, int c, int h) throws SQLException { return prepareStatement(s); }
	@Override public CallableStatement prepareCall(String s, int t, int c, int h) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public PreparedStatement prepareStatement(String s, int k) throws SQLException { return prepareStatement(s); }
	@Override public PreparedStatement prepareStatement(String s, int[] i) throws SQLException { return prepareStatement(s); }
	@Override public PreparedStatement prepareStatement(String s, String[] n) throws SQLException { return prepareStatement(s); }
	@Override public Clob createClob() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Blob createBlob() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public NClob createNClob() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public SQLXML createSQLXML() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isValid(int t) throws SQLException { return !closed; }
	@Override public void setClientInfo(String k, String v) throws SQLClientInfoException {}
	@Override public void setClientInfo(Properties p) throws SQLClientInfoException {}
	@Override public String getClientInfo(String s) throws SQLException { return null; }
	@Override public Properties getClientInfo() throws SQLException { return new Properties(); }
	@Override public Array createArrayOf(String t, Object[] e) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Struct createStruct(String t, Object[] a) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public <T> T unwrap(Class<T> c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isWrapperFor(Class<?> c) throws SQLException { return false; }
}
