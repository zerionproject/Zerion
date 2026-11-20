package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class KdbxDocumentHandler extends DefaultHandler {

	static final int MAX_DEPTH = 64;
	static final int MAX_ENTRIES = 50_000;
	static final int MAX_ENTRY_ELEMENTS = 500_000;
	static final int MAX_GROUPS = 10_000;
	static final int MAX_VALUE_CHARS = 1024 * 1024;
	static final int MAX_STRINGS_PER_ENTRY = 1000;

	private static final class Group {
		@Nullable
		String uuid = null;
		String name = "";
		boolean inRecycleBin = false;
	}

	private static final class Entry {
		final Map<String, String> strings = new LinkedHashMap<>();
		final boolean history;
		boolean hasBinary = false;

		Entry(boolean history) {
			this.history = history;
		}
	}

	private final KdbxInnerStream innerStream;
	private final List<KdbxEntry> entries = new ArrayList<>();
	private final Deque<String> path = new ArrayDeque<>();
	private final Deque<Group> groups = new ArrayDeque<>();
	private final Deque<Entry> entryStack = new ArrayDeque<>();
	private final StringBuilder text = new StringBuilder();

	private boolean sawRoot = false;
	private boolean collecting = false;
	private boolean valueProtected = false;
	private int entryElements = 0;
	private int groupCount = 0;
	private int historyEntries = 0;
	private boolean recycleBinEnabled = false;
	@Nullable
	private String recycleBinUuid = null;
	@Nullable
	private String stringKey = null;
	@Nullable
	private String stringValue = null;

	KdbxDocumentHandler(KdbxInnerStream innerStream) {
		this.innerStream = innerStream;
	}

	List<KdbxEntry> entries() {
		return entries;
	}

	int historyEntries() {
		return historyEntries;
	}

	@Override
	public void startElement(String uri, String localName, String qName,
			Attributes attributes) throws SAXException {
		if (path.isEmpty()) {
			if (!"KeePassFile".equals(qName)) {
				throw fail(KdbxException.corrupted("root element"));
			}
			sawRoot = true;
		}
		if (path.size() >= MAX_DEPTH) {
			throw fail(KdbxException.corrupted("xml depth"));
		}
		String parent = path.peek();
		path.push(qName);
		text.setLength(0);
		collecting = false;
		switch (qName) {
			case "Group":
				if (!"Root".equals(parent) && !"Group".equals(parent)) break;
				if (++groupCount > MAX_GROUPS) {
					throw fail(KdbxException.tooLarge("groups"));
				}
				Group group = new Group();
				Group outer = groups.peek();
				if (outer != null && outer.inRecycleBin) group.inRecycleBin = true;
				groups.push(group);
				break;
			case "Entry":
				if (!"Group".equals(parent) && !"History".equals(parent)) break;
				if (++entryElements > MAX_ENTRY_ELEMENTS) {
					throw fail(KdbxException.tooLarge("entries"));
				}
				boolean history = "History".equals(parent) || !entryStack.isEmpty();
				if (!history && entries.size() >= MAX_ENTRIES) {
					throw fail(KdbxException.tooLarge("entries"));
				}
				entryStack.push(new Entry(history));
				break;
			case "String":
				if ("Entry".equals(parent) && !entryStack.isEmpty()) {
					stringKey = null;
					stringValue = null;
				}
				break;
			case "Key":
				collecting = "String".equals(parent) && !entryStack.isEmpty();
				break;
			case "Value":
				valueProtected = "True".equalsIgnoreCase(
						attributes.getValue("Protected"));
				collecting = valueProtected
						|| ("String".equals(parent) && !entryStack.isEmpty());
				break;
			case "Binary":
				if ("Entry".equals(parent) && !entryStack.isEmpty()) {
					entryStack.peek().hasBinary = true;
				}
				break;
			case "UUID":
			case "Name":
				collecting = "Group".equals(parent) && entryStack.isEmpty()
						&& !groups.isEmpty();
				break;
			case "RecycleBinEnabled":
			case "RecycleBinUUID":
				collecting = "Meta".equals(parent);
				break;
			default:
				break;
		}
	}

	@Override
	public void characters(char[] ch, int start, int length)
			throws SAXException {
		if (!collecting) return;
		if (text.length() + length > MAX_VALUE_CHARS) {
			throw fail(KdbxException.corrupted("value size"));
		}
		text.append(ch, start, length);
	}

	@Override
	public void endElement(String uri, String localName, String qName)
			throws SAXException {
		path.pop();
		String parent = path.peek();
		switch (qName) {
			case "Value":
				if (collecting) {
					String value = valueProtected ? unprotect(text.toString())
							: text.toString();
					if ("String".equals(parent) && !entryStack.isEmpty()) {
						stringValue = value;
					}
				}
				valueProtected = false;
				break;
			case "Key":
				if (collecting) stringKey = text.toString();
				break;
			case "String":
				if ("Entry".equals(parent) && !entryStack.isEmpty()) {
					Entry entry = entryStack.peek();
					if (stringKey != null && !entry.history) {
						if (entry.strings.size() >= MAX_STRINGS_PER_ENTRY) {
							throw fail(KdbxException.corrupted("entry strings"));
						}
						entry.strings.put(stringKey,
								stringValue == null ? "" : stringValue);
					}
					stringKey = null;
					stringValue = null;
				}
				break;
			case "Entry":
				if (!"Group".equals(parent) && !"History".equals(parent)) break;
				if (entryStack.isEmpty()) break;
				Entry done = entryStack.pop();
				if (done.history) {
					historyEntries++;
				} else {
					entries.add(new KdbxEntry(done.strings, groupPathForEntry(),
							currentGroupInRecycleBin(), done.hasBinary));
				}
				break;
			case "Group":
				if (!"Root".equals(parent) && !"Group".equals(parent)) break;
				if (!groups.isEmpty()) groups.pop();
				break;
			case "UUID":
				if (collecting && !groups.isEmpty()) {
					Group group = groups.peek();
					group.uuid = text.toString().trim();
					if (recycleBinEnabled && recycleBinUuid != null
							&& recycleBinUuid.equals(group.uuid)) {
						group.inRecycleBin = true;
					}
				}
				break;
			case "Name":
				if (collecting && !groups.isEmpty()) {
					groups.peek().name = text.toString();
				}
				break;
			case "RecycleBinEnabled":
				if (collecting) {
					recycleBinEnabled = "True".equalsIgnoreCase(text.toString().trim());
				}
				break;
			case "RecycleBinUUID":
				if (collecting) recycleBinUuid = text.toString().trim();
				break;
			default:
				break;
		}
		text.setLength(0);
		collecting = false;
	}

	@Override
	public void endDocument() throws SAXException {
		if (!sawRoot) throw fail(KdbxException.corrupted("empty document"));
	}

	private List<String> groupPathForEntry() {
		List<String> names = new ArrayList<>();
		boolean first = true;
		Object[] stack = groups.toArray();
		for (int i = stack.length - 1; i >= 0; i--) {
			if (first) {
				first = false;
				continue;
			}
			names.add(((Group) stack[i]).name);
		}
		return names;
	}

	private boolean currentGroupInRecycleBin() {
		Group group = groups.peek();
		return group != null && group.inRecycleBin;
	}

	private String unprotect(String base64) throws SAXException {
		StringBuilder compact = new StringBuilder(base64.length());
		for (int i = 0; i < base64.length(); i++) {
			char c = base64.charAt(i);
			if (!Character.isWhitespace(c)) compact.append(c);
		}
		byte[] encrypted;
		try {
			encrypted = java.util.Base64.getDecoder().decode(compact.toString());
		} catch (IllegalArgumentException e) {
			throw fail(KdbxException.corrupted("protected value"));
		}
		byte[] plain = innerStream.process(encrypted);
		KdbxBytes.wipe(encrypted);
		String value = new String(plain, StandardCharsets.UTF_8);
		KdbxBytes.wipe(plain);
		return value;
	}

	private static SAXException fail(KdbxException e) {
		return new SAXException(e);
	}
}
