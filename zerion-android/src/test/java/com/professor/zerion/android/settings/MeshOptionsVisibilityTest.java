package com.professor.zerion.android.settings;

import android.view.LayoutInflater;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MeshOptionsVisibilityTest {

	private static final int[] ROWS = {R.id.mesh_alias_card,
			R.id.mesh_range_card, R.id.mesh_pause_card};

	@Test
	public void theMeshOptionsAreHiddenWhereTheMeshCannotRun() {
		View root = inflate();
		ConnectionsFragment.showMeshOptions(root, false);
		for (int id : ROWS) {
			assertEquals(View.GONE, root.findViewById(id).getVisibility());
		}
	}

	@Test
	public void theMeshOptionsAreShownWhereTheMeshCanRun() {
		View root = inflate();
		ConnectionsFragment.showMeshOptions(root, true);
		for (int id : ROWS) {
			assertEquals(View.VISIBLE, root.findViewById(id).getVisibility());
		}
	}

	private static View inflate() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		return LayoutInflater.from(activity).inflate(
				R.layout.fragment_settings_connections, null, false);
	}
}
