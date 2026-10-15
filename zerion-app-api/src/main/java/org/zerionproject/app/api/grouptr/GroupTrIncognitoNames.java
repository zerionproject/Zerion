package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class GroupTrIncognitoNames {

	public static final int MIN_NUMBER = 10;
	public static final int MAX_NUMBER = 99;

	public static final List<String> ADJECTIVES = Collections.unmodifiableList(
			Arrays.asList("Amber", "Ancient", "Bold", "Brave", "Bright",
					"Brisk", "Calm", "Clever", "Cool", "Cosmic", "Crisp",
					"Curious", "Daring", "Distant", "Dusty", "Eager", "Early",
					"Electric", "Fair", "Fast", "Fearless", "Gentle", "Glad",
					"Golden", "Grand", "Green", "Happy", "Hidden", "Honest",
					"Humble", "Icy", "Jolly", "Keen", "Kind", "Lively",
					"Lucky", "Mellow", "Merry", "Misty", "Modest", "Noble",
					"Patient", "Polar", "Proud", "Quick", "Quiet", "Rapid",
					"Rare", "Silent", "Silver", "Smooth", "Snowy", "Solar",
					"Steady", "Sunny", "Swift", "Tidy", "Tiny", "Velvet",
					"Vivid", "Wandering", "Warm", "Wild", "Wise"));

	public static final List<String> NOUNS = Collections.unmodifiableList(
			Arrays.asList("Badger", "Bear", "Beaver", "Bison", "Cedar",
					"Comet", "Condor", "Crane", "Delta", "Dolphin", "Dune",
					"Eagle", "Falcon", "Fern", "Finch", "Fox", "Gecko",
					"Glacier", "Harbor", "Hawk", "Heron", "Ibis", "Island",
					"Jaguar", "Koala", "Lark", "Lemur", "Lynx", "Maple",
					"Meadow", "Meteor", "Moose", "Nebula", "Newt", "Oak",
					"Ocean", "Orca", "Otter", "Owl", "Panda", "Pebble",
					"Pine", "Planet", "Puffin", "Quail", "Raven", "Reef",
					"River", "Robin", "Sparrow", "Spruce", "Stone", "Swan",
					"Tiger", "Tundra", "Valley", "Walrus", "Willow", "Wolf",
					"Wren", "Yak", "Zebra", "Canyon", "Lagoon"));

	private static final Set<String> ADJECTIVE_SET =
			new HashSet<>(ADJECTIVES);
	private static final Set<String> NOUN_SET = new HashSet<>(NOUNS);

	private GroupTrIncognitoNames() {
	}

	public static String generate(Random random) {
		String adjective = ADJECTIVES.get(random.nextInt(ADJECTIVES.size()));
		String noun = NOUNS.get(random.nextInt(NOUNS.size()));
		int number = MIN_NUMBER
				+ random.nextInt(MAX_NUMBER - MIN_NUMBER + 1);
		return adjective + " " + noun + " " + number;
	}

	public static boolean isIncognitoName(@Nullable String name) {
		if (name == null) return false;
		String[] parts = name.split(" ", -1);
		if (parts.length != 3) return false;
		if (!ADJECTIVE_SET.contains(parts[0])) return false;
		if (!NOUN_SET.contains(parts[1])) return false;
		String digits = parts[2];
		if (digits.length() != 2) return false;
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c < '0' || c > '9') return false;
		}
		int number = Integer.parseInt(digits);
		return number >= MIN_NUMBER && number <= MAX_NUMBER;
	}
}
