package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class CircumventionProviderFactory {

	private CircumventionProviderFactory() {
	}

	public static CircumventionProvider createCircumventionProvider() {
		return new CircumventionProviderImpl();
	}
}
