package com.professor.zerion.android.attachment;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CleanFileManifestTest {

	private static final String ANDROID =
			"http://schemas.android.com/apk/res/android";

	private static Element cleanFileActivity() throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(true);
		Document d = f.newDocumentBuilder().parse(
				new File("src/main/AndroidManifest.xml"));
		NodeList activities = d.getElementsByTagName("activity");
		for (int i = 0; i < activities.getLength(); i++) {
			Element a = (Element) activities.item(i);
			if (a.getAttributeNS(ANDROID, "name")
					.endsWith(".attachment.CleanFileActivity")) {
				return a;
			}
		}
		return null;
	}

	@Test
	public void theCleanFileScreenIsNotReachableFromOtherApps()
			throws Exception {
		Element a = cleanFileActivity();
		assertNotNull("the screen is declared", a);
		assertEquals("false", a.getAttributeNS(ANDROID, "exported"));
		NodeList children = a.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node n = children.item(i);
			if (n.getNodeType() != Node.ELEMENT_NODE) continue;
			assertFalse("the screen must not receive files from other apps: "
					+ n.getNodeName(), n.getNodeName().equals("intent-filter"));
		}
		assertTrue(a.getAttributeNS(ANDROID, "theme").contains("ZerionTheme"));
	}

	@Test
	public void theCleanFileScreenOnlySendsAndNeverOpensContent()
			throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/attachment/"
						+ "CleanFileActivity.java")), StandardCharsets.UTF_8);
		assertTrue(src.contains("Intent.ACTION_SEND"));
		assertFalse(src.contains("ACTION_VIEW"));
		assertTrue("the share copy is served by the file provider",
				src.contains("FileProvider.getUriForFile("));
		assertTrue(src.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
		assertFalse("the share copy must not be writable by the receiver",
				src.contains("FLAG_GRANT_WRITE_URI_PERMISSION"));
		assertTrue("own content is refused", src.contains("PickedUris.accept("));
		assertTrue("images pass the received image gate first",
				src.contains("ReceivedImageGate.checkedIfImage("));
		assertTrue(src.contains("new SharedMediaSanitizer(app, true)"));
		assertTrue("the fallback decoder is guarded by the policy",
				src.contains("CleanFilePolicy.mayDecodeInAppProcess("));
		assertTrue(src.contains("stripOfficeMetadataOrThrow("));
	}
}
