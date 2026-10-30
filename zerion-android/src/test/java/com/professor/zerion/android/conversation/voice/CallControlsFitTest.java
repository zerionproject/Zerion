package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "nl-w360dp-h740dp-xhdpi")
public class CallControlsFitTest {

	private static final int[] BUTTONS = {R.id.mute_button,
			R.id.speaker_button, R.id.video_button,
			R.id.switch_camera_button};
	private static final int[] LABELS = {R.id.mute_label,
			R.id.speaker_label, R.id.video_label, R.id.switch_camera_label};
	private static final int[] CONTAINERS = {R.id.video_button_container,
			R.id.switch_camera_container};

	private static Context scaled(float fontScale) {
		Context app = RuntimeEnvironment.getApplication();
		Configuration c = new Configuration(app.getResources()
				.getConfiguration());
		c.fontScale = fontScale;
		return new ContextThemeWrapper(app.createConfigurationContext(c),
				R.style.ZerionTheme);
	}

	private static View laidOut(Context ctx, int widthDp) {
		View root = LayoutInflater.from(ctx).inflate(
				R.layout.activity_voice_call, null, false);
		root.findViewById(R.id.active_call_layout)
				.setVisibility(View.VISIBLE);
		root.findViewById(R.id.incoming_call_layout)
				.setVisibility(View.GONE);
		for (int id : CONTAINERS) {
			root.findViewById(id).setVisibility(View.VISIBLE);
		}
		DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
		int width = Math.round(widthDp * dm.density);
		int height = Math.round(740 * dm.density);
		root.measure(View.MeasureSpec.makeMeasureSpec(width,
						View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height,
						View.MeasureSpec.EXACTLY));
		root.layout(0, 0, width, height);
		return root;
	}

	private static int left(View v) {
		int x = 0;
		for (View p = v; p != null; p = p.getParent() instanceof View
				? (View) p.getParent() : null) {
			x += p.getLeft();
		}
		return x;
	}

	private static void assertFits(Context ctx, int widthDp) {
		View root = laidOut(ctx, widthDp);
		float density = ctx.getResources().getDisplayMetrics().density;
		int minTarget = Math.round(48 * density);
		int screen = Math.round(widthDp * density);
		for (int i = 0; i < BUTTONS.length; i++) {
			View b = root.findViewById(BUTTONS[i]);
			String name = ctx.getResources().getResourceEntryName(BUTTONS[i]);
			assertTrue(name + " is only " + b.getWidth() + " px wide at "
							+ widthDp + "dp",
					b.getWidth() >= minTarget && b.getHeight() >= minTarget);
			assertTrue(name + " runs off the screen at " + widthDp + "dp",
					left(b) >= 0 && left(b) + b.getWidth() <= screen);
			View column = (View) b.getParent();
			assertTrue(name + " column is squeezed to " + column.getWidth()
							+ " px at " + widthDp + "dp",
					column.getWidth() >= minTarget);
			assertTrue(name + " column runs off the screen at " + widthDp
							+ "dp",
					left(column) >= 0
							&& left(column) + column.getWidth() <= screen);
			TextView label = root.findViewById(LABELS[i]);
			assertTrue(name + " label runs off the screen",
					left(label) >= 0
							&& left(label) + label.getWidth() <= screen);
			assertTrue(name + " label may wrap instead of being cut",
					label.getMaxLines() >= 2);
		}
	}

	@Test
	public void allFourControlsKeepTheirTargetOnANarrowPhone() {
		assertFits(scaled(1f), 360);
	}

	@Test
	public void allFourControlsKeepTheirTargetAtTheLargestFont() {
		assertFits(scaled(2f), 360);
	}

	@Test
	public void allFourControlsKeepTheirTargetOnAVeryNarrowScreen() {
		assertFits(scaled(1.3f), 320);
	}
}
