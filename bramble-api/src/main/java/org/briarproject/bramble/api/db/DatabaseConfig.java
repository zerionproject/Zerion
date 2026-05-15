package org.briarproject.bramble.api.db;

import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

import javax.annotation.Nullable;

@NotNullByDefault
public interface DatabaseConfig {

	File getDatabaseDirectory();

	File getDatabaseKeyDirectory();

	@Nullable
	KeyStrengthener getKeyStrengthener();
}
