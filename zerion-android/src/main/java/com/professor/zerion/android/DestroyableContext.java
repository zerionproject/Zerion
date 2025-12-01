package com.professor.zerion.android;

public interface DestroyableContext {

	void runOnUiThreadUnlessDestroyed(Runnable runnable);
}
