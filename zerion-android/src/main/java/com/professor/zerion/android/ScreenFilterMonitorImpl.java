package com.professor.zerion.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;

import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.util.StringUtils;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;

import static android.Manifest.permission.SYSTEM_ALERT_WINDOW;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.ACTION_PACKAGE_REPLACED;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.GET_SIGNATURES;
import static android.os.Build.VERSION.SDK_INT;
import static org.zerionproject.core.util.AndroidUtils.registerReceiver;
@NotNullByDefault
class ScreenFilterMonitorImpl implements ScreenFilterMonitor, Service {

	private static class AppDetailsImpl implements AppDetails {
		private final String name;
		private final String packageName;

		AppDetailsImpl(String name, String packageName) {
			this.name = name;
			this.packageName = packageName;
		}

		@Override
		public String getPackageName() {
			return packageName;
		}

		@Override
		public String getName() {
			return name;
		}
	}

	private static final String PLAY_SERVICES_PACKAGE =
			"com.google.android.gms";
	private static final String PLAY_SERVICES_PUBLIC_KEY =
			"30820120300D06092A864886F70D01010105000382010D0030820108" +
					"0282010100AB562E00D83BA208AE0A966F124E29DA11F2AB56D08F58" +
					"E2CCA91303E9B754D372F640A71B1DCB130967624E4656A7776A9219" +
					"3DB2E5BFB724A91E77188B0E6A47A43B33D9609B77183145CCDF7B2E" +
					"586674C9E1565B1F4C6A5955BFF251A63DABF9C55C27222252E875E4" +
					"F8154A645F897168C0B1BFC612EABF785769BB34AA7984DC7E2EA276" +
					"4CAE8307D8C17154D7EE5F64A51A44A602C249054157DC02CD5F5C0E" +
					"55FBEF8519FBE327F0B1511692C5A06F19D18385F5C4DBC2D6B93F68" +
					"CC2979C70E18AB93866B3BD5DB8999552A0E3B4C99DF58FB918BEDC1" +
					"82BA35E003C1B4B10DD244A8EE24FFFD333872AB5221985EDAB0FC0D" +
					"0B145B6AA192858E79020103";

	private static final String PREF_KEY_ALLOWED = "allowedOverlayApps";

	private final PackageManager pm;
	private final Application app;
	private final AndroidExecutor androidExecutor;
	private final SharedPreferences prefs;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Nullable
	private BroadcastReceiver receiver = null;

	@Nullable
	private Collection<AppDetails> cachedApps = null;

	@Inject
	ScreenFilterMonitorImpl(Application app, AndroidExecutor androidExecutor,
			SharedPreferences prefs) {
		pm = app.getPackageManager();
		this.app = app;
		this.androidExecutor = androidExecutor;
		this.prefs = prefs;
	}

	@Override
	@UiThread
	public Collection<AppDetails> getApps() {
		if (cachedApps != null) return cachedApps;
		Set<String> allowed = prefs.getStringSet(PREF_KEY_ALLOWED,
				Collections.emptySet());
		List<AppDetails> apps = new ArrayList<>();
		@SuppressLint("QueryPermissionsNeeded") List<PackageInfo> packageInfos =
				pm.getInstalledPackages(GET_PERMISSIONS);
		for (PackageInfo packageInfo : packageInfos) {
			if (!allowed.contains(packageInfo.packageName)
					&& isOverlayApp(packageInfo)) {
				String name = getAppName(packageInfo);
				apps.add(new AppDetailsImpl(name, packageInfo.packageName));
			}
		}
		Collections.sort(apps, (a, b) -> a.getName().compareTo(b.getName()));
		apps = Collections.unmodifiableList(apps);
		cachedApps = apps;
		return apps;
	}

	@Override
	@UiThread
	public void allowApps(Collection<String> packageNames) {
		cachedApps = null;
		Set<String> allowed = prefs.getStringSet(PREF_KEY_ALLOWED,
				Collections.emptySet());
		Set<String> merged = new HashSet<>(allowed);
		merged.addAll(packageNames);
		prefs.edit().putStringSet(PREF_KEY_ALLOWED, merged).apply();
	}

	private String getAppName(PackageInfo pkgInfo) {
		CharSequence seq = pm.getApplicationLabel(pkgInfo.applicationInfo);
		return seq == null ? pkgInfo.packageName : seq.toString();
	}

	private boolean isOverlayApp(PackageInfo packageInfo) {
		int mask = FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP;
		if ((packageInfo.applicationInfo.flags & mask) != 0) return false;
		if (isPlayServices(packageInfo.packageName)) return false;
		String[] requestedPermissions = packageInfo.requestedPermissions;
		if (requestedPermissions == null) return false;
		if (SDK_INT < 23) {
			int[] flags = packageInfo.requestedPermissionsFlags;
			for (int i = 0; i < requestedPermissions.length; i++) {
				if (requestedPermissions[i].equals(SYSTEM_ALERT_WINDOW)) {
					return flags == null ||
							(flags[i] & REQUESTED_PERMISSION_GRANTED) != 0;
				}
			}
		} else {
			for (String requestedPermission : requestedPermissions) {
				if (requestedPermission.equals(SYSTEM_ALERT_WINDOW)) {
					return true;
				}
			}
		}
		return false;
	}

	@SuppressLint("PackageManagerGetSignatures")
	private boolean isPlayServices(String pkg) {
		if (!PLAY_SERVICES_PACKAGE.equals(pkg)) return false;
		try {
			PackageInfo sigs = pm.getPackageInfo(pkg, GET_SIGNATURES);
			Signature[] signatures = sigs.signatures;
			if (signatures == null || signatures.length != 1) return false;
			CertificateFactory certFactory =
					CertificateFactory.getInstance("X509");
			byte[] signatureBytes = signatures[0].toByteArray();
			InputStream in = new ByteArrayInputStream(signatureBytes);
			X509Certificate cert =
					(X509Certificate) certFactory.generateCertificate(in);
			byte[] publicKeyBytes = cert.getPublicKey().getEncoded();
			String publicKey = StringUtils.toHexString(publicKeyBytes);
			return PLAY_SERVICES_PUBLIC_KEY.equals(publicKey);
		} catch (NameNotFoundException | CertificateException e) {
			return false;
		}
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		androidExecutor.runOnUiThread(() -> {
			IntentFilter filter = new IntentFilter();
			filter.addAction(ACTION_PACKAGE_ADDED);
			filter.addAction(ACTION_PACKAGE_CHANGED);
			filter.addAction(ACTION_PACKAGE_REMOVED);
			filter.addAction(ACTION_PACKAGE_REPLACED);
			filter.addDataScheme("package");
			receiver = new PackageBroadcastReceiver();
			registerReceiver(app, receiver, filter);
			cachedApps = null;
		});
	}

	@Override
	public void stopService() {
		androidExecutor.runOnUiThread(() -> {
			if (receiver != null) app.unregisterReceiver(receiver);
		});
	}

	private class PackageBroadcastReceiver extends BroadcastReceiver {

		@Override
		@UiThread
		public void onReceive(Context context, Intent intent) {
			cachedApps = null;
		}
	}

	@Override
	public AppDetails getInstalledScreenFilter() {
		Collection<AppDetails> apps = getApps();
		if (!apps.isEmpty()) {
			return apps.iterator().next();
		}
		return null;
	}

	@Override	public boolean isScreenFilterPresent() {
		return !getApps().isEmpty();
	}
}
