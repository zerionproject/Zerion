package com.professor.zerion.android.settings;

import org.junit.Test;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccountPasswordGatePolicyTest {

	private static final char[] DURESS = "duress password 8&Gh".toCharArray();

	private static AccountManager refusing() throws Exception {
		AccountManager am = mock(AccountManager.class);
		doThrow(new DecryptionException(DecryptionResult.INVALID_PASSWORD))
				.when(am).verifyPassword(any());
		return am;
	}

	private static AccountPasswordGate.Outcome gate(AccountManager am,
			char[] typed) {
		return AccountPasswordGate.verify(am,
				t -> Arrays.equals(t, DURESS), typed.clone()).outcome;
	}

	@Test
	public void theDuressPasswordAtAGateErases() throws Exception {
		assertEquals(AccountPasswordGate.Outcome.ERASE,
				gate(refusing(), DURESS));
	}

	@Test
	public void theDuressPasswordDuringALockoutErases() throws Exception {
		AccountManager am = refusing();
		when(am.signInLockoutRemainingMs()).thenReturn(300_000L);
		assertEquals("duress ERASE, other LOCKED",
				"duress " + gate(am, DURESS) + ", other "
						+ gate(am, "something else".toCharArray()));
	}

	@Test
	public void aWrongPasswordThatReachesTheEraseLimitErases()
			throws Exception {
		AccountManager am = refusing();
		when(am.isEraseRequested()).thenReturn(false, true);
		assertEquals(AccountPasswordGate.Outcome.ERASE,
				gate(am, "a wrong password".toCharArray()));
	}
}
