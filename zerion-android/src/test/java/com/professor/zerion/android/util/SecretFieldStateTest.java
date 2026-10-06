package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

public class SecretFieldStateTest {

	@Test
	public void everyPasswordFieldInTheLayoutsOptsOut() throws Exception {
		Pattern tag = Pattern.compile("<[A-Za-z][^<>]*?>", Pattern.DOTALL);
		File[] layouts = new File("src/main/res/layout").listFiles();
		assertTrue(layouts != null && layouts.length > 0);
		for (File f : layouts) {
			if (f.getName().contains("xmr") || f.getName().contains("btc")
					|| f.getName().contains("wallet")) {
				continue;
			}
			String s = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8);
			Matcher m = tag.matcher(s);
			while (m.find()) {
				String t = m.group();
				if (t.matches("(?s).*android:inputType=\"[^\"]*[Pp]assword[^\"]*\".*")) {
					assertTrue(f.getName() + ": " + t,
							t.contains("android:saveEnabled=\"false\""));
				}
			}
		}
	}

	@Test
	public void theSharedPasswordHelperOptsOut() throws Exception {
		String src = new String(Files.readAllBytes(new File(
				"src/main/java/com/professor/zerion/android/vault/ui/"
						+ "IncognitoInputHelper.java").toPath()),
				StandardCharsets.UTF_8);
		int i = src.indexOf("public static void configurePasswordField(");
		String body = src.substring(i, src.indexOf("}", i));
		assertTrue(body.contains("editText.setSaveEnabled(false);"));
	}
}
