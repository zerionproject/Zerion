package org.zerionproject.core.db;

import android.database.Cursor;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

class SqlCipherResultSet implements ResultSet {

	private final Cursor cursor;
	private boolean lastWasNull;

	SqlCipherResultSet(Cursor cursor) {
		this.cursor = cursor;
	}

	@Override
	public boolean next() throws SQLException {
		return cursor.moveToNext();
	}

	@Override
	public int getInt(int columnIndex) throws SQLException {
		int idx = columnIndex - 1;
		if (cursor.isNull(idx)) {
			lastWasNull = true;
			return 0;
		}
		lastWasNull = false;
		return cursor.getInt(idx);
	}

	@Override
	public long getLong(int columnIndex) throws SQLException {
		int idx = columnIndex - 1;
		if (cursor.isNull(idx)) {
			lastWasNull = true;
			return 0;
		}
		lastWasNull = false;
		return cursor.getLong(idx);
	}

	@Override
	public String getString(int columnIndex) throws SQLException {
		int idx = columnIndex - 1;
		if (cursor.isNull(idx)) {
			lastWasNull = true;
			return null;
		}
		lastWasNull = false;
		return cursor.getString(idx);
	}

	@Override
	public byte[] getBytes(int columnIndex) throws SQLException {
		int idx = columnIndex - 1;
		if (cursor.isNull(idx)) {
			lastWasNull = true;
			return null;
		}
		lastWasNull = false;
		return cursor.getBlob(idx);
	}

	@Override
	public boolean getBoolean(int columnIndex) throws SQLException {
		int idx = columnIndex - 1;
		if (cursor.isNull(idx)) {
			lastWasNull = true;
			return false;
		}
		lastWasNull = false;
		return cursor.getInt(idx) != 0;
	}

	@Override
	public boolean wasNull() throws SQLException {
		return lastWasNull;
	}

	@Override
	public void close() throws SQLException {
		cursor.close();
	}

	@Override
	public int getInt(String columnLabel) throws SQLException {
		return getInt(cursor.getColumnIndexOrThrow(columnLabel) + 1);
	}

	@Override
	public long getLong(String columnLabel) throws SQLException {
		return getLong(cursor.getColumnIndexOrThrow(columnLabel) + 1);
	}

	@Override
	public String getString(String columnLabel) throws SQLException {
		return getString(cursor.getColumnIndexOrThrow(columnLabel) + 1);
	}

	@Override
	public byte[] getBytes(String columnLabel) throws SQLException {
		return getBytes(cursor.getColumnIndexOrThrow(columnLabel) + 1);
	}

	@Override
	public boolean getBoolean(String columnLabel) throws SQLException {
		return getBoolean(cursor.getColumnIndexOrThrow(columnLabel) + 1);
	}

	@Override public byte getByte(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public short getShort(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public float getFloat(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public double getDouble(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public BigDecimal getBigDecimal(int i, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Date getDate(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Time getTime(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Timestamp getTimestamp(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getAsciiStream(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getUnicodeStream(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getBinaryStream(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public byte getByte(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public short getShort(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public float getFloat(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public double getDouble(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public BigDecimal getBigDecimal(String s, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Date getDate(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Time getTime(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Timestamp getTimestamp(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getAsciiStream(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getUnicodeStream(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public InputStream getBinaryStream(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public SQLWarning getWarnings() throws SQLException { return null; }
	@Override public void clearWarnings() throws SQLException {}
	@Override public String getCursorName() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public ResultSetMetaData getMetaData() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Object getObject(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Object getObject(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int findColumn(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Reader getCharacterStream(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Reader getCharacterStream(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public BigDecimal getBigDecimal(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public BigDecimal getBigDecimal(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isBeforeFirst() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isAfterLast() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isFirst() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isLast() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void beforeFirst() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void afterLast() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean first() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean last() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean absolute(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean relative(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean previous() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setFetchDirection(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getFetchDirection() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void setFetchSize(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getFetchSize() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
	@Override public int getConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
	@Override public boolean rowUpdated() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean rowInserted() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean rowDeleted() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNull(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBoolean(int i, boolean b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateByte(int i, byte b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateShort(int i, short s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateInt(int i, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateLong(int i, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateFloat(int i, float f) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateDouble(int i, double d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBigDecimal(int i, BigDecimal b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateString(int i, String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBytes(int i, byte[] b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateDate(int i, Date d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateTime(int i, Time t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateTimestamp(int i, Timestamp t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(int i, InputStream in, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(int i, InputStream in, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(int i, Reader r, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateObject(int i, Object o, int j) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateObject(int i, Object o) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNull(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBoolean(String s, boolean b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateByte(String s, byte b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateShort(String s, short sh) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateInt(String s, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateLong(String s, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateFloat(String s, float f) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateDouble(String s, double d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBigDecimal(String s, BigDecimal b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateString(String s, String v) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBytes(String s, byte[] b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateDate(String s, Date d) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateTime(String s, Time t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateTimestamp(String s, Timestamp t) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(String s, InputStream in, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(String s, InputStream in, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(String s, Reader r, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateObject(String s, Object o, int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateObject(String s, Object o) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void insertRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void deleteRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void refreshRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void cancelRowUpdates() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void moveToInsertRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void moveToCurrentRow() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Statement getStatement() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Object getObject(int i, Map<String, Class<?>> m) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Ref getRef(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Blob getBlob(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Clob getClob(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Array getArray(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Object getObject(String s, Map<String, Class<?>> m) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Ref getRef(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Blob getBlob(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Clob getClob(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Array getArray(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Date getDate(int i, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Date getDate(String s, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Time getTime(int i, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Time getTime(String s, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Timestamp getTimestamp(int i, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Timestamp getTimestamp(String s, Calendar c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public URL getURL(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public URL getURL(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateRef(int i, Ref r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateRef(String s, Ref r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(int i, Blob b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(String s, Blob b) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(int i, Clob c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(String s, Clob c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateArray(int i, Array a) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateArray(String s, Array a) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public RowId getRowId(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public RowId getRowId(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateRowId(int i, RowId r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateRowId(String s, RowId r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public int getHoldability() throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isClosed() throws SQLException { return cursor.isClosed(); }
	@Override public void updateNString(int i, String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNString(String s, String v) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(int i, NClob n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(String s, NClob n) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public NClob getNClob(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public NClob getNClob(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public SQLXML getSQLXML(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public SQLXML getSQLXML(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateSQLXML(int i, SQLXML x) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateSQLXML(String s, SQLXML x) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public String getNString(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public String getNString(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Reader getNCharacterStream(int i) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public Reader getNCharacterStream(String s) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNCharacterStream(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNCharacterStream(String s, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(String s, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(String s, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(String s, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(int i, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(String s, InputStream in, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(String s, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(int i, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(String s, Reader r, long l) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNCharacterStream(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNCharacterStream(String s, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateAsciiStream(String s, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBinaryStream(String s, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateCharacterStream(String s, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(int i, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateBlob(String s, InputStream in) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateClob(String s, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(int i, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public void updateNClob(String s, Reader r) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public <T> T unwrap(Class<T> c) throws SQLException { throw new UnsupportedOperationException(); }
	@Override public boolean isWrapperFor(Class<?> c) throws SQLException { return false; }
}
