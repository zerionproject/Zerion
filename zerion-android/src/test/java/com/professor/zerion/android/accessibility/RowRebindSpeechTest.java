package com.professor.zerion.android.accessibility;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import com.professor.zerion.android.message.MessageState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RowRebindSpeechTest {

	private static final Object STATE = new Object();

	private static final class Rows
			extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		MessageState state = MessageState.WAITING;
		int bound = 0;

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			TextView v = new TextView(parent.getContext());
			v.setLayoutParams(new RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 100));
			return new RecyclerView.ViewHolder(v) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h,
				int position) {
			bound++;
			TextView meta = (TextView) h.itemView;
			meta.setText(state.name());
			RowStates.of(meta).saw(meta, "row", state, () -> "Dinner at eight");
		}

		@Override
		public int getItemCount() {
			return 1;
		}
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void layout(RecyclerView list) {
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		idle();
	}

	@Test
	public void aStateChangeBoundInPlaceDuringALayoutIsStillSaid() {
		Activity activity = Robolectric.buildActivity(Activity.class).setup()
				.get();
		RecyclerView list = new RecyclerView(activity);
		list.setLayoutManager(new LinearLayoutManager(activity));
		Rows rows = new Rows();
		list.setAdapter(rows);
		activity.setContentView(list);
		layout(list);
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		int firstBinds = rows.bound;

		rows.state = MessageState.NOT_SENT;
		rows.notifyItemChanged(0, STATE);
		layout(list);

		assertEquals("the row was bound again in place", firstBinds + 1,
				rows.bound);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
		List<String> said = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				said.add(e.getText().toString());
			}
		}
		assertEquals("[[not sent: Dinner at eight]]", said.toString());
	}
}
