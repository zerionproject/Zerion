package com.professor.zerion.android.controller;

import org.briarproject.nullsafety.NotNullByDefault;

@Deprecated
@NotNullByDefault
public interface DbController {

	void runOnDbThread(Runnable task);
}
