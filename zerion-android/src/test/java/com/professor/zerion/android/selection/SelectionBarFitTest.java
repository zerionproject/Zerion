package com.professor.zerion.android.selection;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectionBarFitTest {

	private static final List<SelectionAction> ALL = Arrays.asList(
			SelectionAction.REPLY, SelectionAction.REACT, SelectionAction.COPY,
			SelectionAction.DELETE);

	private static SelectionBar.Fit plan(float labels, float available,
			float title) {
		return SelectionBar.Fit.plan(ALL, Arrays.asList(labels, labels,
				labels, labels), labels, true, available, 52f, title, 48f,
				64f, 4f);
	}

	@Test
	public void aWideBarKeepsEveryLabelAndButton() {
		SelectionBar.Fit fit = plan(30f, 420f, 80f);
		assertTrue(fit.labels);
		assertTrue(fit.moved.isEmpty());
	}

	@Test
	public void longLabelsGiveWayToIconsWithTheirNamesKeptForTalkBack() {
		SelectionBar.Fit fit = plan(70f, 420f, 80f);
		assertFalse("a label would be cut off", fit.labels);
		assertTrue(fit.moved.isEmpty());
	}

	@Test
	public void aNarrowBarMovesReactFirstAndThenDeleteIntoMore() {
		assertEquals(EnumSet.of(SelectionAction.REACT),
				plan(30f, 340f, 80f).moved);
		assertEquals(EnumSet.of(SelectionAction.REACT,
				SelectionAction.DELETE), plan(30f, 300f, 80f).moved);
	}

	@Test
	public void replyAndCopyAlwaysStayOnTheBar() {
		SelectionBar.Fit fit = plan(90f, 100f, 200f);
		assertFalse(fit.moved.contains(SelectionAction.REPLY));
		assertFalse(fit.moved.contains(SelectionAction.COPY));
	}

	@Test
	public void movingAButtonAddsMoreWhenNothingElseNeedsIt() {
		SelectionBar.Fit fit = SelectionBar.Fit.plan(Arrays.asList(
				SelectionAction.REPLY, SelectionAction.REACT,
				SelectionAction.COPY, SelectionAction.DELETE),
				Arrays.asList(30f, 30f, 30f, 30f), 30f, false, 300f, 52f, 80f,
				48f, 64f, 4f);
		assertEquals("More takes the place React leaves, so Delete moves too",
				EnumSet.of(SelectionAction.REACT, SelectionAction.DELETE),
				fit.moved);
	}
}
