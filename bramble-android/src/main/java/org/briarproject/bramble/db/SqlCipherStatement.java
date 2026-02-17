package org.briarproject.bramble.db;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteStatement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * Minimal JDBC Statement wrapping SQLCipher's SQLiteDatabase.
 * Implements the methods used by JdbcDatabase.
 */
class SqlCipherStatement implements Statement {

	private final SQLiteDatabase db;
	private boolean closed = false;

	SqlCipherStatement(SQLiteDatabase db) {
		this.db = db;
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		try {
			db.execSQL(sql);
			return false;
		} catch (android.database.SQLException e) {
			throw new SQLException(e.getMessage(), e);
		}
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		try {
			Cursor cursor = db.rawQuery(sql, null);
			return new SqlCipherResultSet(cursor);
		} catch (android.database.SQLException e) {
			throw new SQLException(e.getMessage(), e);
		}
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		try {
			if (isDdl(sql)) {
				db.execSQL(sql);
				return 0;
			}
			SQLiteStatement stmt = db.compileStatement(sql);
			try {
				return stmt.executeUpdateDelete();
			} finally {
				stmt.close();
			}
		} catch (android.database.SQLException e) {
			throw new SQLException(e.getMessage(), e);
		}
	}

	private static boolean isDdl(String sql) {
		String upper = sql.trim().toUpperCase();
		return upper.startsWith("CREATE ") || upper.startsWith("DROP ")
				|| upper.startsWith("ALTER ");
	}

	@Override
	public void close() throws SQLException {
		closed = true;
	}

	@Override public boolean isClosed() throws SQLException { return closed; }
	@Override public int getMaxFieldSize() throws SQLException { return 0; }
	@Override public void setMaxFieldSize(int i) throws SQLException {}
	@Override public int getMaxRows() throws SQLException { return 0; }
	@Override public void setMaxRows(int i) throws SQLException {}
	@Override public void setEscapeProcessing(boolean b) throws SQLException {}
	@Override public int getQueryTimeout() throws SQLException { return 0; }
	@Override public void setQueryTimeout(int i) throws SQLException {}
	@Override public void cancel() throws SQLException {}
	@Override public SQLWarning getWarnings() throws SQLException { return null; }
	@Override public void clearWarnings() throws SQLException {}
	@Override public void setCursorName(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public ResultSet getResultSet() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getUpdateCount() throws SQLException { return -1; }
	@Override public boolean getMoreResults() throws SQLException { return false; }
	@Override public void setFetchDirection(int i) throws SQLException {}
	@Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
	@Override public void setFetchSize(int i) throws SQLException {}
	@Override public int getFetchSize() throws SQLException { return 0; }
	@Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
	@Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
	@Override public void addBatch(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void clearBatch() throws SQLException {}
	@Override public int[] executeBatch() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Connection getConnection() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean getMoreResults(int i) throws SQLException { return false; }
	@Override public ResultSet getGeneratedKeys() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int executeUpdate(String s, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int executeUpdate(String s, int[] i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int executeUpdate(String s, String[] n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean execute(String s, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean execute(String s, int[] i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean execute(String s, String[] n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getResultSetHoldability() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setPoolable(boolean b) throws SQLException {}
	@Override public boolean isPoolable() throws SQLException { return false; }
	@Override public <T> T unwrap(Class<T> c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isWrapperFor(Class<?> c) throws SQLException { return false; }
}
