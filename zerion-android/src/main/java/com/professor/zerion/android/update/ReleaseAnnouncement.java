package com.professor.zerion.android.update;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ReleaseAnnouncement {

	public final String versionName;
	public final long versionCode;
	public final String apkUrl;
	public final String releasePageUrl;
	public final String apkSha256;

	ReleaseAnnouncement(String versionName, long versionCode, String apkUrl,
			String releasePageUrl, String apkSha256) {
		this.versionName = versionName;
		this.versionCode = versionCode;
		this.apkUrl = apkUrl;
		this.releasePageUrl = releasePageUrl;
		this.apkSha256 = apkSha256;
	}
}
