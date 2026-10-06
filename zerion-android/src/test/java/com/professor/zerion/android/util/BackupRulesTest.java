package com.professor.zerion.android.util;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertTrue;

public class BackupRulesTest {

	private static final String[] DOMAINS = {"root", "file", "database",
			"sharedpref", "external", "device_root", "device_file",
			"device_database", "device_sharedpref"};

	private static Set<String> excludedDomains(Element section) {
		Set<String> out = new HashSet<>();
		NodeList ex = section.getElementsByTagName("exclude");
		for (int i = 0; i < ex.getLength(); i++) {
			Element e = (Element) ex.item(i);
			if (".".equals(e.getAttribute("path"))) {
				out.add(e.getAttribute("domain"));
			}
		}
		return out;
	}

	private static Document parse(String path) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new File(path));
	}

	@Test
	public void everyDomainIsExcludedFromCloudBackupAndDeviceTransfer()
			throws Exception {
		Document d = parse("src/main/res/xml/backup_extraction_rules.xml");
		for (String section : new String[] {"cloud-backup",
				"device-transfer"}) {
			NodeList n = d.getElementsByTagName(section);
			assertTrue(section, n.getLength() == 1);
			Set<String> ex = excludedDomains((Element) n.item(0));
			for (String domain : DOMAINS) {
				assertTrue(section + " " + domain, ex.contains(domain));
			}
			assertTrue(section, ((Element) n.item(0))
					.getElementsByTagName("include").getLength() == 0);
		}
	}

	@Test
	public void everyDomainIsExcludedFromLegacyFullBackup() throws Exception {
		Document d = parse("src/main/res/xml/backup_rules.xml");
		Set<String> ex = excludedDomains(d.getDocumentElement());
		for (String domain : DOMAINS) {
			assertTrue(domain, ex.contains(domain));
		}
	}
}
