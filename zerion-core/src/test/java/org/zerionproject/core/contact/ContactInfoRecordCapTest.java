package org.zerionproject.core.contact;

import org.junit.Test;
import org.zerionproject.core.api.record.Record;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ContactInfoRecordCapTest {

	@Test
	public void theManagerReadsContactRecordsUnderTheCap() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/org/zerionproject/core/contact/"
						+ "ContactExchangeManagerImpl.java")),
				StandardCharsets.UTF_8);
		assertTrue(src.contains("recordReaderFactory.createRecordReader("
				+ "streamReader,\n\t\t\t\t\t\tMAX_CONTACT_INFO_BYTES)"));
		assertEquals(64 * 1024,
				ContactExchangeManagerImpl.MAX_CONTACT_INFO_BYTES);
		assertTrue(ContactExchangeManagerImpl.MAX_CONTACT_INFO_BYTES
				< Record.MAX_RECORD_PAYLOAD_BYTES);
	}
}
