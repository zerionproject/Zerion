package org.briarproject.bramble.db;

/**
 * Dummy class loaded by Class.forName() in JdbcDatabase.open().
 * SQLCipher doesn't use a JDBC driver, but JdbcDatabase.open() requires
 * a loadable driver class name. This class exists solely to satisfy that call.
 */
class SqlCipherDriver {
}
