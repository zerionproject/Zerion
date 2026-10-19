package com.professor.zerion.android.vault.ui;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.maxfont.MaxFontTest;
import com.professor.zerion.android.maxfont.RealText;
import com.professor.zerion.android.view.WrapRow;

import org.bitcoinj.crypto.MnemonicCode;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RecoveryWordsAtMaxFontTest extends MaxFontTest {

	private static final String[] LONG_MONERO_WORDS = {"laboratory",
			"dehydrate", "dexterity", "itinerary", "lifestyle", "aggravate",
			"eavesdrop", "knowledge", "adrenalin", "imbalance", "dwindling",
			"launching", "loincloth", "boyfriend", "adventure", "arbitrary",
			"laboratory", "dehydrate", "dexterity", "itinerary", "lifestyle",
			"aggravate", "eavesdrop", "knowledge", "adrenalin"};

	private static String[] widestBip39Words(int count) {
		Paint paint = new Paint();
		paint.setTextSize(100f);
		paint.setTypeface(Typeface.DEFAULT_BOLD);
		List<String> words = new ArrayList<>(
				MnemonicCode.INSTANCE.getWordList());
		words.sort((x, y) -> Float.compare(RealText.width(paint, y),
				RealText.width(paint, x)));
		return words.subList(0, count).toArray(new String[0]);
	}

	private static void assertEveryWordWhole(Largest a, String[] words) {
		LayoutInflater inflater = LayoutInflater.from(a);
		View screen = inflater.inflate(R.layout.fragment_xmr_recovery, null,
				false);
		LinearLayout grid = screen.findViewById(R.id.recovery_word_grid);
		List<TextView> shown = new ArrayList<>();
		XmrRecoveryPhraseFragment.fillWordGrid(inflater, grid, words, shown);
		layoutIn(screen, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		assertEquals(words.length, shown.size());
		for (TextView word : shown) {
			ViewGroup cell = (ViewGroup) word.getParent();
			TextView index = (TextView) cell.getChildAt(0);
			for (TextView t : new TextView[] {index, word}) {
				String text = t.getText().toString();
				assertEquals(text + " does not sit on one line", 1,
						t.getLayout().getLineCount());
				assertTrue(text + " is cut", shownWhole(t));
				int room = t.getWidth() - t.getCompoundPaddingLeft()
						- t.getCompoundPaddingRight();
				float needed = RealText.width(t.getPaint(), text);
				assertTrue(text + " needs " + needed + "px and has " + room,
						needed <= room);
			}
			int[] b = boundsIn(cell, grid);
			assertTrue(word.getText() + " leaves the screen",
					b[0] >= 0 && b[2] <= grid.getWidth());
		}
	}

	@Test
	public void longMoneroWordsShowInFullAtTheLargestText() {
		assertEveryWordWhole(activity(), LONG_MONERO_WORDS);
	}

	@Test
	public void theWidestBip39WordsShowInFullAtTheLargestText() {
		assertEveryWordWhole(activity(), widestBip39Words(24));
	}

	private static void forceLayout(View v) {
		v.forceLayout();
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) forceLayout(g.getChildAt(i));
		}
	}

	private static List<Object> rememberedTexts(ViewGroup grid)
			throws Exception {
		List<Object> out = new ArrayList<>();
		java.lang.reflect.Field checked = WrapRow.class.getDeclaredField(
				"checked");
		checked.setAccessible(true);
		for (int i = 0; i < grid.getChildCount(); i++) {
			View row = grid.getChildAt(i);
			if (!(row instanceof WrapRow)) continue;
			java.util.Map<?, ?> map = (java.util.Map<?, ?>) checked.get(row);
			for (Object fit : map.values()) {
				java.lang.reflect.Field text =
						fit.getClass().getDeclaredField("text");
				text.setAccessible(true);
				out.add(text.get(fit));
			}
		}
		return out;
	}

	@Test
	public void noRowKeepsAWordAfterThePhraseIsCleared() throws Exception {
		Largest a = activity();
		LayoutInflater inflater = LayoutInflater.from(a);
		View screen = inflater.inflate(R.layout.fragment_xmr_recovery, null,
				false);
		LinearLayout grid = screen.findViewById(R.id.recovery_word_grid);
		List<TextView> shown = new ArrayList<>();
		String[] words = {"abandon", "dehydrate", "laboratory", "zoo",
				"itinerary"};
		XmrRecoveryPhraseFragment.fillWordGrid(inflater, grid, words, shown);
		int width = screenWidth(a);
		int height = a.getResources().getDisplayMetrics().heightPixels;
		layoutIn(screen, width, height);
		assertTrue("the rows checked no words",
				!rememberedTexts(grid).isEmpty());
		XmrRecoveryPhraseFragment.clearWords(shown);
		forceLayout(screen);
		layoutIn(screen, width, height);
		for (Object text : rememberedTexts(grid)) {
			for (String word : words) {
				assertTrue("a row still holds " + word,
						text == null || !word.contentEquals(text.toString()));
			}
		}
	}

	@Test
	public void normalTextKeepsTwoWordsPerRow() {
		Normal a = normalActivity();
		LayoutInflater inflater = LayoutInflater.from(a);
		View screen = inflater.inflate(R.layout.fragment_xmr_recovery, null,
				false);
		LinearLayout grid = screen.findViewById(R.id.recovery_word_grid);
		List<TextView> shown = new ArrayList<>();
		XmrRecoveryPhraseFragment.fillWordGrid(inflater, grid,
				LONG_MONERO_WORDS, shown);
		layoutIn(screen, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		for (int i = 0; i + 1 < shown.size(); i += 2) {
			int[] first = boundsIn((View) shown.get(i).getParent(), grid);
			int[] second = boundsIn((View) shown.get(i + 1).getParent(), grid);
			assertEquals("words " + (i + 1) + " and " + (i + 2)
					+ " share a row", first[1], second[1]);
			assertTrue(shownWhole(shown.get(i)) && shownWhole(shown.get(i + 1)));
		}
	}
}
