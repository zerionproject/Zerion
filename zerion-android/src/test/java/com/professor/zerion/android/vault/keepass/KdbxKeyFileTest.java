package com.professor.zerion.android.vault.keepass;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KdbxKeyFileTest {

	private static byte[] key() {
		byte[] key = new byte[32];
		for (int i = 0; i < 32; i++) key[i] = (byte) (200 + i);
		return key;
	}

	private static String hex(byte[] data) {
		StringBuilder b = new StringBuilder();
		for (byte x : data) b.append(String.format("%02X", x));
		return b.toString();
	}

	private static byte[] sha256(byte[] data) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	@Test
	public void rawThirtyTwoBytesAreTheKey() throws Exception {
		assertArrayEquals(key(), KdbxKeyFile.keyFromFile(key()));
	}

	@Test
	public void sixtyFourHexCharactersAreDecoded() throws Exception {
		byte[] file = hex(key()).toLowerCase().getBytes(StandardCharsets.US_ASCII);
		assertArrayEquals(key(), KdbxKeyFile.keyFromFile(file));
	}

	@Test
	public void sixtyFourNonHexBytesAreHashed() throws Exception {
		byte[] file = new byte[64];
		for (int i = 0; i < 64; i++) file[i] = (byte) ('g' + (i % 5));
		assertArrayEquals(sha256(file), KdbxKeyFile.keyFromFile(file));
	}

	@Test
	public void otherFilesAreHashed() throws Exception {
		byte[] file = "any other content, for example a photo".getBytes(
				StandardCharsets.UTF_8);
		assertArrayEquals(sha256(file), KdbxKeyFile.keyFromFile(file));
	}

	@Test
	public void xmlVersion1IsBase64() throws Exception {
		String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<KeyFile>\n"
				+ "\t<Meta><Version>1.00</Version></Meta>\n"
				+ "\t<Key><Data>" + Base64.getEncoder().encodeToString(key())
				+ "</Data></Key>\n</KeyFile>\n";
		assertArrayEquals(key(),
				KdbxKeyFile.keyFromFile(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	public void xmlVersion2IsHexWithHash() throws Exception {
		String hash = hex(sha256(key())).substring(0, 8);
		String hexKey = hex(key());
		String spaced = hexKey.substring(0, 32).replaceAll("(.{8})", "$1 ")
				+ "\n\t\t\t" + hexKey.substring(32).replaceAll("(.{8})", "$1 ");
		String xml = "<KeyFile><Meta><Version>2.0</Version></Meta><Key>"
				+ "<Data Hash=\"" + hash + "\">\n\t\t\t" + spaced
				+ "\n\t\t</Data></Key></KeyFile>";
		assertArrayEquals(key(),
				KdbxKeyFile.keyFromFile(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	public void xmlVersion2WithoutHashIsAccepted() throws Exception {
		String xml = "<KeyFile><Meta><Version>2.0</Version></Meta><Key><Data>"
				+ hex(key()) + "</Data></Key></KeyFile>";
		assertArrayEquals(key(),
				KdbxKeyFile.keyFromFile(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	public void xmlVersion2WithWrongHashIsRefused() throws Exception {
		String xml = "<KeyFile><Meta><Version>2.0</Version></Meta><Key>"
				+ "<Data Hash=\"00000000\">" + hex(key())
				+ "</Data></Key></KeyFile>";
		try {
			KdbxKeyFile.keyFromFile(xml.getBytes(StandardCharsets.UTF_8));
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
	}

	@Test
	public void xmlKeyFileWithDoctypeIsRefused() throws Exception {
		String xml = "<!DOCTYPE KeyFile [<!ENTITY e SYSTEM \"file:///x\">]>"
				+ "<KeyFile><Meta><Version>2.0</Version></Meta><Key><Data>&e;"
				+ "</Data></Key></KeyFile>";
		try {
			KdbxKeyFile.keyFromFile(xml.getBytes(StandardCharsets.UTF_8));
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
	}

	@Test
	public void otherXmlDocumentsAreHashedLikeAnyFile() throws Exception {
		byte[] file = "<note><to>me</to></note>".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(sha256(file), KdbxKeyFile.keyFromFile(file));
	}

	@Test
	public void emptyKeyFileIsRefused() {
		try {
			KdbxKeyFile.keyFromFile(new byte[0]);
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.WRONG_KEY, e.reason);
		}
	}
}
