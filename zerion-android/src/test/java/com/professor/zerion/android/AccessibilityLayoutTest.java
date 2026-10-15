package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AccessibilityLayoutTest {

	private static final File LAYOUTS = new File("src/main/res/layout");
	private static final double MIN_TARGET_DP = 48;
	private static final Set<String> LABELLED_IN_CODE = new HashSet<>(
			Arrays.asList("list_item_my_sticker.xml#ImageView"));
	private static final Set<String> HINT_IN_CODE = new HashSet<>(Arrays.asList(
			"dialog_add_profile.xml#@+id/profile_password_input",
			"dialog_add_profile.xml#@+id/profile_password_confirm_input"));
	private static final String[][] TARGETS = {
			{"view_composite_send_button.xml", "@+id/attachmentButton", "both"},
			{"view_composite_send_button.xml", "@+id/voiceButton", "both"},
			{"view_composite_send_button.xml", "@+id/sendButton", "both"},
			{"reply_preview.xml", "@+id/cancel_reply", "both"},
			{"emoji_text_input_view.xml", "@+id/emoji_toggle", "both"},
			{"voice_recording_overlay.xml", "@+id/cancel_recording_button",
					"both"},
			{"voice_recording_overlay.xml", "@+id/send_voice_button", "both"},
			{"activity_grouptr_conversation.xml", "@+id/attachmentButton",
					"both"},
			{"activity_grouptr_conversation.xml", "@+id/voiceButton", "both"},
			{"activity_grouptr_conversation.xml", "@+id/sendButton", "both"},
			{"activity_grouptr_conversation.xml", "@+id/recordingCancel",
					"both"},
			{"activity_grouptr_conversation.xml", "@+id/recordingSend", "both"},
			{"list_item_grouptr_voice_in.xml", "@+id/voicePlayButton", "both"},
			{"list_item_grouptr_voice_out.xml", "@+id/voicePlayButton", "both"},
			{"list_item_grouptr_post_in.xml", "@+id/quoteBlock", "height"},
			{"list_item_grouptr_post_out.xml", "@+id/quoteBlock", "height"},
			{"list_item_grouptr_admin_member.xml", "@+id/memberMenuButton",
					"both"},
			{"activity_grouptr_admin.xml", "@+id/membersMayInviteSwitch",
					"height"},
			{"activity_grouptr_admin.xml", "@+id/actionInviteContacts",
					"height"},
			{"activity_grouptr_admin.xml", "@+id/rowIncognito", "height"},
			{"list_item_grouptr_admin_member.xml",
					"@+id/memberMayAddSwitch", "height"},
			{"view_grouptr_incognito.xml", "@+id/incognitoSwitch", "height"},
			{"view_grouptr_incognito.xml", "@+id/incognitoNewName",
					"height"},
			{"activity_channel_feed.xml", "@+id/channelPinnedBannerClose",
					"both"},
			{"activity_channel_feed.xml", "@+id/channelComposeTtlButton",
					"both"},
			{"activity_channel_feed.xml", "@+id/channelComposeAttachButton",
					"both"},
			{"list_item_channel_post.xml", "@+id/channelPostCommentBadge",
					"both"},
			{"dialog_sticker_picker.xml", "@+id/importStickerButton", "both"},
			{"dialog_sticker_picker.xml", "@+id/tabStandard", "height"},
			{"dialog_sticker_picker.xml", "@+id/tabMine", "height"},
			{"list_item_conversation_request.xml", "@+id/acceptButton",
					"height"},
			{"list_item_conversation_request.xml", "@+id/declineButton",
					"height"},
			{"fragment_donation_dialog.xml", "@+id/buttonOpenInBrowser",
					"height"},
			{"fragment_donation_dialog.xml", "@+id/buttonLater", "height"},
			{"power_view.xml", "@+id/helpButton", "both"},
			{"fragment_vault_wallet.xml", "@+id/wallet_back", "both"},
			{"fragment_vault_wallet.xml", "@+id/wallet_settings", "both"},
			{"fragment_xmr_detail.xml", "@+id/xmr_back", "both"},
			{"fragment_xmr_detail.xml", "@+id/xmr_settings", "both"},
			{"fragment_xmr_list.xml", "@+id/xmr_list_back", "both"},
			{"fragment_xmr_list.xml", "@+id/xmr_list_settings", "both"},
			{"fragment_xmr_recovery.xml", "@+id/recovery_back", "both"},
			{"fragment_settings_main.xml", "@+id/change_avatar_button",
					"height"},
	};
	private static final String[] GROUP_POSTS_IN = {
			"list_item_grouptr_post_in.xml", "list_item_grouptr_image_in.xml",
			"list_item_grouptr_video_in.xml", "list_item_grouptr_voice_in.xml"
	};

	private static Element parse(File f) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(f).getDocumentElement();
	}

	private static List<Element> all(Element root) {
		List<Element> out = new ArrayList<>();
		out.add(root);
		NodeList nodes = root.getElementsByTagName("*");
		for (int i = 0; i < nodes.getLength(); i++) {
			out.add((Element) nodes.item(i));
		}
		return out;
	}

	private static String tag(Element e) {
		return e.getTagName().substring(e.getTagName().lastIndexOf('.') + 1);
	}

	private static File[] layouts() {
		File[] files = LAYOUTS.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(files);
		Arrays.sort(files);
		return files;
	}

	private static double dp(String v) {
		Matcher m = Pattern.compile("(-?[\\d.]+)dp").matcher(v);
		return m.matches() ? Double.parseDouble(m.group(1)) : -1;
	}

	@Test
	public void iconOnlyControlsHaveLabels() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : layouts()) {
			for (Element e : all(parse(f))) {
				String tag = tag(e);
				boolean clickable = "true".equals(e.getAttribute(
						"android:clickable")) || "true".equals(
						e.getAttribute("android:focusable"));
				boolean iconOnly = tag.equals("ImageButton")
						|| tag.equals("AppCompatImageButton")
						|| tag.equals("FloatingActionButton")
						|| (tag.endsWith("ImageView") && clickable)
						|| (tag.equals("MaterialButton")
						&& e.hasAttribute("app:icon")
						&& !e.hasAttribute("android:text"));
				if (!iconOnly) continue;
				String hidden = e.getAttribute("android:importantForAccessibility");
				if (hidden.equals("no") || hidden.equals("noHideDescendants")) {
					continue;
				}
				String id = e.hasAttribute("android:id")
						? e.getAttribute("android:id") : tag;
				checked++;
				if (LABELLED_IN_CODE.contains(f.getName() + "#" + id)) continue;
				String label = e.getAttribute("android:contentDescription");
				if (label.isEmpty() || label.equals("@null")) {
					problems.add(f.getName() + " " + id + " has no label");
				}
			}
		}
		assertTrue("checked " + checked, checked > 50);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void passwordFieldsStayPrivateAndLabelled() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : layouts()) {
			for (Element e : all(parse(f))) {
				if (!e.getAttribute("android:inputType").contains("Password")) {
					continue;
				}
				checked++;
				String id = f.getName() + "#" + e.getAttribute("android:id");
				String autofill = e.getAttribute("android:importantForAutofill");
				if (!autofill.equals("no")
						&& !autofill.equals("noExcludeDescendants")) {
					problems.add(id + " allows autofill");
				}
				if (e.hasAttribute("android:contentDescription")) {
					problems.add(id + " has a content description");
				}
				boolean labelled = e.hasAttribute("android:hint");
				if (e.getParentNode() instanceof Element) {
					Element parent = (Element) e.getParentNode();
					if (tag(parent).equals("TextInputLayout")
							&& parent.hasAttribute("android:hint")) {
						labelled = true;
					}
				}
				if (!labelled && !HINT_IN_CODE.contains(id)) {
					problems.add(id + " has no label");
				}
			}
		}
		assertTrue("checked " + checked, checked >= 20);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void inputErrorsAreAnnounced() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : layouts()) {
			for (Element e : all(parse(f))) {
				if (!"true".equals(e.getAttribute("app:errorEnabled"))) continue;
				checked++;
				if (!"1".equals(e.getAttribute(
						"app:errorAccessibilityLiveRegion"))) {
					problems.add(f.getName() + " "
							+ e.getAttribute("android:id")
							+ " does not announce errors");
				}
			}
		}
		assertTrue("checked " + checked, checked >= 10);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void touchTargetsAreAtLeast48dp() throws Exception {
		List<String> problems = new ArrayList<>();
		for (String[] t : TARGETS) {
			Element found = null;
			for (Element e : all(parse(new File(LAYOUTS, t[0])))) {
				if (t[1].equals(e.getAttribute("android:id"))) found = e;
			}
			assertNotNull(t[0] + " " + t[1], found);
			double height = Math.max(dp(found.getAttribute(
					"android:layout_height")), dp(found.getAttribute(
					"android:minHeight")));
			double width = Math.max(dp(found.getAttribute(
					"android:layout_width")), dp(found.getAttribute(
					"android:minWidth")));
			if (height < MIN_TARGET_DP) {
				problems.add(t[0] + " " + t[1] + " is " + height + "dp high");
			}
			if (t[2].equals("both") && width < MIN_TARGET_DP) {
				problems.add(t[0] + " " + t[1] + " is " + width + "dp wide");
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void switchesHaveLabels() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : layouts()) {
			for (Element e : all(parse(f))) {
				if (!tag(e).endsWith("Switch") && !tag(e).endsWith("SwitchMaterial")
						&& !tag(e).endsWith("SwitchCompat")) {
					continue;
				}
				String hidden = e.getAttribute("android:importantForAccessibility");
				if (hidden.equals("no") || hidden.equals("noHideDescendants")) {
					continue;
				}
				if ("false".equals(e.getAttribute("android:focusable"))
						&& "false".equals(e.getAttribute("android:clickable"))) {
					continue;
				}
				checked++;
				if (e.getAttribute("android:text").isEmpty()
						&& e.getAttribute("android:contentDescription").isEmpty()) {
					problems.add(f.getName() + " " + e.getAttribute("android:id")
							+ " has no label");
				}
			}
		}
		assertTrue("checked " + checked, checked > 0);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theChatComposerHasAHint() throws Exception {
		Element found = null;
		for (Element e : all(parse(new File(LAYOUTS,
				"activity_conversation.xml")))) {
			if ("@+id/text_input_container".equals(e.getAttribute("android:id"))) {
				found = e;
			}
		}
		assertNotNull(found);
		assertEquals("@string/message_hint", found.getAttribute("app:hint"));
	}

	private static String themeItem(String theme, String name)
			throws Exception {
		NodeList styles = parse(new File("src/main/res/values/themes.xml"))
				.getElementsByTagName("style");
		for (int i = 0; i < styles.getLength(); i++) {
			Element s = (Element) styles.item(i);
			if (!s.getAttribute("name").equals(theme)) continue;
			NodeList items = s.getElementsByTagName("item");
			for (int j = 0; j < items.getLength(); j++) {
				Element item = (Element) items.item(j);
				if (item.getAttribute("name").equals(name)) {
					return item.getTextContent().trim();
				}
			}
		}
		return "";
	}

	@Test
	public void theOverflowButtonIsAtLeast48dp() throws Exception {
		assertEquals("@style/Zerion.ActionButton.Overflow",
				themeItem("ZerionTheme", "actionOverflowButtonStyle"));
		Element style = null;
		Element toolbarStyle = null;
		NodeList styles = parse(new File("src/main/res/values/styles.xml"))
				.getElementsByTagName("style");
		for (int i = 0; i < styles.getLength(); i++) {
			Element s = (Element) styles.item(i);
			if (s.getAttribute("name").equals("Zerion.ActionButton.Overflow")) {
				style = s;
			}
			if (s.getAttribute("name").equals("OverflowButtonStyle")) {
				toolbarStyle = s;
			}
		}
		assertNotNull(style);
		assertNotNull(toolbarStyle);
		assertEquals("Zerion.ActionButton.Overflow",
				toolbarStyle.getAttribute("parent"));
		double width = -1;
		double height = -1;
		NodeList items = style.getElementsByTagName("item");
		for (int i = 0; i < items.getLength(); i++) {
			Element item = (Element) items.item(i);
			String value = item.getTextContent().trim();
			if (item.getAttribute("name").equals("android:minWidth")) {
				width = dp(value);
			} else if (item.getAttribute("name").equals("android:minHeight")) {
				height = dp(value);
			}
		}
		assertTrue("width " + width, width >= MIN_TARGET_DP);
		assertTrue("height " + height, height >= MIN_TARGET_DP);
	}

	@Test
	public void groupPostRowsAreAtLeast48dpHigh() throws Exception {
		for (String name : new String[] {"list_item_grouptr_post_in.xml",
				"list_item_grouptr_post_out.xml"}) {
			Element root = parse(new File(LAYOUTS, name));
			assertTrue(name, dp(root.getAttribute("android:minHeight"))
					>= MIN_TARGET_DP);
		}
	}

	@Test
	public void groupSenderNamesHaveARoomyTarget() throws Exception {
		for (String name : GROUP_POSTS_IN) {
			Element found = null;
			for (Element e : all(parse(new File(LAYOUTS, name)))) {
				if ("@+id/senderName".equals(e.getAttribute("android:id"))) {
					found = e;
				}
			}
			assertNotNull(name, found);
			double padding = dp(found.getAttribute("android:paddingTop"))
					+ dp(found.getAttribute("android:paddingBottom"));
			assertTrue(name + " pads its sender name by " + padding + "dp",
					padding >= 16);
		}
	}

	@Test
	public void emojiToggleComesBeforeTheComposerText() throws Exception {
		List<Element> elements = all(parse(new File(LAYOUTS,
				"emoji_text_input_view.xml")));
		int toggle = -1;
		int input = -1;
		for (int i = 0; i < elements.size(); i++) {
			String id = elements.get(i).getAttribute("android:id");
			if (id.equals("@+id/emoji_toggle")) toggle = i;
			if (id.equals("@+id/input_text")) input = i;
		}
		assertTrue(toggle >= 0 && input >= 0 && toggle < input);
		assertEquals("@+id/input_text", elements.get(toggle)
				.getAttribute("android:accessibilityTraversalBefore"));
	}
}
