package com.professor.zerion.android.controller;

import org.briarproject.bramble.api.system.Wakeful;
import com.professor.zerion.android.controller.handler.ResultHandler;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface BriarController extends ActivityLifecycleController {

	void startAndBindService();

	boolean accountSignedIn();

	/**
	 * Returns true via the handler when the app has dozed
	 * without being white-listed.
	 */
	void hasDozed(ResultHandler<Boolean> handler);

	void doNotAskAgainForDozeWhiteListing();

	@Wakeful
	void signOut(ResultHandler<Void> handler, boolean deleteAccount);

	void deleteAccount();

}
