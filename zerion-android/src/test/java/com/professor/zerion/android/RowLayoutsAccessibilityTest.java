package com.professor.zerion.android;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilderFactory;

import androidx.core.widget.TextViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RowLayoutsAccessibilityTest {

	private static final File LAYOUTS = new File("src/main/res/layout");
	private static final Pattern FIXED = Pattern.compile("[1-9][\\d.]*dp");
	private static final String[] KEY_ROWS = {
			"list_item_home_row.xml", "list_item_home_invite.xml",
			"list_item_home_notice.xml",
			"list_item_conversation_msg_in.xml",
			"list_item_conversation_msg_in_content.xml",
			"list_item_conversation_msg_out.xml",
			"list_item_conversation_secret_in.xml",
			"list_item_conversation_secret_out.xml",
			"list_item_conversation_notice_in.xml",
			"list_item_conversation_notice_out.xml",
			"list_item_conversation_top_notice_in.xml",
			"list_item_conversation_top_notice_out.xml",
			"list_item_grouptr_post_in.xml", "list_item_grouptr_post_out.xml",
			"list_item_grouptr_image_in.xml",
			"list_item_grouptr_image_out.xml",
			"list_item_grouptr_voice_in.xml",
			"list_item_grouptr_voice_out.xml",
			"list_item_grouptr_video_in.xml",
			"list_item_grouptr_video_out.xml",
			"list_item_channel_post.xml", "list_item_channel_comment.xml",
			"list_item_channel_attachment.xml", "list_item_image.xml",
			"view_selection_action.xml", "view_selection_title.xml",
			"view_composite_send_button.xml", "emoji_text_input_view.xml",
			"reply_preview.xml", "view_message_not_sent_actions.xml",
			"voice_message_view.xml", "voice_message_view_out.xml",
			"view_link_preview.xml", "view_link_preview_out.xml",
			"activity_grouptr_conversation.xml", "activity_channel_feed.xml",
			"activity_channel_comments.xml", "activity_conversation.xml"
	};
	private static final Set<String> IMAGES = new HashSet<>(Arrays.asList(
			"ImageView", "AppCompatImageView", "ShapeableImageView"));
	private static final Set<String> LABELLED_IN_CODE = new HashSet<>(
			Arrays.asList("@+id/bomb", "@+id/status",
					"list_item_image.xml#@+id/imageView"));

	private static Element parse(File f) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(f).getDocumentElement();
	}

	private static String tag(Element e) {
		return e.getTagName().substring(e.getTagName().lastIndexOf('.') + 1);
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

	private static boolean hiddenAbove(Element e) {
		for (Node n = e.getParentNode(); n instanceof Element;
				n = n.getParentNode()) {
			if ("noHideDescendants".equals(((Element) n).getAttribute(
					"android:importantForAccessibility"))) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static String fixedAbove(Element e) {
		for (Node n = e.getParentNode(); n instanceof Element;
				n = n.getParentNode()) {
			String h = ((Element) n).getAttribute("android:layout_height");
			if (FIXED.matcher(h).matches()) return h;
		}
		return null;
	}

	private static boolean showsText(Element e) {
		String t = tag(e);
		if (t.equals("MaterialButton") || t.equals("Button")) {
			return e.hasAttribute("android:text");
		}
		return t.endsWith("TextView") || t.endsWith("EditText")
				|| t.equals("Chip");
	}

	@Test
	public void decorativeRowImagesAreSkippedByScreenReaders()
			throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (String name : KEY_ROWS) {
			for (Element e : all(parse(new File(LAYOUTS, name)))) {
				if (!IMAGES.contains(tag(e))) continue;
				checked++;
				String id = e.getAttribute("android:id");
				String label = e.getAttribute("android:contentDescription");
				boolean labelled = !label.isEmpty() && !label.equals("@null");
				boolean clickable = "true".equals(e.getAttribute(
						"android:clickable"));
				if (labelled || clickable || hiddenAbove(e)
						|| LABELLED_IN_CODE.contains(id)
						|| LABELLED_IN_CODE.contains(name + "#" + id)) {
					continue;
				}
				if (!"no".equals(e.getAttribute(
						"android:importantForAccessibility"))) {
					problems.add(name + " " + id + " is not marked decorative");
				}
			}
		}
		assertTrue("checked " + checked, checked >= 20);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void rowTextIsNeverHeldInAFixedHeight() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (String name : KEY_ROWS) {
			for (Element e : all(parse(new File(LAYOUTS, name)))) {
				if (!showsText(e)) continue;
				checked++;
				String id = name + " " + e.getAttribute("android:id");
				boolean fits = "uniform".equals(e.getAttribute(
						"android:autoSizeTextType"));
				String h = e.getAttribute("android:layout_height");
				if (FIXED.matcher(h).matches() && !fits) {
					problems.add(id + " is " + h + " high");
				}
				String above = fixedAbove(e);
				if (above != null && !fits) {
					problems.add(id + " sits in a " + above + " box");
				}
				String size = e.getAttribute("android:textSize");
				if (!size.isEmpty() && !size.endsWith("sp")) {
					problems.add(id + " has a text size of " + size);
				}
			}
		}
		assertTrue("checked " + checked, checked >= 60);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static Context scaled(float fontScale) {
		Context app = RuntimeEnvironment.getApplication();
		Configuration c = new Configuration(app.getResources()
				.getConfiguration());
		c.fontScale = fontScale;
		return new ContextThemeWrapper(app.createConfigurationContext(c),
				R.style.ZerionTheme);
	}

	private static void texts(View v, List<TextView> out) {
		if (v instanceof TextView && !(v instanceof Button
				&& ((TextView) v).getText().length() == 0)) {
			out.add((TextView) v);
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				texts(g.getChildAt(i), out);
			}
		}
	}

	private static boolean fixedHeight(View v) {
		for (View at = v; at != null; at = at.getParent() instanceof View
				? (View) at.getParent() : null) {
			ViewGroup.LayoutParams lp = at.getLayoutParams();
			if (lp != null && lp.height > 0) return true;
		}
		return false;
	}

	@Test
	public void keyRowsGrowWithTheLargestFontInsteadOfClipping() {
		int[] rows = {R.layout.list_item_home_row,
				R.layout.list_item_home_invite,
				R.layout.list_item_conversation_msg_out,
				R.layout.list_item_grouptr_post_in,
				R.layout.list_item_channel_post,
				R.layout.list_item_channel_comment,
				R.layout.view_selection_action,
				R.layout.view_message_not_sent_actions};
		Context normal = scaled(1.0f);
		Context large = scaled(2.0f);
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (int row : rows) {
			ViewGroup parent = new android.widget.FrameLayout(large);
			View big = LayoutInflater.from(large).inflate(row, parent, false);
			View small = LayoutInflater.from(normal).inflate(row,
					new android.widget.FrameLayout(normal), false);
			List<TextView> bigTexts = new ArrayList<>();
			List<TextView> smallTexts = new ArrayList<>();
			texts(big, bigTexts);
			texts(small, smallTexts);
			assertEquals(bigTexts.size(), smallTexts.size());
			for (int i = 0; i < bigTexts.size(); i++) {
				TextView b = bigTexts.get(i);
				TextView s = smallTexts.get(i);
				checked++;
				String where = big.getResources().getResourceEntryName(row)
						+ " " + (b.getId() == View.NO_ID ? "text"
						: b.getResources().getResourceEntryName(b.getId()));
				boolean fits = TextViewCompat.getAutoSizeTextType(b)
						== TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM;
				if (fixedHeight(b) && !fits) {
					problems.add(where + " is held in a fixed height");
				}
				if (!fits && Math.abs(b.getTextSize()
						- 2f * s.getTextSize()) > 0.5f) {
					problems.add(where + " does not grow with the font: "
							+ s.getTextSize() + " to " + b.getTextSize());
				}
			}
		}
		assertTrue("checked " + checked, checked >= 25);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theCallStatusIsAPoliteLiveRegionSetOnlyWhenItChanges()
			throws Exception {
		Element status = null;
		for (Element e : all(parse(new File(LAYOUTS,
				"activity_voice_call.xml")))) {
			if ("@+id/call_status".equals(e.getAttribute("android:id"))) {
				status = e;
			}
		}
		assertTrue(status != null);
		assertEquals("polite", status.getAttribute(
				"android:accessibilityLiveRegion"));
		String src = new String(Files.readAllBytes(new File(
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "voice/VoiceCallActivity.java").toPath()),
				StandardCharsets.UTF_8);
		assertFalse("every status goes through the change check",
				src.contains("callStatusText.setText("));
		assertTrue(src.contains("Speech.setText(callStatusText, text)"));
		Element duration = null;
		for (Element e : all(parse(new File(LAYOUTS,
				"activity_voice_call.xml")))) {
			if ("@+id/call_duration".equals(e.getAttribute("android:id"))) {
				duration = e;
			}
		}
		assertTrue(duration != null);
		assertFalse("the ticking clock is never read out on its own",
				duration.hasAttribute("android:accessibilityLiveRegion"));
	}
}
