package org.zerionproject.app.test;

import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.identity.LocalAuthor;

import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.getRandomString;

public class BriarTestUtils {

	public static Author getRealAuthor(AuthorFactory authorFactory) {
		String name = getRandomString(MAX_AUTHOR_NAME_LENGTH);
		return authorFactory.createLocalAuthor(name);
	}

	public static LocalAuthor getRealLocalAuthor(AuthorFactory authorFactory) {
		String name = getRandomString(MAX_AUTHOR_NAME_LENGTH);
		return authorFactory.createLocalAuthor(name);
	}

}
