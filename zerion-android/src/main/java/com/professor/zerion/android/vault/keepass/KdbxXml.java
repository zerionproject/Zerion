package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.helpers.DefaultHandler;

@NotNullByDefault
final class KdbxXml {

	private static final byte[] DOCTYPE = "<!DOCTYPE".getBytes();
	private static final byte[] ENTITY = "<!ENTITY".getBytes();

	private KdbxXml() {
	}

	static boolean containsDeclaration(byte[] xml) {
		return indexOfIgnoreCase(xml, DOCTYPE) >= 0
				|| indexOfIgnoreCase(xml, ENTITY) >= 0;
	}

	private static int indexOfIgnoreCase(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= data.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				int a = data[i + j] & 0xFF;
				int b = needle[j] & 0xFF;
				if (a >= 'a' && a <= 'z') a -= 32;
				if (a != b) continue outer;
			}
			return i;
		}
		return -1;
	}

	static void parse(byte[] xml, DefaultHandler handler)
			throws KdbxException {
		if (containsDeclaration(xml)) {
			throw KdbxException.corrupted("xml declaration");
		}
		SAXParser parser = newParser();
		try (InputStream in = new ByteArrayInputStream(xml)) {
			parser.parse(in, handler);
		} catch (SAXException e) {
			Throwable cause = e.getException();
			if (cause instanceof KdbxException) throw (KdbxException) cause;
			throw KdbxException.corrupted("xml");
		} catch (IOException | RuntimeException e) {
			throw KdbxException.corrupted("xml");
		}
	}

	private static SAXParser newParser() throws KdbxException {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(false);
		factory.setValidating(false);
		try {
			factory.setXIncludeAware(false);
		} catch (Exception ignored) {
		}
		try {
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		} catch (Exception ignored) {
		}
		try {
			factory.setFeature(
					"http://apache.org/xml/features/disallow-doctype-decl", true);
		} catch (Exception ignored) {
		}
		try {
			factory.setFeature(
					"http://xml.org/sax/features/external-general-entities",
					false);
		} catch (Exception ignored) {
		}
		try {
			factory.setFeature(
					"http://xml.org/sax/features/external-parameter-entities",
					false);
		} catch (Exception ignored) {
		}
		try {
			return factory.newSAXParser();
		} catch (ParserConfigurationException | SAXException e) {
			throw KdbxException.corrupted("xml parser");
		}
	}
}
