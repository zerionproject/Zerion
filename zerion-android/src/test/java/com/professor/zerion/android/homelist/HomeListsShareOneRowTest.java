package com.professor.zerion.android.homelist;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeListsShareOneRowTest {

	private static final File JAVA =
			new File("src/main/java/com/professor/zerion/android");
	private static final File LAYOUTS = new File("src/main/res/layout");
	private static final List<String> TABS = Arrays.asList(
			"chat/ChatsFragment.java", "grouptr/GroupTrListFragment.java",
			"channel/ChannelListFragment.java");
	private static final List<String> RETIRED_ROWS = Arrays.asList(
			"list_item_chat.xml", "list_item_grouptr_group.xml",
			"list_item_channel.xml", "fragment_chats.xml");

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	private static List<File> javaFiles(File dir) {
		List<File> out = new ArrayList<>();
		File[] children = dir.listFiles();
		if (children == null) return out;
		for (File c : children) {
			if (c.isDirectory()) out.addAll(javaFiles(c));
			else if (c.getName().endsWith(".java")) out.add(c);
		}
		return out;
	}

	private static Element byId(Element root, String id) {
		if (id.equals(root.getAttribute("android:id"))) return root;
		NodeList all = root.getElementsByTagName("*");
		for (int i = 0; i < all.getLength(); i++) {
			Element e = (Element) all.item(i);
			if (id.equals(e.getAttribute("android:id"))) return e;
		}
		return null;
	}

	@Test
	public void everyHomeTabBuildsItsListFromTheSharedRow() throws Exception {
		for (String tab : TABS) {
			String src = read(new File(JAVA, tab));
			assertTrue(tab, src.contains("HomeList.inflate("));
			for (String banned : new String[] {"R.layout.list_item_",
					"ScrollView", "LinearLayout", "listDivider(",
					"removeAllViews"}) {
				assertFalse(tab + " still uses " + banned,
						src.contains(banned));
			}
		}
		List<String> inflaters = new ArrayList<>();
		for (File f : javaFiles(JAVA)) {
			if (read(f).contains("R.layout.list_item_home_row")) {
				inflaters.add(f.getName());
			}
		}
		assertEquals(Arrays.asList("HomeListAdapter.java"), inflaters);
		for (String retired : RETIRED_ROWS) {
			assertFalse(retired, new File(LAYOUTS, retired).exists());
		}
	}

	@Test
	public void theSharedRowHasOneViewPerPart() throws Exception {
		Element root = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().parse(new File(LAYOUTS,
						"list_item_home_row.xml")).getDocumentElement();
		assertEquals("@+id/homeRow", root.getAttribute("android:id"));
		assertEquals("72dp", root.getAttribute("android:minHeight"));
		assertEquals("?attr/selectableItemBackground",
				root.getAttribute("android:background"));
		Element avatar = byId(root, "@+id/homeRowAvatar");
		assertEquals("48dp", avatar.getAttribute("android:layout_width"));
		assertEquals("48dp", avatar.getAttribute("android:layout_height"));
		for (String id : new String[] {"@+id/homeRowName", "@+id/homeRowTime",
				"@+id/homeRowPreview", "@+id/homeRowUnread",
				"@+id/homeRowMuted", "@+id/homeRowPinned",
				"@+id/homeRowAvatarText", "@+id/homeRowAvatarImage"}) {
			assertTrue(id, byId(root, id) != null);
		}
		Element name = byId(root, "@+id/homeRowName");
		assertEquals("1", name.getAttribute("android:maxLines"));
		assertEquals("end", name.getAttribute("android:ellipsize"));
		String adapter = read(new File(JAVA, "homelist/HomeListAdapter.java"));
		assertTrue(adapter.contains("R.layout.view_home_empty"));
	}
}
