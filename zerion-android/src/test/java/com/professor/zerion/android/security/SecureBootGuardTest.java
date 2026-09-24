package com.professor.zerion.android.security;

import android.content.pm.Signature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

/**
 * STAT-05: in Hardened Mode the signature check fails closed. Missing
 * signing information, an empty signer list, a null entry or an unknown
 * signer are all a mismatch; only an accepted certificate digest passes.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureBootGuardTest {

	private static final String RELEASE =
			"d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8";
	private static final String PLAY =
			"b12ddf964ac59e3914984ec93e068768756bb0b917cb45c3fb2b65dc6c7940c6";

	private static String zeros() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 64; i++) sb.append('0');
		return sb.toString();
	}

	@Test
	public void abnormalAnswersAreAMismatch() {
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifySigners(null));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifySigners(new Signature[0]));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifySigners(new Signature[] {null}));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifyDigests(null));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifyDigests(Collections.emptyList()));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifyDigests(
						Collections.singletonList((String) null)));
	}

	@Test
	public void unknownSignerIsAMismatchUnlessAnAcceptedOneIsPresent() {
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifySigners(new Signature[] {
						new Signature(new byte[] {1, 2, 3, 4})}));
		assertEquals(SecureBootGuard.RESULT_SIGNATURE_MISMATCH,
				SecureBootGuard.classifyDigests(
						Collections.singletonList(zeros())));
		assertEquals(SecureBootGuard.RESULT_OK,
				SecureBootGuard.classifyDigests(
						Arrays.asList(zeros(), RELEASE)));
	}

	@Test
	public void acceptedDigestsPassInEitherCase() {
		assertEquals(SecureBootGuard.RESULT_OK,
				SecureBootGuard.classifyDigests(
						Collections.singletonList(RELEASE)));
		assertEquals(SecureBootGuard.RESULT_OK,
				SecureBootGuard.classifyDigests(
						Collections.singletonList(PLAY.toUpperCase())));
	}
}
