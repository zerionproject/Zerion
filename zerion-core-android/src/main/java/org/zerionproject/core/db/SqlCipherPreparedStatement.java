package org.zerionproject.core.db;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteCursor;
import net.zetetic.database.sqlcipher.SQLiteCursorDriver;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteProgram;
import net.zetetic.database.sqlcipher.SQLiteQuery;
import net.zetetic.database.sqlcipher.SQLiteStatement;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

class SqlCipherPreparedStatement implements PreparedStatement {

	private final SQLiteDatabase db;
	private final String sql;
	private final List<Object> bindings = new ArrayList<>();
	private final List<List<Object>> batchedBindings = new ArrayList<>();
	private boolean closed = false;

	SqlCipherPreparedStatement(SQLiteDatabase db, String sql) {
		this.db = db;
		this.sql = sql;
	}

	private void ensureBindingSlot(int parameterIndex) {
		while (bindings.size() < parameterIndex) {
			bindings.add(null);
		}
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, (long) x);
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, x);
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, x);
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, x);
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, x ? 1L : 0L);
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		ensureBindingSlot(parameterIndex);
		bindings.set(parameterIndex - 1, null);
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		try {
			Cursor cursor = db.rawQueryWithFactory(
					new SQLiteDatabase.CursorFactory() {
						@Override
						public Cursor newCursor(SQLiteDatabase db,
								SQLiteCursorDriver driver, String editTable,
								SQLiteQuery query) {
							bindAllToProgram(query);
							return new SQLiteCursor(driver, editTable, query);
						}
					}, sql, null, null);
			return new SqlCipherResultSet(cursor);
		} catch (android.database.SQLException e) {
			throw new SQLException(e.getMessage(), e);
		}
	}

	@Override
	public int executeUpdate() throws SQLException {
		try {
			SQLiteStatement stmt = db.compileStatement(sql);
			try {
				bindAll(stmt);
				if (isInsert(sql)) {
					long rowId = stmt.executeInsert();
					return rowId >= 0 ? 1 : 0;
				} else {
					return stmt.executeUpdateDelete();
				}
			} finally {
				stmt.close();
			}
		} catch (android.database.SQLException e) {
			throw new SQLException(e.getMessage(), e);
		}
	}

	private void bindAll(SQLiteStatement stmt) {
		for (int i = 0; i < bindings.size(); i++) {
			Object val = bindings.get(i);
			int idx = i + 1;
			if (val == null) {
				stmt.bindNull(idx);
			} else if (val instanceof Long) {
				stmt.bindLong(idx, (Long) val);
			} else if (val instanceof String) {
				stmt.bindString(idx, (String) val);
			} else if (val instanceof byte[]) {
				stmt.bindBlob(idx, (byte[]) val);
			} else if (val instanceof Double) {
				stmt.bindDouble(idx, (Double) val);
			} else {
				stmt.bindString(idx, val.toString());
			}
		}
	}

	private void bindAllToProgram(SQLiteProgram program) {
		for (int i = 0; i < bindings.size(); i++) {
			Object val = bindings.get(i);
			int idx = i + 1;
			if (val == null) {
				program.bindNull(idx);
			} else if (val instanceof Long) {
				program.bindLong(idx, (Long) val);
			} else if (val instanceof String) {
				program.bindString(idx, (String) val);
			} else if (val instanceof byte[]) {
				program.bindBlob(idx, (byte[]) val);
			} else if (val instanceof Double) {
				program.bindDouble(idx, (Double) val);
			} else {
				program.bindString(idx, val.toString());
			}
		}
	}

	private static boolean isInsert(String sql) {
		String trimmed = sql.trim().toUpperCase();
		return trimmed.startsWith("INSERT");
	}

	@Override
	public void close() throws SQLException {
		closed = true;
		bindings.clear();
	}

	@Override
	public boolean isClosed() throws SQLException {
		return closed;
	}

	@Override public void setByte(int i, byte b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setShort(int i, short s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setFloat(int i, float f) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setDouble(int i, double d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBigDecimal(int i, BigDecimal b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setDate(int i, Date d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setTime(int i, Time t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setTimestamp(int i, Timestamp t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setAsciiStream(int i, InputStream in, int l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setUnicodeStream(int i, InputStream in, int l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBinaryStream(int i, InputStream in, int l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void clearParameters() throws SQLException { bindings.clear(); }
	@Override public void setObject(int i, Object o, int t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setObject(int i, Object o) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean execute() throws SQLException { throw new UnsupportedOperationException(); }
	@Override
	public void addBatch() throws SQLException {
		batchedBindings.add(new ArrayList<>(bindings));
	}
	@Override public void setCharacterStream(int i, Reader r, int l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setRef(int i, Ref r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBlob(int i, Blob b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setClob(int i, Clob c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setArray(int i, Array a) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public ResultSetMetaData getMetaData() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setDate(int i, Date d, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setTime(int i, Time t, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setTimestamp(int i, Timestamp t, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNull(int i, int t, String n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setURL(int i, URL u) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public ParameterMetaData getParameterMetaData() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setRowId(int i, RowId r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNString(int i, String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNCharacterStream(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNClob(int i, NClob n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setClob(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBlob(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNClob(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setSQLXML(int i, SQLXML x) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setObject(int i, Object o, int t, int s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setAsciiStream(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBinaryStream(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setCharacterStream(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setAsciiStream(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBinaryStream(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setCharacterStream(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNCharacterStream(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setClob(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setBlob(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setNClob(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public ResultSet executeQuery(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int executeUpdate(String s) throws SQLException { throw new UnsupportedOperationException(); }
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
	@Override public boolean execute(String s) throws SQLException { throw new UnsupportedOperationException(); }
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
	@Override
	public void clearBatch() throws SQLException {
		batchedBindings.clear();
	}

	@Override
	public int[] executeBatch() throws SQLException {
		int[] results = new int[batchedBindings.size()];
		for (int i = 0; i < batchedBindings.size(); i++) {
			bindings.clear();
			bindings.addAll(batchedBindings.get(i));
			results[i] = executeUpdate();
		}
		batchedBindings.clear();
		return results;
	}
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
