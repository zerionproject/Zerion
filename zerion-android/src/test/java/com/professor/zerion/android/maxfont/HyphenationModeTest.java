package com.professor.zerion.android.maxfont;

import android.text.Layout;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class HyphenationModeTest {

	private static int hyphenationIn(String folder) throws Exception {
		File f = new File("src/main/res/" + folder + "/attrs.xml");
		NodeList list = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().parse(f).getDocumentElement()
				.getElementsByTagName("integer");
		for (int i = 0; i < list.getLength(); i++) {
			Element e = (Element) list.item(i);
			if ("text_hyphenation".equals(e.getAttribute("name"))) {
				return Integer.parseInt(e.getTextContent().trim());
			}
		}
		fail("no text_hyphenation in " + f);
		return -1;
	}

	@Test
	public void android13AndLaterHyphenateInTheFastMode() throws Exception {
		assertEquals(Layout.HYPHENATION_FREQUENCY_NORMAL_FAST,
				hyphenationIn("values-v33"));
	}

	@Test
	public void earlierReleasesHyphenateNormally() throws Exception {
		assertEquals(Layout.HYPHENATION_FREQUENCY_NORMAL,
				hyphenationIn("values"));
	}
}
