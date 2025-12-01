package com.professor.zerion.android.util;

import androidx.annotation.UiThread;

public interface VersionedAdapter {

	int getRevision();

	@UiThread
	void incrementRevision();

}
