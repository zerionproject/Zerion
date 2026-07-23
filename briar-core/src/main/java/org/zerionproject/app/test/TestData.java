package org.zerionproject.app.test;

import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.getRandomString;

public interface TestData {

	String AUTHOR_NAMES[] = {
			"Thales",
			"Pythagoras",
			"Plato",
			"Aristotle",
			"Euclid",
			"Archimedes",
			"Hipparchus",
			"Ptolemy",
			"Sun Tzu",
			"Ibrahim ibn Sinan",
			"Muhammad Al-Karaji",
			"Yang Hui",
			"Ren\u00e9 Descartes",
			"Pierre de Fermat",
			"Blaise Pascal",
			"Jacob Bernoulli",
			"Christian Goldbach",
			"Leonhard Euler",
			"Joseph Louis Lagrange",
			"Pierre-Simon Laplace",
			"Joseph Fourier",
			"Carl Friedrich Gauss",
			"Charles Babbage",
			"George Boole",
			"John Venn",
			"Gottlob Frege",
			"Henri Poincar\u00e9",
			"David Hilbert",
			"Bertrand Russell",
			"John von Neumann",
			"Kurt G\u00f6del",
			"Alan Turing",
			"Beno\u00eet Mandelbrot",
			"John Nash",
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
			getRandomString(MAX_AUTHOR_NAME_LENGTH),
	};

}
