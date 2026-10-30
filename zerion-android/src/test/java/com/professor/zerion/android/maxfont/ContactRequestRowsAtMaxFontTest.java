package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class ContactRequestRowsAtMaxFontTest extends MaxFontTest {

	private static View inflate(Largest a, int layout) {
		View v = LayoutInflater.from(a).inflate(layout, null, false);
		FrameLayout host = new FrameLayout(a);
		host.addView(v, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return v;
	}

	private static void assertFits(View root, int width, int... ids) {
		List<String> problems = new ArrayList<>();
		TrailingLabelRowsAtMaxFontTest.collectProblems(root, problems);
		for (int id : ids) {
			View b = root.findViewById(id);
			int x = 0;
			for (View v = b; v != null && v != root;
					v = (View) v.getParent()) {
				x += v.getLeft();
			}
			if (x + b.getWidth() > width + 1 || b.getWidth() == 0) {
				problems.add("outside the screen: " + ((TextView) b).getText());
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theInviteLinkActionsWrapInsteadOfLeavingTheScreen() {
		Largest a = activity();
		View row = inflate(a, R.layout.list_item_invite_link);
		((TextView) row.findViewById(R.id.inviteLinkLabel)).setText(
				"Klantenservice");
		((TextView) row.findViewById(R.id.inviteLinkCounts)).setText(
				"12 requests waiting, 3 blocked");
		((TextView) row.findViewById(R.id.inviteLinkState)).setText(
				R.string.invite_links_inbox_closed);
		layoutAtWidth((View) row.getParent(), screenWidth(a));
		assertFits(row, screenWidth(a), R.id.inviteLinkShare,
				R.id.inviteLinkCopy, R.id.inviteLinkQr,
				R.id.inviteLinkWithdraw);
	}

	@Test
	public void theZeroIdActionsWrapInsteadOfLeavingTheScreen() {
		Largest a = activity();
		View screen = inflate(a, R.layout.fragment_send_invite);
		layoutAtWidth((View) screen.getParent(), screenWidth(a));
		View actions = screen.findViewById(R.id.zeroIdActions);
		assertFits(actions, actions.getWidth(), R.id.zeroIdShareButton,
				R.id.zeroIdCopyButton, R.id.zeroIdQrButton);
		List<String> problems = new ArrayList<>();
		TrailingLabelRowsAtMaxFontTest.collectProblems(screen, problems);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void aRequestRowKeepsItsWordsWhole() {
		Largest a = activity();
		View row = inflate(a, R.layout.list_item_request);
		((TextView) row.findViewById(R.id.requestTitle)).setText(
				"Alexandra van der Berg");
		((TextView) row.findViewById(R.id.requestSubtitle)).setText(
				a.getString(R.string.requests_state_withdrawing));
		((TextView) row.findViewById(R.id.requestTime)).setText("12:30");
		TextView action = row.findViewById(R.id.requestAction);
		action.setVisibility(View.VISIBLE);
		action.setText(R.string.requests_cancel);
		layoutAtWidth((View) row.getParent(), screenWidth(a));
		assertFits(row, screenWidth(a), R.id.requestAction);
	}
}
