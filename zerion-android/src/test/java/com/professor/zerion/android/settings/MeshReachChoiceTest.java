package com.professor.zerion.android.settings;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeshReachChoiceTest {

	private static final String FRAGMENT =
			"src/main/java/com/professor/zerion/android/settings/"
					+ "ConnectionsFragment.java";

	private static final String MANAGER =
			"src/main/java/com/professor/zerion/android/mesh/MeshManager.java";

	private static String read(String path) throws Exception {
		return new String(Files.readAllBytes(Paths.get(path)),
				StandardCharsets.UTF_8);
	}

	private static String body(String s, String signature) {
		int i = s.indexOf(signature);
		assertTrue(signature, i >= 0);
		int open = s.indexOf('{', i);
		int depth = 0;
		for (int k = open; k < s.length(); k++) {
			char c = s.charAt(k);
			if (c == '{') depth++;
			if (c == '}' && --depth == 0) return s.substring(open, k + 1);
		}
		throw new AssertionError("unbalanced");
	}

	private static int count(String s, String needle) {
		int n = 0;
		for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
			n++;
		}
		return n;
	}

	@Test
	public void openingTheMeshToEveryoneGoesThroughTheConfirmation()
			throws Exception {
		String src = read(FRAGMENT);
		String choice = body(src, "private void showMeshReachDialog()");
		assertTrue("the everyone choice asks first",
				choice.contains("confirmOpenMesh()"));
		assertFalse("the everyone choice opens the mesh without asking",
				choice.contains("setOpenMesh(true)"));
		String confirm = body(src, "private void confirmOpenMesh()");
		assertTrue(confirm.contains(
				"setMessage(R.string.mesh_reach_everyone_message)"));
		int positive = confirm.indexOf("setPositiveButton(");
		assertTrue(positive > 0);
		assertTrue("only the positive button opens the mesh",
				confirm.indexOf("setOpenMesh(true)", positive) > 0);
		assertEquals("the mesh is opened from one place only", 1,
				count(src, "setOpenMesh(true)"));
	}

	@Test
	public void theMeshManagerStartsInContactsOnlyModeWithoutAStoredChoice()
			throws Exception {
		String flat = read(MANAGER).replaceAll("\\s+", "");
		int guard = flat.indexOf(
				"if(!meshSettings.getBoolean(MeshController.PREF_MESH_OPEN,false)){");
		assertTrue("the open mode must be chosen, it is not the default",
				guard >= 0);
		int block = flat.indexOf('}', guard);
		assertTrue(flat.substring(guard, block).contains("setPrivatePeers("));
		assertEquals("private peers are set only under that guard", 1,
				count(flat, "setPrivatePeers("));
	}
}
