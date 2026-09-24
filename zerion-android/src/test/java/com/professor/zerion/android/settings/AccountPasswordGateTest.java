package com.professor.zerion.android.settings;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2-AND-04: account-protecting settings ask for the account password
 * through the sign-in throttle; a lockout refuses without a key derivation
 * and the password is wiped whatever happens.
 */
public class AccountPasswordGateTest {

	@Test
	public void aLockoutRefusesWithoutTouchingTheKey() throws Exception {
		AccountManager am = mock(AccountManager.class);
		when(am.signInLockoutRemainingMs()).thenReturn(90_000L);
		char[] pw = "secret".toCharArray();
		AccountPasswordGate.Result r = AccountPasswordGate.verify(am, pw);
		assertEquals(AccountPasswordGate.Outcome.LOCKED, r.outcome);
		assertEquals(90_000L, r.lockedMs);
		verify(am, never()).signIn(any());
		assertWiped(pw);
	}

	@Test
	public void aWrongPasswordIsRefusedAndWiped() throws Exception {
		AccountManager am = mock(AccountManager.class);
		doThrow(new DecryptionException(DecryptionResult.INVALID_PASSWORD))
				.when(am).signIn(any());
		char[] pw = "secret".toCharArray();
		assertEquals(AccountPasswordGate.Outcome.WRONG,
				AccountPasswordGate.verify(am, pw).outcome);
		assertWiped(pw);
	}

	@Test
	public void theRightPasswordIsGrantedAndWiped() throws Exception {
		AccountManager am = mock(AccountManager.class);
		char[] pw = "secret".toCharArray();
		assertEquals(AccountPasswordGate.Outcome.GRANTED,
				AccountPasswordGate.verify(am, pw).outcome);
		verify(am).signIn(any());
		assertWiped(pw);
	}

	/** The five account-protecting controls all go through the gate. */
	@Test
	public void everyProtectingControlIsGated() throws Exception {
		String security = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/settings/"
						+ "SecurityFragment.java")), StandardCharsets.UTF_8);
		String profiles = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/settings/"
						+ "ProfilesFragment.java")), StandardCharsets.UTF_8);
		assertTrue(security.contains(
				"withAccountPassword(this::showWipeOnFailedLoginsDialog"));
		assertTrue(security.contains(".setWipeOnRepeatedFailures(false),"));
		assertTrue(security.contains(
				"wipePasswordCard.setOnClickListener(v -> withAccountPassword("));
		assertTrue(security.contains(
				".DecoyConfig.setEnabled(requireContext(), isChecked),"));
		assertTrue(security.contains(
				"withAccountPassword(this::showDecoySetCodeDialog"));
		assertTrue(profiles.contains("AccountPasswordGate.prompt("));
		assertTrue(profiles.contains("if (isAdded()) doDeleteActiveProfile();"));
	}

	private static void assertWiped(char[] pw) {
		for (char c : pw) assertEquals(0, c);
	}
}
