package com.professor.zerion.android.settings;

import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class OwnIdentityInfo {

	private final LocalAuthor localAuthor;
	private final AuthorInfo authorInfo;

	public OwnIdentityInfo(LocalAuthor localAuthor, AuthorInfo authorInfo) {
		this.localAuthor = localAuthor;
		this.authorInfo = authorInfo;
	}

	public LocalAuthor getLocalAuthor() {
		return localAuthor;
	}

	public AuthorInfo getAuthorInfo() {
		return authorInfo;
	}

}