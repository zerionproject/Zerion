package com.professor.zerion.android.vault.util;

import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BookmarkRulesTest {

	private static VaultItem item(String id, VaultItem.ItemType type,
			String name, long modified) {
		return new VaultItem(id, type, name, modified, modified, 1,
				new byte[0], false, new byte[0],
				VaultItem.DEFAULT_EXTRA_MEMORY_KB,
				VaultItem.DEFAULT_EXTRA_ITERATIONS,
				VaultItem.DEFAULT_EXTRA_PARALLELISM,
				new byte[0], new byte[0], 1);
	}

	@Test
	public void webLinksAreAcceptedWithTheirHost() {
		assertEquals("example.org", BookmarkRules.host("https://example.org"));
		assertEquals("example.org",
				BookmarkRules.host("HTTP://Example.ORG/path?q=1#frag"));
		assertEquals("example.org", BookmarkRules.host("https://example.org:8443/"));
		assertNull(BookmarkRules.host("https://user:pw@example.org/"));
		assertNull(BookmarkRules.host("https://bank.example@evil.com/"));
		assertNull(BookmarkRules.host("https://user:secret@example.org/"));
		assertEquals("[2001:db8::1]",
				BookmarkRules.host("http://[2001:db8::1]:8080/x"));
	}

	@Test
	public void onionHostsAreAccepted() {
		String onion = "http://"
				+ "pg6mmjiyjmcrsslvykfwnntlaru7p5svn6y2ymmju6nubxndf4pscryd"
				+ ".onion/";
		assertEquals("pg6mmjiyjmcrsslvykfwnntlaru7p5svn6y2ymmju6nubxndf4pscryd"
				+ ".onion", BookmarkRules.host(onion));
	}

	@Test
	public void internationalHostsBecomeTheirPlainForm() {
		assertEquals("xn--bcher-kva.example",
				BookmarkRules.host("https://bücher.example/"));
	}

	@Test
	public void otherSchemesAndBrokenLinksAreRefused() {
		assertNull(BookmarkRules.host(null));
		assertNull(BookmarkRules.host(""));
		assertNull(BookmarkRules.host("example.org"));
		assertNull(BookmarkRules.host("ftp://example.org/"));
		assertNull(BookmarkRules.host("javascript://example.org/%0aalert(1)"));
		assertNull(BookmarkRules.host("file:///etc/passwd"));
		assertNull(BookmarkRules.host("intent://x#Intent;end"));
		assertNull(BookmarkRules.host("https://"));
		assertNull(BookmarkRules.host("https:///path"));
		assertNull(BookmarkRules.host("https://exa mple.org/"));
		assertNull(BookmarkRules.host("https://example.org/\n"));
		assertNull(BookmarkRules.host("https://example.org:abc/"));
		assertNull(BookmarkRules.host("https://example.org:70000/"));
		assertNull(BookmarkRules.host("https://exam_ple.org/"));
		assertNull(BookmarkRules.host("https://.example.org/"));
		assertNull(BookmarkRules.host("https://example..org/"));
		assertNull(BookmarkRules.host("https://exam​ple.org/"));
		assertNull(BookmarkRules.host("https://‮example.org/"));
	}

	@Test
	public void titlesLoseFormattingAndControlCharacters() {
		assertEquals("My bank", BookmarkRules.cleanTitle(
				"‮My​ bank‏"));
		assertEquals("a b", BookmarkRules.cleanTitle("  a \n\t b  "));
		assertEquals("", BookmarkRules.cleanTitle(null));
	}

	@Test
	public void aTitleOfOnlyFormattingCharactersIsRefused() {
		assertFalse(BookmarkRules.validTitle(BookmarkRules.cleanTitle(
				"‮​‏ ⁦")));
		assertFalse(BookmarkRules.validTitle(BookmarkRules.cleanTitle("   ")));
		assertFalse(BookmarkRules.validTitle(BookmarkRules.cleanTitle("")));
		assertTrue(BookmarkRules.validTitle(BookmarkRules.cleanTitle("x")));
		char[] longTitle = new char[BookmarkRules.MAX_TITLE_LENGTH + 1];
		Arrays.fill(longTitle, 'a');
		assertFalse(BookmarkRules.validTitle(new String(longTitle)));
		assertTrue(BookmarkRules.validTitle(
				new String(longTitle, 0, BookmarkRules.MAX_TITLE_LENGTH)));
	}

	@Test
	public void passwordsAndBookmarksAreKeptApart() {
		VaultItem password = item("p", VaultItem.ItemType.PASSWORD, "Mail", 1);
		VaultItem bookmark = item("b", VaultItem.ItemType.PASSWORD, "Shop", 2);
		VaultItem note = item("n", VaultItem.ItemType.NOTE, "Shop", 3);
		List<VaultItem> all = Arrays.asList(password, bookmark, note);
		Set<String> bookmarkIds = new HashSet<>(Collections.singleton("b"));

		assertEquals(Collections.singletonList(password),
				BookmarkRules.passwords(all, bookmarkIds));
		assertEquals(Collections.singletonList(bookmark),
				BookmarkRules.bookmarks(all, bookmarkIds));
		assertEquals(Arrays.asList(password, bookmark),
				BookmarkRules.passwords(all, Collections.<String>emptySet()));
		assertTrue(BookmarkRules.bookmarks(all,
				Collections.<String>emptySet()).isEmpty());
	}

	@Test
	public void theBookmarkListSearchesTitleAndHost() {
		VaultItem shop = item("s", VaultItem.ItemType.PASSWORD, "Shop", 1);
		VaultItem news = item("n", VaultItem.ItemType.PASSWORD, "Daily news", 2);
		VaultItem bare = item("x", VaultItem.ItemType.PASSWORD, "Odd", 3);
		List<VaultItem> all = Arrays.asList(shop, news, bare);
		Map<String, String> urls = new HashMap<>();
		urls.put("s", "https://shop.example.org/");
		urls.put("n", "http://news.example.net/today");

		assertEquals(Arrays.asList(news, bare, shop),
				BookmarkRules.filterSort(all, urls, "", VaultSearch.SORT_NAME));
		assertEquals(Arrays.asList(bare, news, shop),
				BookmarkRules.filterSort(all, urls, null,
						VaultSearch.SORT_RECENT));
		assertEquals(Collections.singletonList(news),
				BookmarkRules.filterSort(all, urls, "NEWS",
						VaultSearch.SORT_NAME));
		assertEquals(Collections.singletonList(news),
				BookmarkRules.filterSort(all, urls, "example.net",
						VaultSearch.SORT_NAME));
		assertEquals(Arrays.asList(news, shop),
				BookmarkRules.filterSort(all, urls, "example",
						VaultSearch.SORT_NAME));
		assertTrue(BookmarkRules.filterSort(all, urls, "today",
				VaultSearch.SORT_NAME).isEmpty());
		assertEquals(Collections.singletonList(bare),
				BookmarkRules.filterSort(all, urls, "odd",
						VaultSearch.SORT_NAME));
	}
}
