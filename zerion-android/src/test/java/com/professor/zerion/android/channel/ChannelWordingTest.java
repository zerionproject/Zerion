package com.professor.zerion.android.channel;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelWordingTest {

	private static final File SRC = new File("src");
	private static final File RES = new File("src/main/res");
	private static final File CHANNEL_CODE = new File(
			"src/main/java/com/professor/zerion/android/channel");

	private static final List<String> TECHNICAL_WORDS = Arrays.asList(
			"closed channel", "onion", "epoch", "checkpoint", "manifest",
			"capabilit", "client authorization", "address", "base32",
			"rotat", "publisher");

	private static final String KEY_WORD = "\\bkeys?\\b";

	private static final List<String> PRESENCE_WORDS = Arrays.asList(
			"online", "owner's device");

	private static final List<String> FORM_WORDS = Arrays.asList(
			"apply", "application");

	private static final Map<String, String> ADVANCED_SCREEN_KEYS =
			Collections.emptyMap();

	private static final List<String> RETIRED_KEYS = Arrays.asList(
			"channels_publisher_offline_note",
			"channels_action_mirror_on",
			"channels_action_mirror_off",
			"channels_row_active",
			"channels_ttl_label",
			"channels_ttl_post_label",
			"channels_post_signer_owner",
			"channels_delegations_role_editor",
			"channels_ttl_picker_title",
			"channels_delegations_from_subscribers",
			"channels_attach_uploading",
			"channels_attach_downloading",
			"channels_comments_count",
			"channels_comments_count_one",
			"grouptr_detail_epoch",
			"grouptr_creator_tag",
			"channels_row_closed",
			"channels_row_publisher_self",
			"channels_create_public_label",
			"channels_create_closed_label",
			"channels_create_require_approval",
			"channels_join_confirm",
			"channels_action_rotate_capability",
			"channels_invite_rotate_confirm_title",
			"channels_invite_rotate_confirm_message",
			"channels_subscribers_ban_rotate",
			"channels_delete_confirm_message",
			"channels_editor_post_failed",
			"channels_delegations_help",
			"channels_delegations_paste_pubkey",
			"channels_apply_title",
			"channels_apply_message",
			"channels_apply_action",
			"channels_apply_failed",
			"channels_apply_unconfirmed",
			"channels_apply_banner",
			"channels_approval_pending_banner",
			"channels_approval_denied_banner",
			"channels_rejoin_apply_again",
			"channels_rejoin_apply_again_unreachable",
			"channels_pending_title",
			"channels_pending_empty",
			"channels_announce_message",
			"channels_row_public",
			"channels_pending_count",
			"channels_delegations_paste_editor_key",
			"channels_delegations_paste_key",
			"channels_delegations_error_key",
			"channels_action_copy_my_key",
			"channels_my_key_copied",
			"channels_delete_confirm_title",
			"channels_delete_channel_confirm_message",
			"channels_delete_confirm_action",
			"channels_action_delete_channel",
			"channels_discussions_menu_enable",
			"channels_discussions_menu_disable",
			"channels_delegations_help_text",
			"channels_delegations_revoke",
			"channels_comment_ban_author",
			"channels_comment_ban_confirm",
			"channels_ban_failed",
			"channels_subscribers_ban",
			"channels_subscribers_ban_confirm",
			"channels_subscribers_trust",
			"channels_subscribers_untrust",
			"channels_subscribers_ban_renew_link",
			"channels_subscribers_ban_renew_link_approval",
			"channels_subscribers_banned_label",
			"channels_subscribers_unban",
			"channels_subscribers_unban_confirm",
			"channels_unban_failed",
			"channels_subscribers_title",
			"channels_subscribers_empty",
			"channels_subscribers_count",
			"channels_delegations_validity_label",
			"channels_delegations_row_detail",
			"channels_comment_anonymous",
			"channels_comments_action");

	private static Map<String, String> englishTexts() throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		File[] files = new File(RES, "values")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertTrue(files != null && files.length > 0);
		for (File f : files) {
			Element root = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder().parse(f).getDocumentElement();
			NodeList nodes = root.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node n = nodes.item(i);
				if (!(n instanceof Element)) continue;
				Element e = (Element) n;
				String tag = e.getTagName();
				if (tag.equals("string") || tag.equals("plurals")) {
					out.put(e.getAttribute("name"), e.getTextContent());
				}
			}
		}
		return out;
	}

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	private static boolean isChannelKey(String key) {
		return key.startsWith("channels_") || key.startsWith("channel_");
	}

	private static Set<String> keysUsedByChannelCode() throws Exception {
		Set<String> keys = new TreeSet<>();
		File[] files = CHANNEL_CODE.listFiles((d, n) -> n.endsWith(".java"));
		assertTrue(files != null && files.length > 0);
		Pattern ref = Pattern.compile("R\\.(?:string|plurals)\\.(\\w+)");
		for (File f : files) {
			Matcher m = ref.matcher(read(f));
			while (m.find()) keys.add(m.group(1));
		}
		return keys;
	}

	private static Set<String> channelScreenKeys() throws Exception {
		Set<String> shown = new TreeSet<>(keysUsedByChannelCode());
		for (String key : englishTexts().keySet()) {
			if (isChannelKey(key)) shown.add(key);
		}
		return shown;
	}

	private static Set<String> groupScreenKeys() throws Exception {
		Set<String> shown = new TreeSet<>();
		for (String key : englishTexts().keySet()) {
			if (key.startsWith("grouptr_")) shown.add(key);
		}
		return shown;
	}

	private static List<String> problemsIn(String key, String text,
			List<String> words) {
		List<String> problems = new ArrayList<>();
		String lower = text.replace("\\", "").toLowerCase(Locale.ROOT);
		for (String word : words) {
			if (Pattern.compile(word).matcher(lower).find()) {
				problems.add(key + " says \"" + word + "\": " + text);
			}
		}
		return problems;
	}

	private static List<String> textsSaying(Set<String> keys,
			List<String> words) throws Exception {
		Map<String, String> english = englishTexts();
		List<String> problems = new ArrayList<>();
		for (String key : keys) {
			String text = english.get(key);
			if (text != null) problems.addAll(problemsIn(key, text, words));
		}
		return problems;
	}

	static List<String> technicalWordProblems(Map<String, String> texts,
			Set<String> keys, Map<String, String> keyWordAllowed) {
		List<String> problems = new ArrayList<>();
		for (String key : keys) {
			String text = texts.get(key);
			if (text == null) continue;
			List<String> words = new ArrayList<>(TECHNICAL_WORDS);
			if (!keyWordAllowed.containsKey(key)) words.add(KEY_WORD);
			problems.addAll(problemsIn(key, text, words));
		}
		return problems;
	}

	@Test
	public void channelScreensUseEverydayWords() throws Exception {
		List<String> problems = technicalWordProblems(englishTexts(),
				channelScreenKeys(), ADVANCED_SCREEN_KEYS);
		problems.addAll(textsSaying(groupScreenKeys(), TECHNICAL_WORDS));
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theAllowlistOnlyExcusesTheWordKey() {
		Map<String, String> texts = new LinkedHashMap<>();
		texts.put("channels_probe", "Paste the onion key");
		Map<String, String> allowed = Collections.singletonMap(
				"channels_probe", "An owner screen that has to say key.");
		List<String> problems =
				technicalWordProblems(texts, texts.keySet(), allowed);
		assertEquals(String.join("\n", problems), 1, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("onion"));
		texts.put("channels_probe", "Paste the key");
		assertEquals(0, technicalWordProblems(texts, texts.keySet(),
				allowed).size());
		assertEquals(1, technicalWordProblems(texts, texts.keySet(),
				Collections.<String, String>emptyMap()).size());
	}

	@Test
	public void theMemberIdHasOnePlainName() throws Exception {
		List<String> problems = textsSaying(channelScreenKeys(),
				Collections.singletonList("private id"));
		assertEquals(String.join("\n", problems), 0, problems.size());
		Map<String, String> english = englishTexts();
		String announce = english.get("channels_announce_message_text");
		assertTrue(String.valueOf(announce), announce != null
				&& announce.contains("an ID used only in this channel")
				&& announce.contains("this name and that ID to everyone"
						+ " who reads the discussion"));
		assertEquals("Copy my channel ID",
				english.get("channels_action_copy_my_id"));
		assertTrue(String.valueOf(english.get("channels_my_id_copied")),
				String.valueOf(english.get("channels_my_id_copied"))
						.startsWith("Channel ID copied."));
		assertEquals("Paste the editor's channel ID", String.valueOf(
				english.get("channels_delegations_paste_editor_id"))
				.replace("\\", ""));
	}

	@Test
	public void advancedScreenKeysAreRealAndJustified() throws Exception {
		Map<String, String> english = englishTexts();
		for (Map.Entry<String, String> e : ADVANCED_SCREEN_KEYS.entrySet()) {
			assertTrue(e.getKey(), english.containsKey(e.getKey()));
			assertTrue(e.getKey(), e.getValue().length() > 20);
		}
	}

	@Test
	public void editorsHelpSaysWhatEditorsMayDo() throws Exception {
		String help = englishTexts().get("channels_delegations_help_sheet");
		assertTrue(String.valueOf(help), help != null
				&& help.contains("edit or delete their own posts")
				&& help.contains("delete other posts"));
	}

	@Test
	public void channelScreensNeverHintWhetherTheOwnerIsOnline()
			throws Exception {
		List<String> problems = textsSaying(channelScreenKeys(),
				PRESENCE_WORDS);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void accessIsRequestedNotAppliedFor() throws Exception {
		List<String> problems = textsSaying(channelScreenKeys(), FORM_WORDS);
		assertEquals(String.join("\n", problems), 0, problems.size());
		Map<String, String> english = englishTexts();
		assertEquals("Request access",
				english.get("channels_request_access_title"));
		assertTrue(String.valueOf(english.get(
				"channels_waiting_for_approval_banner")),
				String.valueOf(english.get(
						"channels_waiting_for_approval_banner"))
						.startsWith("Waiting for approval"));
	}

	static boolean isTestSourceSet(String name) {
		return name.startsWith("test") || name.startsWith("androidTest");
	}

	@Test
	public void testSourceSetsDoNotCountAsShown() {
		assertTrue(isTestSourceSet("test"));
		assertTrue(isTestSourceSet("androidTest"));
		assertTrue(isTestSourceSet("androidTestOfficial"));
		assertTrue(isTestSourceSet("androidTestScreenshot"));
		assertTrue(!isTestSourceSet("main"));
		assertTrue(!isTestSourceSet("debug"));
		assertTrue(!isTestSourceSet("screenshotDebug"));
	}

	private static void collectSources(File dir, StringBuilder out)
			throws Exception {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File c : children) {
			if (c.isDirectory()) {
				if (c.getName().startsWith("values")) continue;
				if (dir.equals(SRC) && isTestSourceSet(c.getName())) continue;
				collectSources(c, out);
			} else if (c.getName().endsWith(".java")
					|| c.getName().endsWith(".kt")
					|| c.getName().endsWith(".xml")) {
				out.append(read(c)).append('\n');
			}
		}
	}

	@Test
	public void everyChannelStringIsShownSomewhere() throws Exception {
		StringBuilder sources = new StringBuilder();
		collectSources(SRC, sources);
		String all = sources.toString();
		List<String> unused = new ArrayList<>();
		for (String key : englishTexts().keySet()) {
			if (!isChannelKey(key)) continue;
			if (!Pattern.compile("\\b" + key + "\\b").matcher(all).find()) {
				unused.add(key);
			}
		}
		assertEquals(String.join("\n", unused), 0, unused.size());
	}

	@Test
	public void retiredStringsAreGoneFromEveryLanguage() throws Exception {
		File[] folders = RES.listFiles((d, n) -> n.startsWith("values"));
		assertTrue(folders != null && folders.length >= 40);
		List<String> problems = new ArrayList<>();
		for (File folder : folders) {
			File strings = new File(folder, "strings.xml");
			if (!strings.isFile()) continue;
			String text = read(strings);
			for (String key : RETIRED_KEYS) {
				if (text.contains("name=\"" + key + "\"")) {
					problems.add(folder.getName() + " still has " + key);
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theTwoChannelKindsReadDifferently() throws Exception {
		Map<String, String> english = englishTexts();
		String open = english.get("channels_create_public_option");
		String invited = english.get("channels_create_private_option");
		assertTrue(open != null && invited != null);
		assertTrue(open, open.startsWith("Public:"));
		assertTrue(invited, invited.startsWith("Private:"));
		assertTrue(invited, invited.contains("invite link"));
		assertTrue(invited, !invited.contains("people you invite"));
		assertTrue(invited, invited.contains("renew"));
		assertTrue(open, !open.contains("invite"));
		String approval = english.get("channels_create_approval_required");
		assertTrue(String.valueOf(approval), approval != null
				&& approval.startsWith(
						english.get("channels_row_approval_required")));
	}
}
