package com.professor.zerion.android.security;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
final class UsbPanicResponder implements AntiForensics.PanicAction {

	static final long STOP_TIMEOUT_MS = 20_000L;

	interface Steps {

		boolean signedIn();

		void abortCopies();

		void clearMemory();

		void shredKey();

		boolean stopServices(long timeoutMs);

		void sweepFiles();

		void wipeAccount();

		boolean accountGone();

		void exit();
	}

	private final boolean wipe;
	private final Steps steps;

	UsbPanicResponder(boolean wipe, Steps steps) {
		this.wipe = wipe;
		this.steps = steps;
	}

	boolean wipes() {
		return wipe;
	}

	@Override
	public boolean run() {
		boolean signedIn = steps.signedIn();
		if (!signedIn && !wipe) return true;
		steps.abortCopies();
		steps.clearMemory();
		if (wipe) steps.shredKey();
		boolean stopped = steps.stopServices(STOP_TIMEOUT_MS);
		steps.sweepFiles();
		if (wipe) steps.wipeAccount();
		boolean done = stopped && (!wipe || steps.accountGone());
		if (done) steps.exit();
		return done;
	}

	@Override
	public void giveUp() {
		if (wipe || steps.signedIn()) steps.exit();
	}
}
