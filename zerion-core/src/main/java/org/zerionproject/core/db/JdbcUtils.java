package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.annotation.Nullable;

@NotNullByDefault
class JdbcUtils {

	static void tryToClose(@Nullable ResultSet rs) {
		try {
			if (rs != null) rs.close();
		} catch (SQLException e) {
		}
	}

	static void tryToClose(@Nullable Statement s) {
		try {
			if (s != null) s.close();
		} catch (SQLException e) {
		}
	}

	static void tryToClose(@Nullable Connection c) {
		try {
			if (c != null) c.close();
		} catch (SQLException e) {
		}
	}
}
