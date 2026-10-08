package com.professor.zerion.android.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class InstallSource {

	private static final Map<String, String> STORES = new HashMap<>();

	static {
		STORES.put("com.android.vending", "Google Play");
		STORES.put("org.fdroid.fdroid", "F-Droid");
		STORES.put("org.fdroid.basic", "F-Droid");
		STORES.put("com.looker.droidify", "F-Droid");
		STORES.put("com.machiav3lli.fdroid", "F-Droid");
		STORES.put("eu.bubu1.fdroidclassic", "F-Droid");
		STORES.put("dev.imranr.obtainium", "Obtainium");
		STORES.put("app.accrescent.client", "Accrescent");
		STORES.put("com.aurora.store", "Aurora Store");
	}

	private InstallSource() {
	}

	@Nullable
	static String storeLabel(Context ctx) {
		return labelFor(installer(ctx));
	}

	@Nullable
	static String labelFor(@Nullable String installerPackage) {
		return installerPackage == null ? null : STORES.get(installerPackage);
	}

	@Nullable
	private static String installer(Context ctx) {
		PackageManager pm = ctx.getPackageManager();
		String pkg = ctx.getPackageName();
		try {
			if (Build.VERSION.SDK_INT >= 30) {
				return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
			}
			return pm.getInstallerPackageName(pkg);
		} catch (Exception e) {
			return null;
		}
	}

	static List<X509Certificate> ownSigners(Context ctx) {
		try {
			PackageInfo pi = ctx.getPackageManager().getPackageInfo(
					ctx.getPackageName(),
					PackageManager.GET_SIGNING_CERTIFICATES);
			SigningInfo si = pi.signingInfo;
			if (si == null) return Collections.emptyList();
			Signature[] signers = si.getApkContentsSigners();
			if (signers == null) return Collections.emptyList();
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			List<X509Certificate> out = new ArrayList<>();
			for (Signature s : signers) {
				out.add((X509Certificate) cf.generateCertificate(
						new ByteArrayInputStream(s.toByteArray())));
			}
			return out;
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}
}
