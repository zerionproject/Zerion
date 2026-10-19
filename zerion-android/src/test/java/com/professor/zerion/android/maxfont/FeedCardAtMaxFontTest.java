package com.professor.zerion.android.maxfont;

import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class FeedCardAtMaxFontTest extends MaxFontTest {

	private static View card(Largest a, String author, boolean editor,
			String time, String comments) {
		View row = LayoutInflater.from(a).inflate(
				R.layout.list_item_channel_post, null, false);
		((TextView) row.findViewById(R.id.channelPostAvatar)).setText(
				author.substring(0, 1));
		((TextView) row.findViewById(R.id.channelPostAuthor)).setText(author);
		TextView badge = row.findViewById(R.id.channelPostSignerBadge);
		badge.setText(a.getString(R.string.channels_post_signer_editor)
				.toUpperCase(java.util.Locale.ROOT));
		badge.setVisibility(editor ? View.VISIBLE : View.GONE);
		((TextView) row.findViewById(R.id.channelPostTimestampView))
				.setText(time);
		((TextView) row.findViewById(R.id.channelPostBodyView)).setText(
				"chanedit2030");
		TextView commentBadge = row.findViewById(R.id.channelPostCommentBadge);
		commentBadge.setText(comments);
		commentBadge.setVisibility(View.VISIBLE);
		row.findViewById(R.id.channelPostReactButton).setVisibility(
				View.VISIBLE);
		layoutAtWidth(host(a, row), screenWidth(a));
		return row;
	}

	private static List<String> problems(View row, int... ids) {
		List<String> problems = new ArrayList<>();
		for (int id : ids) {
			TrailingLabelRowsAtMaxFontTest.collectProblems(
					row.findViewById(id), problems);
		}
		return problems;
	}

	private static void assertInsideCard(View row, int id) {
		View card = row.findViewById(R.id.layout);
		View v = row.findViewById(id);
		int[] b = boundsIn(v, card);
		assertTrue(v + " leaves the card", b[0] >= 0
				&& b[2] <= card.getWidth() && b[3] <= card.getHeight());
	}

	@Test
	public void theAuthorStaysWholeWhenTheEditedTimeTakesItsOwnLine() {
		Largest a = activity();
		View row = card(a, "OpenTest18", false, "15 okt, 20:00 \u00b7 "
				+ a.getString(R.string.message_edited), "Opmerkingen 1");
		TextView author = row.findViewById(R.id.channelPostAuthor);
		assertFalse("the author is cut to " + author.getWidth() + "px",
				ellipsized(author));
		assertTrue(author.getWidth() >= RealText.width(author.getPaint(),
				author.getText()));
		List<String> problems = problems(row, R.id.channelPostHeader,
				R.id.channelPostFooter);
		assertEquals(String.join("\n", problems), 0, problems.size());
		for (int id : new int[] {R.id.channelPostAuthor,
				R.id.channelPostTimestampView, R.id.channelPostCommentBadge,
				R.id.channelPostReactButton}) {
			assertInsideCard(row, id);
		}
	}

	@Test
	public void aShortHeaderKeepsTheTimeOnTheAuthorLine() {
		Largest a = activity();
		View row = card(a, "Ok", false, "09:00", "Opmerkingen 1");
		View author = row.findViewById(R.id.channelPostAuthor);
		View time = row.findViewById(R.id.channelPostTimestampView);
		int[] ab = boundsIn(author, row);
		int[] tb = boundsIn(time, row);
		assertTrue("the time moved off the author line",
				tb[0] >= ab[2] && tb[1] < ab[3]);
	}

	@Test
	public void aLongEditorNameShowsMoreThanALetter() {
		Largest a = activity();
		String name = "Samantha Alexandra Montgomery-Whitfields";
		View row = card(a, name, true, "15 okt, 20:00 \u00b7 "
				+ a.getString(R.string.message_edited), "Opmerkingen 12");
		TextView author = row.findViewById(R.id.channelPostAuthor);
		Layout layout = author.getLayout();
		int shown = layout.getEllipsisCount(0) > 0
				? layout.getEllipsisStart(0) : name.length();
		assertTrue("only " + shown + " letters of the name show",
				shown >= 8);
		TextView badge = row.findViewById(R.id.channelPostSignerBadge);
		assertTrue(badge.getText() + " is cut", shownWhole(badge));
		TextView time = row.findViewById(R.id.channelPostTimestampView);
		assertTrue(time.getText() + " is cut", shownWhole(time));
		assertInsideCard(row, R.id.channelPostSignerBadge);
		assertInsideCard(row, R.id.channelPostTimestampView);
	}

	private static void assertTimeShownInFull(Largest a, String time) {
		View row = card(a, "PhaseAopen", false, time, "Opmerkingen 2");
		TextView view = row.findViewById(R.id.channelPostTimestampView);
		assertTrue(time + " is cut", shownWhole(view));
		assertEquals(null, brokenWord(view));
		assertTrue(shownWhole((TextView) row.findViewById(
				R.id.channelPostAuthor)));
		assertInsideCard(row, R.id.channelPostTimestampView);
		List<String> problems = problems(row, R.id.channelPostHeader);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void aLongApproximateTimeShowsInFull() {
		Largest a = activity();
		assertTimeShownInFull(a, "Oct 17, 2025, around 10:00 \u00b7 edited");
		assertTimeShownInFull(a, "17 okt. 2025, rond 10:00 \u00b7 bewerkt");
	}

	@Test
	public void theReactButtonKeepsItsWordWhole() {
		Largest a = activity();
		View row = card(a, "PhaseAopen", false, "16 okt, 14:00",
				"Opmerkingen 2");
		TextView react = row.findViewById(R.id.channelPostReactButton);
		assertEquals(null, brokenWord(react));
		assertFalse(ellipsized(react));
		List<String> problems = problems(row, R.id.channelPostFooter);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
