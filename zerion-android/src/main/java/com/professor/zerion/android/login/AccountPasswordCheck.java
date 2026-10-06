package com.professor.zerion.android.login;

import android.content.Context;

import com.professor.zerion.android.panic.WipePasswordManager;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;

import java.util.Arrays;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_FILES_DAMAGED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_FAILED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_UNCERTAIN;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;

@NotNullByDefault
public final class AccountPasswordCheck {

	public enum Outcome {
		GRANTED,
		WRONG,
		LOCKED,
		DAMAGED,
		UNAVAILABLE,
		FAILED,
		ERASE
	}

	public interface Attempt {
		void run(char[] typed) throws DecryptionException;
	}

	public interface DuressCheck {
		boolean matches(char[] typed);
	}

	public static final class Result {

		public final Outcome outcome;
		@Nullable
		public final DecryptionResult result;
		public final long lockedMs;

		Result(Outcome outcome, @Nullable DecryptionResult result,
				long lockedMs) {
			this.outcome = outcome;
			this.result = result;
			this.lockedMs = lockedMs;
		}
	}

	private static final Result ERASE =
			new Result(Outcome.ERASE, null, 0);

	private final AccountManager accountManager;
	private final DuressCheck duress;

	public AccountPasswordCheck(AccountManager accountManager,
			DuressCheck duress) {
		this.accountManager = accountManager;
		this.duress = duress;
	}

	public static DuressCheck duressPasswordOf(Context context) {
		Context app = context.getApplicationContext();
		return typed -> {
			try {
				WipePasswordManager wpm = WipePasswordManager.getInstance(app);
				return wpm != null && wpm.isWipePasswordEnabled()
						&& wpm.verifyWipePassword(typed);
			} catch (RuntimeException e) {
				return false;
			}
		};
	}

	public Result check(char[] typed, Attempt attempt) {
		try {
			Result before = beforeAttempt(typed);
			if (before != null) return before;
			try {
				attempt.run(typed);
				return new Result(Outcome.GRANTED, SUCCESS, 0);
			} catch (DecryptionException e) {
				return afterRefusal(typed, e.getDecryptionResult());
			} catch (RuntimeException e) {
				if (accountManager.isEraseRequested()) return ERASE;
				return new Result(Outcome.FAILED, null, 0);
			}
		} finally {
			Arrays.fill(typed, '\0');
		}
	}

	@Nullable
	public Result beforeAttempt(char[] typed) {
		if (accountManager.isEraseRequested()) return ERASE;
		long locked = accountManager.signInLockoutRemainingMs();
		if (locked > 0) {
			if (duress.matches(typed)) return ERASE;
			return new Result(Outcome.LOCKED, null, locked);
		}
		return null;
	}

	public Result afterRefusal(char[] typed, DecryptionResult result) {
		if (accountManager.isEraseRequested()) return ERASE;
		if (result == KEY_REPLACEMENT_FAILED
				|| result == KEY_REPLACEMENT_UNCERTAIN) {
			return new Result(Outcome.FAILED, result, 0);
		}
		if (duress.matches(typed)) return ERASE;
		if (result == KEY_STRENGTHENER_ERROR) {
			return new Result(Outcome.UNAVAILABLE, result, 0);
		}
		if (result == KEY_FILES_DAMAGED) {
			return new Result(Outcome.DAMAGED, result, 0);
		}
		return new Result(Outcome.WRONG, result,
				accountManager.signInLockoutRemainingMs());
	}
}
