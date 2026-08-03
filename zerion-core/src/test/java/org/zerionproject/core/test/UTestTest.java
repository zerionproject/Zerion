package org.zerionproject.core.test;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.zerionproject.core.test.UTest.Result.INCONCLUSIVE;
import static org.zerionproject.core.test.UTest.Result.LARGER;
import static org.zerionproject.core.test.UTest.Result.SMALLER;
import static org.junit.Assert.assertEquals;

@Ignore
public class UTestTest extends BrambleTestCase {

	private final Random random = new Random();

	@Test
	public void testSmallerLarger() {

		int aSize = random.nextInt(1000) + 1000;
		int bSize = random.nextInt(1000) + 1000;
		List<Double> a = new ArrayList<>(aSize);
		List<Double> b = new ArrayList<>(bSize);

		for (int i = 0; i < aSize; i++) a.add(random.nextDouble());
		for (int i = 0; i < bSize; i++) b.add(random.nextDouble() + 0.1);

		assertEquals(SMALLER, UTest.test(a, b));
		assertEquals(LARGER, UTest.test(b, a));
	}

	@Test
	public void testSmallerLargerWithTies() {

		int aSize = random.nextInt(1000) + 1000;
		int bSize = random.nextInt(1000) + 1000;
		List<Double> a = new ArrayList<>(aSize);
		List<Double> b = new ArrayList<>(bSize);

		addTiedValues(a, b);

		for (int i = a.size(); i < aSize; i++) a.add(random.nextDouble());
		for (int i = b.size(); i < bSize; i++) b.add(random.nextDouble() + 0.1);

		assertEquals(SMALLER, UTest.test(a, b));
		assertEquals(LARGER, UTest.test(b, a));
	}

	@Test
	public void testInconclusive() {

		int aSize = random.nextInt(1000) + 1000;
		int bSize = random.nextInt(1000) + 1000;
		List<Double> a = new ArrayList<>(aSize);
		List<Double> b = new ArrayList<>(bSize);

		for (int i = 0; i < aSize; i++) a.add(random.nextDouble());
		for (int i = 0; i < bSize; i++) b.add(random.nextDouble());

		assertEquals(INCONCLUSIVE, UTest.test(a, b));
		assertEquals(INCONCLUSIVE, UTest.test(b, a));
	}

	@Test
	public void testInconclusiveWithTies() {

		int aSize = random.nextInt(1000) + 1000;
		int bSize = random.nextInt(1000) + 1000;
		List<Double> a = new ArrayList<>(aSize);
		List<Double> b = new ArrayList<>(bSize);

		addTiedValues(a, b);

		for (int i = a.size(); i < aSize; i++) a.add(random.nextDouble());
		for (int i = b.size(); i < bSize; i++) b.add(random.nextDouble());

		assertEquals(INCONCLUSIVE, UTest.test(a, b));
		assertEquals(INCONCLUSIVE, UTest.test(b, a));
	}

	private void addTiedValues(List<Double> a, List<Double> b) {
		for (int i = 0; i < 10; i++) {
			double tiedValue = random.nextDouble();
			int numTies = random.nextInt(5) + 1;
			for (int j = 0; j < numTies; j++) {
				if (random.nextBoolean()) a.add(tiedValue);
				else b.add(tiedValue);
			}
		}
	}
}
