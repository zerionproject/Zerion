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
	static final int MAX_ENTRIES = 10_000;
	static final int MAX_ENTRY_ELEMENTS = 500_000;
	static final int MAX_GROUPS = 10_000;
	static final int MAX_VALUE_CHARS = 1024 * 1024;
	static final int MAX_SMALL_TEXT = 4096;
	static final int MAX_STRINGS_PER_ENTRY = 1000;
	private static final int BASE64_CHUNK = 4096;

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
		boolean oversized = false;

		Entry(boolean history) {
			this.history = history;
		}
	}

	private final class ProtectedDecoder {

		private final boolean store;
		private final StringBuilder pending = new StringBuilder(BASE64_CHUNK);
		@Nullable
		private final KdbxStreams.WipingBuffer plain;
		private boolean padded = false;

		ProtectedDecoder(boolean store) {
			this.store = store;
			this.plain = store ? new KdbxStreams.WipingBuffer(64,
					MAX_VALUE_CHARS) : null;
		}

		void append(char[] ch, int start, int length) throws SAXException {
			for (int i = 0; i < length; i++) {
				char c = ch[start + i];
				if (Character.isWhitespace(c)) continue;
				if (c == '=') {
					padded = true;
					continue;
				}
				if (padded) throw fail(KdbxException.corrupted("protected value"));
				pending.append(c);
				if (pending.length() >= BASE64_CHUNK) flush(false);
			}
		}

		private void flush(boolean last) throws SAXException {
			int usable = last ? pending.length()
					: pending.length() - pending.length() % 4;
			if (usable == 0) return;
			byte[] encrypted;
			try {
				encrypted = java.util.Base64.getDecoder()
						.decode(pending.substring(0, usable));
			} catch (IllegalArgumentException e) {
				throw fail(KdbxException.corrupted("protected value"));
			}
			pending.delete(0, usable);
			byte[] decrypted = innerStream.process(encrypted);
			KdbxBytes.wipe(encrypted);
			if (plain != null && plain.size() < MAX_VALUE_CHARS) {
				int room = Math.min(decrypted.length,
						MAX_VALUE_CHARS - plain.size());
				plain.write(decrypted, 0, room);
			}
			KdbxBytes.wipe(decrypted);
		}

		String finish() throws SAXException {
			flush(true);
			if (plain == null) return "";
			byte[] bytes = plain.toByteArray();
			String value = new String(bytes, StandardCharsets.UTF_8);
			KdbxBytes.wipe(bytes);
			plain.wipe();
			return value;
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
	private int textCap = MAX_SMALL_TEXT;
	@Nullable
	private ProtectedDecoder decoder = null;
	private int entryElements = 0;
	private int groupCount = 0;
	private int historyEntries = 0;
	private int skippedEntries = 0;
	private boolean recycleBinEnabled = false;
	@Nullable
	private String recycleBinUuid = null;
	@Nullable
	private String headerHash = null;
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

	int skippedEntries() {
		return skippedEntries;
	}

	@Nullable
	String headerHash() {
		return headerHash;
	}

	private boolean inEntryString(@Nullable String parent) {
		return "String".equals(parent) && !entryStack.isEmpty();
	}

	@Override
	public void startElement(String uri, String localName, String qName,
			Attributes attributes) throws SAXException {
		if (decoder != null) {
			throw fail(KdbxException.corrupted("protected value"));
		}
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
		textCap = MAX_SMALL_TEXT;
		if ("True".equalsIgnoreCase(attributes.getValue("Protected"))) {
			decoder = new ProtectedDecoder(
					"Value".equals(qName) && inEntryString(parent));
			return;
		}
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
				boolean history = "History".equals(parent)
						|| !entryStack.isEmpty();
				if (!history && entries.size() + skippedEntries >= MAX_ENTRIES) {
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
				collecting = inEntryString(parent);
				break;
			case "Value":
				collecting = inEntryString(parent);
				textCap = MAX_VALUE_CHARS;
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
			case "HeaderHash":
				collecting = "Meta".equals(parent);
				break;
			default:
				break;
		}
	}

	@Override
	public void characters(char[] ch, int start, int length)
			throws SAXException {
		if (decoder != null) {
			decoder.append(ch, start, length);
			return;
		}
		if (!collecting) return;
		int room = textCap - text.length();
		if (room <= 0) return;
		text.append(ch, start, Math.min(length, room));
	}

	@Override
	public void endElement(String uri, String localName, String qName)
			throws SAXException {
		path.pop();
		String parent = path.peek();
		if (decoder != null) {
			String value = decoder.finish();
			if (decoder.store) stringValue = value;
			decoder = null;
			collecting = false;
			text.setLength(0);
			return;
		}
		switch (qName) {
			case "Value":
				if (collecting) stringValue = text.toString();
				break;
			case "Key":
				if (collecting) stringKey = text.toString();
				break;
			case "String":
				if ("Entry".equals(parent) && !entryStack.isEmpty()) {
					Entry entry = entryStack.peek();
					if (stringKey != null && !entry.history) {
						if (entry.strings.size() >= MAX_STRINGS_PER_ENTRY) {
							entry.oversized = true;
						} else {
							entry.strings.put(stringKey,
									stringValue == null ? "" : stringValue);
						}
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
				} else if (done.oversized) {
					skippedEntries++;
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
					recycleBinEnabled = "True".equalsIgnoreCase(
							text.toString().trim());
				}
				break;
			case "RecycleBinUUID":
				if (collecting) recycleBinUuid = text.toString().trim();
				break;
			case "HeaderHash":
				if (collecting) headerHash = text.toString().trim();
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

	private static SAXException fail(KdbxException e) {
		return new SAXException(e);
	}
}
