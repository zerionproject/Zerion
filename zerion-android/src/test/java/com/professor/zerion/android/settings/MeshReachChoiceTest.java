package com.professor.zerion.android.settings;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeshReachChoiceTest {

	private static final Path SOURCE_SETS = Paths.get("src");

	private static final String FRAGMENT =
			"src/main/java/com/professor/zerion/android/settings/"
					+ "ConnectionsFragment.java";

	private static final String CONTROLLER =
			"src/main/java/com/professor/zerion/android/mesh/"
					+ "MeshController.java";

	private static final String MANAGER =
			"src/main/java/com/professor/zerion/android/mesh/MeshManager.java";

	private static final Pattern SETTER = Pattern.compile("\\bsetOpenMesh\\b");

	private static final Pattern PARAMETER =
			Pattern.compile("boolean\\s+\\w+");

	private static final Pattern RETURNS_NOTHING =
			Pattern.compile("\\bvoid\\s*$");

	private static final String NOT_A_CALL = "not a call";

	private static final class Use {

		private final String file;
		private final String source;
		private final int start;
		private final int end;

		private Use(String file, String source, int start, int end) {
			this.file = file;
			this.source = source;
			this.start = start;
			this.end = end;
		}

		private String argument() {
			int open = end;
			while (open < source.length()
					&& Character.isWhitespace(source.charAt(open))) {
				open++;
			}
			if (open >= source.length() || source.charAt(open) != '(') {
				return NOT_A_CALL;
			}
			int depth = 0;
			for (int k = open; k < source.length(); k++) {
				char c = source.charAt(k);
				if (c == '(') depth++;
				if (c == ')' && --depth == 0) {
					return source.substring(open + 1, k).trim();
				}
			}
			return NOT_A_CALL;
		}

		private boolean isDefinition() {
			return RETURNS_NOTHING.matcher(source.substring(0, start)).find()
					&& PARAMETER.matcher(argument()).matches();
		}

		private boolean opensTheMesh() {
			return !isDefinition() && "true".equals(argument());
		}

		private boolean isUnexpected() {
			return !isDefinition() && !"true".equals(argument())
					&& !"false".equals(argument());
		}

		private String described() {
			return file + ": " + argument();
		}
	}

	private static String read(String path) throws IOException {
		return new String(Files.readAllBytes(Paths.get(path)),
				StandardCharsets.UTF_8);
	}

	private static List<Use> usesIn(String file, String source) {
		List<Use> uses = new ArrayList<>();
		Matcher m = SETTER.matcher(source);
		while (m.find()) uses.add(new Use(file, source, m.start(), m.end()));
		return uses;
	}

	private static List<Path> shippedSources() throws IOException {
		List<Path> sources = new ArrayList<>();
		List<Path> sets;
		try (Stream<Path> listed = Files.list(SOURCE_SETS)) {
			sets = listed.collect(Collectors.toList());
		}
		for (Path set : sets) {
			String name = set.getFileName().toString();
			Path java = set.resolve("java");
			if (name.startsWith("test") || name.startsWith("androidTest")
					|| !Files.isDirectory(java)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(java)) {
				files.filter(p -> p.toString().endsWith(".java"))
						.forEach(sources::add);
			}
		}
		return sources;
	}

	private static List<Use> usesInShippedSources() throws IOException {
		List<Path> sources = shippedSources();
		assertTrue("the sources were not found from " + SOURCE_SETS
				.toAbsolutePath(), sources.size() > 100);
		List<Use> uses = new ArrayList<>();
		for (Path p : sources) {
			String file = SOURCE_SETS.relativize(p).toString()
					.replace('\\', '/');
			uses.addAll(usesIn("src/" + file, new String(
					Files.readAllBytes(p), StandardCharsets.UTF_8)));
		}
		return uses;
	}

	private static List<String> unexpected(List<Use> uses) {
		List<String> found = new ArrayList<>();
		for (Use use : uses) {
			if (use.isUnexpected()) found.add(use.described());
		}
		return found;
	}

	private static int[] span(String s, String signature) {
		int i = s.indexOf(signature);
		assertTrue(signature, i >= 0);
		int open = s.indexOf('{', i);
		int depth = 0;
		for (int k = open; k < s.length(); k++) {
			char c = s.charAt(k);
			if (c == '{') depth++;
			if (c == '}' && --depth == 0) return new int[] {open, k + 1};
		}
		throw new AssertionError("unbalanced");
	}

	private static String body(String s, String signature) {
		int[] span = span(s, signature);
		return s.substring(span[0], span[1]);
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
		for (Use use : usesIn(FRAGMENT, choice)) {
			assertEquals("the everyone choice opens the mesh without asking",
					"false", use.argument());
		}
		String confirm = body(src, "private void confirmOpenMesh()");
		assertTrue(confirm.contains(
				"setMessage(R.string.mesh_reach_everyone_message)"));
	}

	@Test
	public void theMeshIsOpenedFromOnePlaceOnly() throws Exception {
		List<Use> uses = usesInShippedSources();
		List<String> definitions = new ArrayList<>();
		List<Use> opening = new ArrayList<>();
		for (Use use : uses) {
			if (use.isDefinition()) definitions.add(use.file);
			if (use.opensTheMesh()) opening.add(use);
		}
		assertEquals("the setter is defined once, by the controller",
				Collections.singletonList(CONTROLLER), definitions);
		assertEquals("a use that is neither a plain true nor a plain false",
				new ArrayList<String>(), unexpected(uses));
		assertEquals("the mesh is opened from one place only", 1,
				opening.size());
		Use open = opening.get(0);
		assertEquals(FRAGMENT, open.file);
		int[] confirm = span(open.source, "private void confirmOpenMesh()");
		int positive = open.source.indexOf("setPositiveButton(", confirm[0]);
		assertTrue(positive > 0 && positive < confirm[1]);
		assertTrue("only the positive button opens the mesh",
				open.start > positive && open.start < confirm[1]);
		int negative = open.source.indexOf("setNegativeButton(", positive);
		assertTrue(negative > 0 && negative < confirm[1]);
		assertTrue("only the positive button opens the mesh",
				open.start < negative);
	}

	@Test
	public void anyOtherWayOfSettingTheReachIsNoticed() {
		String[] rejected = {
				"c.setOpenMesh(which == 1);",
				"c.setOpenMesh(\n\t\t\topen);",
				"c.setOpenMesh(!false);",
				"c.setOpenMesh(Boolean.TRUE);",
				"c.setOpenMesh(isOpen());",
				"c.setOpenMesh(true && ready);",
				"c.setOpenMesh((true));",
				"run(c::setOpenMesh);",
				"Consumer<Boolean> reach = c::setOpenMesh;",
				"String name = \"setOpenMesh\";",
				"boolean setOpenMesh(boolean open) {",
				"void setOpenMesh(boolean open, boolean now) {"};
		for (String source : rejected) {
			assertEquals(source, 1, unexpected(usesIn("probe", source)).size());
		}
		String[] accepted = {
				"c.setOpenMesh(true);",
				"c.setOpenMesh( false );",
				"c.setOpenMesh\n\t\t(true);",
				"public void setOpenMesh(boolean open) {",
				"c.resetOpenMesh(which == 1);"};
		for (String source : accepted) {
			assertEquals(source, Arrays.asList(),
					unexpected(usesIn("probe", source)));
		}
		List<Use> plain = usesIn("probe", "c.setOpenMesh( true );");
		assertTrue(plain.get(0).opensTheMesh());
		assertFalse(usesIn("probe", "c.setOpenMesh(false);").get(0)
				.opensTheMesh());
		assertFalse(usesIn("probe", "void setOpenMesh(boolean open) {")
				.get(0).opensTheMesh());
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
