package org.zerionproject.core.api.identity;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@NotNullByDefault
public final class ReservedNames {

	public static final List<String> RESERVED = Collections.unmodifiableList(
			Arrays.asList(
					"zerion",
					"zerionchat",
					"zerion-chat",
					"zerion-team",
					"zerion-app",
					"zerionapp",
					"zerion-official",
					"zerionofficial",
					"zerion-support",
					"zerionsupport",
					"zerion-admin",
					"zerionadmin",
					"zerion-help",
					"zerionhelp",
					"zerion-security",
					"zerionsecurity",
					"zerion-bot",
					"zerionbot",
					"support",
					"admin",
					"administrator",
					"moderator",
					"helpdesk",
					"official",
					"staff",
					"security",
					"team",
					"system",
					"root"
			));

	private ReservedNames() {}

	public static boolean isReserved(String name) {
		String key = canonicalize(name);
		if (key.isEmpty()) return false;
		if (key.startsWith("zerion")) return true;
		for (String reserved : RESERVED) {
			if (key.equals(canonicalize(reserved))) return true;
		}
		return false;
	}

	private static String canonicalize(String s) {
		String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
		StringBuilder out = new StringBuilder(n.length());
		for (int i = 0; i < n.length(); ) {
			int cp = n.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isLetterOrDigit(cp)) {
				out.appendCodePoint(Character.toLowerCase(cp));
			}
		}
		return out.toString();
	}
}
