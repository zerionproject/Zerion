package com.professor.zerion.android.view;

import com.professor.zerion.android.util.ItemReturningAdapter;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ZerionRecyclerViewScrollListenerTest {

	private static final class Items implements ItemReturningAdapter<String> {

		final List<String> items = new ArrayList<>();

		@Override
		public String getItemAt(int position) {
			return position < items.size() ? items.get(position) : null;
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static final class Seen
			extends ZerionRecyclerViewScrollListener<Items, String> {

		final List<String> seen = new ArrayList<>();

		Seen(Items adapter) {
			super(adapter);
		}

		@Override
		protected void onItemVisible(String item) {
			seen.add(item);
		}
	}

	@Test
	public void positionsLeftOverFromALongerListAreSkipped() {
		Items adapter = new Items();
		adapter.items.addAll(Arrays.asList("a", "b"));
		Seen listener = new Seen(adapter);
		listener.onItemsVisible(0, 4, 5);
		assertEquals(Arrays.asList("a", "b"), listener.seen);
	}
}
