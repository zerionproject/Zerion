package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class CircumventionProviderFactory {

	public static CircumventionProvider createCircumventionProvider() {
		return new CircumventionProviderImpl();
	}

}
