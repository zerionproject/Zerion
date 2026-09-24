package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.fail;

public class VoiceMemoFormatCheckTest {

	private static String body(int version) {
		return Base64.getEncoder().withoutPadding().encodeToString(
				new byte[] {(byte) version, 9, 8, 7, 6, 5});
	}

	private static String memo(int version) {
		return "[VOICE:1200:" + body(version) + "]";
	}

	private static String part(int seq, int version) {
		return "[VMP:1:0123456789abcdef:" + seq + ":3:1200:" + body(version)
				+ "]";
	}

	private static void assertRefused(String text) {
		try {
			VoiceMemoFormatCheck.requireCurrentFormat(text);
			fail(text);
		} catch (FormatException expected) {
		}
	}

	@Test
	public void currentFormatPasses() throws Exception {
		VoiceMemoFormatCheck.requireCurrentFormat(memo(2));
		VoiceMemoFormatCheck.requireCurrentFormat(part(0, 2));
	}

	@Test
	public void retiredFormatIsRefused() {
		assertRefused(memo(1));
		assertRefused(part(0, 1));
		assertRefused(memo(3));
		assertRefused(memo(0));
	}

	@Test
	public void laterPartsCarryNoVersionAndPass() throws Exception {
		VoiceMemoFormatCheck.requireCurrentFormat(part(1, 1));
		VoiceMemoFormatCheck.requireCurrentFormat(part(2, 0));
	}

	@Test
	public void malformedMemoTextIsRefused() {
		assertRefused("[VOICE:1200:!!!!]");
		assertRefused("[VOICE:1200:AA]");
		assertRefused("[VOICE:abc:" + body(2) + "]");
		assertRefused("[VMP:1:zz:0:1:1:" + body(2) + "]");
	}

	@Test
	public void ordinaryTextPasses() throws Exception {
		VoiceMemoFormatCheck.requireCurrentFormat(null);
		VoiceMemoFormatCheck.requireCurrentFormat("");
		VoiceMemoFormatCheck.requireCurrentFormat("hello [VOICE: world");
		VoiceMemoFormatCheck.requireCurrentFormat("VMP:1:not a part");
	}
}
