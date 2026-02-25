package com.professor.zerion.android.conversation.linkpreview;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
class OpenGraphParser {

	@Nullable
	private String title;
	@Nullable
	private String description;
	@Nullable
	private String imageUrl;

	void parse(String html) {
		title = extractMeta(html, "og:title");
		if (title == null) title = extractMeta(html, "twitter:title");
		if (title == null) title = extractHtmlTitle(html);

		description = extractMeta(html, "og:description");
		if (description == null) {
			description = extractMeta(html, "twitter:description");
		}
		if (description == null) {
			description = extractMeta(html, "description");
		}

		imageUrl = extractMeta(html, "og:image");
		if (imageUrl == null) {
			imageUrl = extractMeta(html, "twitter:image");
		}
	}

	@Nullable
	String getTitle() {
		return title;
	}

	@Nullable
	String getDescription() {
		return description;
	}

	@Nullable
	String getImageUrl() {
		return imageUrl;
	}

	@Nullable
	private static String extractMeta(String html, String property) {
		String lowerHtml = html.toLowerCase();
		int idx = findMetaTag(lowerHtml, property);
		if (idx < 0) return null;
		int contentStart = lowerHtml.indexOf("content=", idx);
		if (contentStart < 0) return null;
		int tagEnd = html.indexOf('>', idx);
		if (tagEnd < 0 || contentStart > tagEnd) return null;
		contentStart += 8;
		if (contentStart >= html.length()) return null;
		char quote = html.charAt(contentStart);
		if (quote != '"' && quote != '\'') return null;
		contentStart++;
		int contentEnd = html.indexOf(quote, contentStart);
		if (contentEnd < 0) return null;
		String value = html.substring(contentStart, contentEnd).trim();
		return decodeHtmlEntities(value);
	}

	private static int findMetaTag(String lowerHtml, String property) {
		String prop = property.toLowerCase();
		String[] patterns = {
				"property=\"" + prop + "\"",
				"property='" + prop + "'",
				"name=\"" + prop + "\"",
				"name='" + prop + "'"
		};
		int best = -1;
		for (String pattern : patterns) {
			int idx = lowerHtml.indexOf(pattern);
			if (idx >= 0) {
				int metaIdx = lowerHtml.lastIndexOf("<meta", idx);
				if (metaIdx >= 0 && (best < 0 || metaIdx < best)) {
					best = metaIdx;
				}
			}
		}
		return best;
	}

	@Nullable
	private static String extractHtmlTitle(String html) {
		String lower = html.toLowerCase();
		int start = lower.indexOf("<title");
		if (start < 0) return null;
		int tagEnd = lower.indexOf('>', start);
		if (tagEnd < 0) return null;
		tagEnd++;
		int end = lower.indexOf("</title>", tagEnd);
		if (end < 0) return null;
		String title = html.substring(tagEnd, end).trim();
		if (title.isEmpty()) return null;
		return decodeHtmlEntities(title);
	}

	private static String decodeHtmlEntities(String s) {
		return s.replace("&amp;", "&")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&apos;", "'");
	}
}
