package com.professor.zerion.android.vault.utils;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MetadataStripperOfficeTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String XML_HEAD =
			"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

	private static final String CORE_OVERRIDE = "<Override PartName=\""
			+ "/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats"
			+ "-package.core-properties+xml\"/>";
	private static final String CUSTOM_XML_OVERRIDE = "<Override PartName=\""
			+ "/customXml/itemProps1.xml\" ContentType=\"application/vnd."
			+ "openxmlformats-officedocument.customXmlProperties+xml\"/>";
	private static final String PEOPLE_OVERRIDE = "<Override PartName=\""
			+ "/word/people.xml\" ContentType=\"application/vnd.openxmlformats"
			+ "-officedocument.wordprocessingml.people+xml\"/>";
	private static final String DOCUMENT_OVERRIDE = "<Override PartName=\""
			+ "/word/document.xml\" ContentType=\"application/vnd."
			+ "openxmlformats-officedocument.wordprocessingml.document.main+xml"
			+ "\"/>";
	private static final String COMMENTS_OVERRIDE = "<Override PartName=\""
			+ "/word/comments.xml\" ContentType=\"application/vnd."
			+ "openxmlformats-officedocument.wordprocessingml.comments+xml\"/>";

	private static final String DOCX_CONTENT_TYPES = XML_HEAD
			+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
			+ "content-types\"><Default Extension=\"rels\" ContentType=\""
			+ "application/vnd.openxmlformats-package.relationships+xml\"/>"
			+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
			+ DOCUMENT_OVERRIDE + CORE_OVERRIDE + CUSTOM_XML_OVERRIDE
			+ PEOPLE_OVERRIDE + COMMENTS_OVERRIDE + "</Types>";

	private static final String CORE_RELATIONSHIP = "<Relationship Id=\"rId2\""
			+ " Type=\"http://schemas.openxmlformats.org/package/2006/"
			+ "relationships/metadata/core-properties\" Target=\"docProps/"
			+ "core.xml\"/>";
	private static final String DOCUMENT_RELATIONSHIP = "<Relationship Id=\""
			+ "rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
			+ "2006/relationships/officeDocument\" Target=\"word/document.xml\""
			+ "/>";
	private static final String PACKAGE_RELS = XML_HEAD + "<Relationships "
			+ "xmlns=\"http://schemas.openxmlformats.org/package/2006/"
			+ "relationships\">" + DOCUMENT_RELATIONSHIP + CORE_RELATIONSHIP
			+ "</Relationships>";

	private static final String PEOPLE_RELATIONSHIP = "<Relationship Id=\""
			+ "rId7\" Type=\"http://schemas.microsoft.com/office/2011/"
			+ "relationships/people\" Target=\"people.xml\"/>";
	private static final String CUSTOM_XML_RELATIONSHIP = "<Relationship Id="
			+ "\"rId8\" Type=\"http://schemas.openxmlformats.org/"
			+ "officeDocument/2006/relationships/customXml\" Target=\"../"
			+ "customXml/item1.xml\"/>";
	private static final String STYLES_RELATIONSHIP = "<Relationship Id=\""
			+ "rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
			+ "2006/relationships/styles\" Target=\"styles.xml\"/>";
	private static final String COMMENTS_RELATIONSHIP = "<Relationship Id=\""
			+ "rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
			+ "2006/relationships/comments\" Target=\"comments.xml\"/>";
	private static final String LINK_RELATIONSHIP = "<Relationship Id=\""
			+ "rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
			+ "2006/relationships/hyperlink\" Target=\"https://example.org/"
			+ "people.xml\" TargetMode=\"External\"/>";
	private static final String DOCUMENT_RELS = XML_HEAD + "<Relationships "
			+ "xmlns=\"http://schemas.openxmlformats.org/package/2006/"
			+ "relationships\">" + STYLES_RELATIONSHIP + COMMENTS_RELATIONSHIP
			+ LINK_RELATIONSHIP + PEOPLE_RELATIONSHIP + CUSTOM_XML_RELATIONSHIP
			+ "</Relationships>";

	private static final String DOCUMENT = XML_HEAD + "<w:document xmlns:w=\""
			+ "http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
			+ "<w:body><w:p><w:r><w:t>Hello people.xml</w:t></w:r></w:p>"
			+ "</w:body></w:document>";
	private static final String COMMENTS = XML_HEAD + "<w:comments xmlns:w=\""
			+ "http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
			+ "<w:comment w:id=\"0\" w:author=\"Ada\" w:date=\"2026-01-01T00:"
			+ "00:00Z\"><w:p><w:r><w:t>note</w:t></w:r></w:p></w:comment>"
			+ "</w:comments>";
	private static final String PEOPLE = XML_HEAD + "<w15:people xmlns:w15=\""
			+ "http://schemas.microsoft.com/office/word/2012/wordml\">"
			+ "<w15:person w15:author=\"Ada\"><w15:presenceInfo w15:providerId="
			+ "\"AD\" w15:userId=\"S::ada@example.org::1\"/></w15:person>"
			+ "</w15:people>";
	private static final String CORE = XML_HEAD + "<cp:coreProperties xmlns:cp="
			+ "\"http://schemas.openxmlformats.org/package/2006/metadata/"
			+ "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
			+ "<dc:creator>Ada</dc:creator></cp:coreProperties>";
	private static final String CUSTOM_XML = XML_HEAD + "<p:properties xmlns:p="
			+ "\"http://schemas.microsoft.com/office/2006/metadata/properties\">"
			+ "<documentManagement/></p:properties>";

	private static final String ABS_PATH = "<x15ac:absPath url=\"C:\\Users\\"
			+ "ada\\Documents\\\" xmlns:x15ac=\"http://schemas.microsoft.com/"
			+ "office/spreadsheetml/2010/11/ac\"/>";
	private static final String WORKBOOK_BEFORE = XML_HEAD + "<workbook xmlns="
			+ "\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
			+ "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility"
			+ "/2006\" mc:Ignorable=\"x15\"><fileVersion appName=\"xl\" "
			+ "lastEdited=\"7\"/><workbookPr/><mc:AlternateContent xmlns:mc=\""
			+ "http://schemas.openxmlformats.org/markup-compatibility/2006\">"
			+ "<mc:Choice Requires=\"x15\">";
	private static final String WORKBOOK_AFTER = "</mc:Choice>"
			+ "</mc:AlternateContent><sheets><sheet name=\"Sheet1\" sheetId="
			+ "\"1\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/"
			+ "officeDocument/2006/relationships\"/></sheets></workbook>";
	private static final String XLSX_CONTENT_TYPES = XML_HEAD
			+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
			+ "content-types\"><Override PartName=\"/xl/workbook.xml\" "
			+ "ContentType=\"application/vnd.openxmlformats-officedocument."
			+ "spreadsheetml.sheet.main+xml\"/></Types>";

	private static byte[] zip(Map<String, String> parts) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		try (ZipOutputStream zout = new ZipOutputStream(bout)) {
			for (Map.Entry<String, String> e : parts.entrySet()) {
				zout.putNextEntry(new ZipEntry(e.getKey()));
				zout.write(e.getValue().getBytes(StandardCharsets.UTF_8));
				zout.closeEntry();
			}
		}
		return bout.toByteArray();
	}

	private static Map<String, byte[]> unzip(byte[] data) throws IOException {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		try (ZipInputStream zin =
				new ZipInputStream(new ByteArrayInputStream(data))) {
			ZipEntry e;
			byte[] buf = new byte[4096];
			while ((e = zin.getNextEntry()) != null) {
				ByteArrayOutputStream part = new ByteArrayOutputStream();
				int read;
				while ((read = zin.read(buf)) > 0) part.write(buf, 0, read);
				parts.put(e.getName(), part.toByteArray());
			}
		}
		return parts;
	}

	private static String text(Map<String, byte[]> parts, String name) {
		byte[] b = parts.get(name);
		assertTrue(name + " is present", b != null);
		return new String(b, StandardCharsets.UTF_8);
	}

	private static byte[] clean(byte[] zip) throws Exception {
		return new MetadataStripper(ApplicationProvider.getApplicationContext())
				.stripOfficeMetadataOrThrow(zip);
	}

	private static Map<String, String> docx() {
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", DOCX_CONTENT_TYPES);
		parts.put("_rels/.rels", PACKAGE_RELS);
		parts.put("docProps/core.xml", CORE);
		parts.put("customXml/item1.xml", CUSTOM_XML);
		parts.put("customXml/itemProps1.xml", CUSTOM_XML);
		parts.put("customXml/_rels/item1.xml.rels", PACKAGE_RELS);
		parts.put("word/document.xml", DOCUMENT);
		parts.put("word/comments.xml", COMMENTS);
		parts.put("word/people.xml", PEOPLE);
		parts.put("word/_rels/document.xml.rels", DOCUMENT_RELS);
		return parts;
	}

	@Test
	public void aWordFileLosesItsPropertiesCustomXmlAndPeopleParts()
			throws Exception {
		Map<String, byte[]> out = unzip(clean(zip(docx())));
		List<String> names = new ArrayList<>(out.keySet());
		assertEquals("[Content_Types].xml", names.get(0));
		assertFalse(out.containsKey("docProps/core.xml"));
		assertFalse(out.containsKey("customXml/item1.xml"));
		assertFalse(out.containsKey("customXml/itemProps1.xml"));
		assertFalse(out.containsKey("customXml/_rels/item1.xml.rels"));
		assertFalse(out.containsKey("word/people.xml"));
		assertTrue(out.containsKey("word/comments.xml"));
		assertArrayEquals(DOCUMENT.getBytes(StandardCharsets.UTF_8),
				out.get("word/document.xml"));
		assertArrayEquals(COMMENTS.getBytes(StandardCharsets.UTF_8),
				out.get("word/comments.xml"));
		assertEquals(5, out.size());
	}

	@Test
	public void theContentTypesLoseOnlyTheOverridesOfRemovedParts()
			throws Exception {
		Map<String, byte[]> out = unzip(clean(zip(docx())));
		String types = text(out, "[Content_Types].xml");
		assertFalse(types, types.contains(CORE_OVERRIDE));
		assertFalse(types, types.contains(CUSTOM_XML_OVERRIDE));
		assertFalse(types, types.contains(PEOPLE_OVERRIDE));
		assertFalse(types, types.contains("docProps"));
		assertFalse(types, types.contains("customXml"));
		assertFalse(types, types.contains("people.xml"));
		assertEquals(DOCX_CONTENT_TYPES.replace(CORE_OVERRIDE, "")
				.replace(CUSTOM_XML_OVERRIDE, "")
				.replace(PEOPLE_OVERRIDE, ""), types);
	}

	@Test
	public void everyRelationshipsPartLosesOnlyTheEntriesOfRemovedParts()
			throws Exception {
		Map<String, byte[]> out = unzip(clean(zip(docx())));
		String packageRels = text(out, "_rels/.rels");
		assertFalse(packageRels, packageRels.contains(CORE_RELATIONSHIP));
		assertEquals(PACKAGE_RELS.replace(CORE_RELATIONSHIP, ""), packageRels);
		String documentRels = text(out, "word/_rels/document.xml.rels");
		assertFalse(documentRels, documentRels.contains(PEOPLE_RELATIONSHIP));
		assertFalse(documentRels,
				documentRels.contains(CUSTOM_XML_RELATIONSHIP));
		assertTrue("an external link whose address ends like a removed part"
				+ " stays", documentRels.contains(LINK_RELATIONSHIP));
		assertTrue(documentRels.contains(STYLES_RELATIONSHIP));
		assertTrue(documentRels.contains(COMMENTS_RELATIONSHIP));
		assertEquals(DOCUMENT_RELS.replace(PEOPLE_RELATIONSHIP, "")
				.replace(CUSTOM_XML_RELATIONSHIP, ""), documentRels);
	}

	@Test
	public void removedPartsAreMatchedByNameNotByCase() {
		assertTrue(MetadataStripper.isRemovedOfficePart("docprops/core.xml"));
		assertTrue(MetadataStripper.isRemovedOfficePart(
				"docprops/thumbnail.jpeg"));
		assertTrue(MetadataStripper.isRemovedOfficePart("customxml/item1.xml"));
		assertTrue(MetadataStripper.isRemovedOfficePart(
				"customxml/_rels/item1.xml.rels"));
		assertTrue(MetadataStripper.isRemovedOfficePart("word/people.xml"));
		assertTrue(MetadataStripper.isRemovedOfficePart(
				"ppt/commentauthors.xml"));
		assertFalse(MetadataStripper.isRemovedOfficePart("word/comments.xml"));
		assertFalse(MetadataStripper.isRemovedOfficePart(
				"xl/persons/person.xml"));
		assertFalse(MetadataStripper.isRemovedOfficePart(
				"xl/threadedcomments/threadedcomment1.xml"));
		assertFalse(MetadataStripper.isRemovedOfficePart("word/document.xml"));
		assertFalse(MetadataStripper.isRemovedOfficePart(
				"ppt/comments/comment1.xml"));
	}

	@Test
	public void relationshipTargetsResolveAgainstTheirSourcePart() {
		assertEquals("docprops/core.xml", MetadataStripper.resolveOfficePart(
				"_rels/.rels", "docProps/core.xml"));
		assertEquals("docprops/core.xml", MetadataStripper.resolveOfficePart(
				"_rels/.rels", "/docProps/core.xml"));
		assertEquals("word/people.xml", MetadataStripper.resolveOfficePart(
				"word/_rels/document.xml.rels", "people.xml"));
		assertEquals("customxml/item1.xml", MetadataStripper.resolveOfficePart(
				"word/_rels/document.xml.rels", "../customXml/item1.xml"));
		assertEquals("ppt/commentauthors.xml",
				MetadataStripper.resolveOfficePart(
						"ppt/_rels/presentation.xml.rels",
						"./commentAuthors.xml"));
		assertEquals("word/comments.xml", MetadataStripper.resolveOfficePart(
				"word/_rels/document.xml.rels", "comments.xml"));
	}

	@Test
	public void aPresentationLosesItsCommentAuthors() throws Exception {
		String authorsRel = "<Relationship Id=\"rId5\" Type=\"http://schemas."
				+ "openxmlformats.org/officeDocument/2006/relationships/"
				+ "commentAuthors\" Target=\"commentAuthors.xml\"/>";
		String slideRel = "<Relationship Id=\"rId1\" Type=\"http://schemas."
				+ "openxmlformats.org/officeDocument/2006/relationships/slide\""
				+ " Target=\"slides/slide1.xml\"/>";
		String rels = XML_HEAD + "<Relationships xmlns=\"http://schemas."
				+ "openxmlformats.org/package/2006/relationships\">" + slideRel
				+ authorsRel + "</Relationships>";
		String authorsOverride = "<Override PartName=\"/ppt/commentAuthors.xml"
				+ "\" ContentType=\"application/vnd.openxmlformats-"
				+ "officedocument.presentationml.commentAuthors+xml\"/>";
		String types = XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats"
				+ ".org/package/2006/content-types\"><Override PartName=\"/ppt/"
				+ "presentation.xml\" ContentType=\"x\"/>" + authorsOverride
				+ "</Types>";
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", types);
		parts.put("ppt/presentation.xml", XML_HEAD + "<p:presentation/>");
		parts.put("ppt/commentAuthors.xml", XML_HEAD + "<p:cmAuthorLst/>");
		parts.put("ppt/comments/comment1.xml", XML_HEAD + "<p:cmLst/>");
		parts.put("ppt/_rels/presentation.xml.rels", rels);
		Map<String, byte[]> out = unzip(clean(zip(parts)));
		assertFalse(out.containsKey("ppt/commentAuthors.xml"));
		assertTrue(out.containsKey("ppt/comments/comment1.xml"));
		assertEquals(types.replace(authorsOverride, ""),
				text(out, "[Content_Types].xml"));
		assertEquals(rels.replace(authorsRel, ""),
				text(out, "ppt/_rels/presentation.xml.rels"));
	}

	@Test
	public void aWorkbookLosesTheSavedFilePathAndNothingElse()
			throws Exception {
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", XLSX_CONTENT_TYPES);
		parts.put("xl/workbook.xml", WORKBOOK_BEFORE + ABS_PATH
				+ WORKBOOK_AFTER);
		parts.put("xl/worksheets/sheet1.xml", XML_HEAD + "<worksheet/>");
		Map<String, byte[]> out = unzip(clean(zip(parts)));
		String workbook = text(out, "xl/workbook.xml");
		assertFalse(workbook, workbook.contains("absPath"));
		assertFalse(workbook, workbook.contains("Users"));
		assertEquals(WORKBOOK_BEFORE + WORKBOOK_AFTER, workbook);
		assertEquals(XLSX_CONTENT_TYPES, text(out, "[Content_Types].xml"));
		assertArrayEquals((XML_HEAD + "<worksheet/>")
				.getBytes(StandardCharsets.UTF_8),
				out.get("xl/worksheets/sheet1.xml"));
	}

	@Test
	public void aWorkbookWithoutAPathIsCopiedUnchanged() throws Exception {
		Map<String, String> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", XLSX_CONTENT_TYPES);
		parts.put("xl/workbook.xml", WORKBOOK_BEFORE + WORKBOOK_AFTER);
		Map<String, byte[]> out = unzip(clean(zip(parts)));
		assertEquals(WORKBOOK_BEFORE + WORKBOOK_AFTER,
				text(out, "xl/workbook.xml"));
	}
}
