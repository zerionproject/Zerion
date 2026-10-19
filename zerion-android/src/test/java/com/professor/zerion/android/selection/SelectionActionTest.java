package com.professor.zerion.android.selection;

import com.professor.zerion.R;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SelectionActionTest {

	@Test
	public void oneItemKeepsEveryOfferedAction() {
		EnumSet<SelectionAction> all = EnumSet.allOf(SelectionAction.class);
		assertEquals(all, SelectionAction.shown(all, 1));
	}

	@Test
	public void severalItemsKeepOnlyActionsForSeveral() {
		EnumSet<SelectionAction> all = EnumSet.allOf(SelectionAction.class);
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.FORWARD), SelectionAction.shown(all, 2));
	}

	@Test
	public void nothingIsShownWithoutASelection() {
		assertTrue(SelectionAction.shown(
				EnumSet.allOf(SelectionAction.class), 0).isEmpty());
	}

	@Test
	public void anActionThatIsNotOfferedIsNeverShown() {
		assertEquals(EnumSet.of(SelectionAction.DELETE), SelectionAction.shown(
				EnumSet.of(SelectionAction.DELETE), 1));
	}

	@Test
	public void theBarHoldsReplyReactCopyAndDeleteAndMoreHoldsTheRest() {
		EnumSet<SelectionAction> primary = EnumSet.noneOf(
				SelectionAction.class);
		for (SelectionAction a : SelectionAction.values()) {
			if (a.isPrimary()) primary.add(a);
		}
		assertEquals(EnumSet.of(SelectionAction.REPLY, SelectionAction.REACT,
				SelectionAction.COPY, SelectionAction.DELETE), primary);
	}

	@Test
	public void everyActionHasItsOwnMenuItem() {
		for (SelectionAction a : SelectionAction.values()) {
			assertEquals(a, SelectionAction.byId(a.getId()));
		}
		assertNull(SelectionAction.byId(R.id.action_more));
	}
}
