package com.professor.zerion.android;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ComposerThemeTest {

	private static final String[] COMPOSERS = {
			"src/main/res/layout/emoji_text_input_view.xml",
			"src/main/res/layout/activity_grouptr_conversation.xml"};
	private static final Pattern EDIT = Pattern.compile(
			"<com\\.vanniktech\\.emoji\\.EmojiEditText(.*?)/>", Pattern.DOTALL);

	@Test
	public void everyWritingBoxTakesItsTextAndHintColorsFromTheTheme()
			throws Exception {
		for (String path : COMPOSERS) {
			String s = new String(Files.readAllBytes(new File(path).toPath()),
					StandardCharsets.UTF_8);
			Matcher m = EDIT.matcher(s);
			int found = 0;
			while (m.find()) {
				found++;
				String attrs = m.group(1);
				assertTrue(path, attrs.contains(
						"android:textColor=\"?attr/zerionColorOnSurface\""));
				assertTrue(path, attrs.contains(
						"android:textColorHint=\"?attr/zerionColorOnSurfaceMuted\""));
			}
			assertEquals(path, 1, found);
		}
	}
}
