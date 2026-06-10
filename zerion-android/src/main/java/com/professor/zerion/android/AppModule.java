package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StrictMode;

import com.professor.zerion.android.security.ZerionEncryptedPrefs;

import com.vanniktech.emoji.RecentEmoji;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.PluginConfig;
import org.briarproject.bramble.api.plugin.TorControlPort;
import org.briarproject.bramble.api.plugin.TorDirectory;
import org.briarproject.bramble.api.plugin.TorSocksPort;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.bramble.api.plugin.simplex.SimplexPluginFactory;
import org.briarproject.bramble.plugin.tor.AndroidTorPluginFactory;
import org.briarproject.bramble.util.AndroidUtils;
import org.briarproject.bramble.util.StringUtils;
import com.professor.zerion.android.account.DozeHelperModule;
import com.professor.zerion.android.account.LockManagerImpl;
import com.professor.zerion.android.account.SetupModule;
import com.professor.zerion.android.contact.ContactListModule;
import com.professor.zerion.android.introduction.IntroductionModule;
import com.professor.zerion.android.login.LoginModule;
import com.professor.zerion.android.navdrawer.NavDrawerModule;
import org.briarproject.bramble.account.AndroidAccountManager;
import org.briarproject.bramble.account.ProfileManager;
import org.briarproject.bramble.api.account.AccountManager;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.security.SecurityManager;
import com.professor.zerion.android.security.AntiForensics;
import com.professor.zerion.android.network.TorStatusMonitor;
import com.professor.zerion.android.settings.SettingsModule;
import com.professor.zerion.android.sharing.SharingModule;
import com.professor.zerion.android.test.TestAvatarCreatorImpl;
import com.professor.zerion.android.util.TorPortManager;
import com.professor.zerion.android.viewmodel.ViewModelModule;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.api.NetworkUsageMetrics;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import org.briarproject.briar.api.test.TestAvatarCreator;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.lang.annotation.Retention;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static android.content.Context.MODE_PRIVATE;
import static android.os.Build.VERSION.SDK_INT;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;

@Module(includes = {
		SetupModule.class,
		DozeHelperModule.class,
		LoginModule.class,
		NavDrawerModule.class,
		ViewModelModule.class,
		SettingsModule.class,
		ContactListModule.class,
		IntroductionModule.class,
		SharingModule.class,
})
public class AppModule {

	@Qualifier
	@Retention(RUNTIME)
	public @interface SecurePrefs {}

	@Qualifier
	@Retention(RUNTIME)
	public @interface UiPrefs {}

	public static SharedPreferences getUiPrefs() {
		return SecurePrefsHolder.getUiPrefs();
	}

	static class SecurePrefsHolder {
		private static volatile SharedPreferences securePrefs;
		private static volatile SharedPreferences uiPrefs;
		private static final Object lock = new Object();

		static void initialize(Application app) {
			synchronized (lock) {
				if (securePrefs == null) {
					initializeInternal(app);
				}
			}
		}

		static void initializeWithStrictModeBypass(Application app) {
			synchronized (lock) {
				if (securePrefs == null) {
					StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
					StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(oldPolicy)
							.permitDiskReads()
							.permitDiskWrites()
							.permitCustomSlowCalls()
							.build());
					try {
						initializeInternal(app);
					} finally {
						StrictMode.setThreadPolicy(oldPolicy);
					}
				}
			}
		}

		private static void initializeInternal(Application app) {
			Context ctx = app.getApplicationContext();
			securePrefs = ZerionEncryptedPrefs.create(ctx, "secure_prefs");
			uiPrefs = ZerionEncryptedPrefs.create(ctx, "ui_prefs");
		}

		static SharedPreferences getSecurePrefs() {
			if (securePrefs == null) {
				throw new IllegalStateException("SecurePrefs not initialized");
			}
			return securePrefs;
		}

		public static SharedPreferences getUiPrefs() {
			if (uiPrefs == null) {
				throw new IllegalStateException("UiPrefs not initialized");
			}
			return uiPrefs;
		}
	}

	static class EagerSingletons {
		@Inject
		Application application;
		@Inject
		AndroidNotificationManager androidNotificationManager;
		@Inject
		ScreenFilterMonitor screenFilterMonitor;
		@Inject
		NetworkUsageMetrics networkUsageMetrics;
		@Inject
		DozeWatchdog dozeWatchdog;
		@Inject
		LockManager lockManager;
		@Inject
		RecentEmoji recentEmoji;
		@Inject
		com.professor.zerion.android.vault.PreferencesMigration preferencesMigration;

		@Inject
		void init() {
			SecurePrefsHolder.initialize(application);
			preferencesMigration.migrateVaultSettingsIfNeeded();
			pinTorBinary(application);
		}

		private static void pinTorBinary(Application app) {
			try {
				android.content.SharedPreferences prefs =
						SecurePrefsHolder.getSecurePrefs();
				if (prefs == null) return;
				int versionCode;
				try {
					versionCode = app.getPackageManager().getPackageInfo(
							app.getPackageName(), 0).versionCode;
				} catch (android.content.pm.PackageManager
						.NameNotFoundException e) {
					return;
				}
				String nativeLibDir = app.getApplicationInfo().nativeLibraryDir;
				if (nativeLibDir == null) return;
				java.io.File dir = new java.io.File(nativeLibDir);
				java.io.File[] files = dir.listFiles();
				if (files == null) return;
				for (java.io.File f : files) {
					String n = f.getName();
					if (n.startsWith("libtor") || n.startsWith("liblyrebird")) {
						com.professor.zerion.android.security
								.TorBinaryIntegrity.verifyOrPin(prefs, f,
								versionCode);
					}
				}
			} catch (com.professor.zerion.android.security
					.TorBinaryIntegrity.IntegrityException tampered) {
				throw tampered;
			} catch (RuntimeException ignored) {
			}
		}
	}

	private final Application application;

	public AppModule(Application application) {
		this.application = application;
	}

	public static AndroidComponent getAndroidComponent(Context ctx) {
		ZerionApplication app = (ZerionApplication) ctx.getApplicationContext();
		return app.getApplicationComponent();
	}

	@Provides
	@Singleton
	Application providesApplication() {
		return application;
	}

	@Provides
	@Singleton
	Context provideContext() {
		return application.getApplicationContext();
	}

	@Provides
	@Singleton
	ProfileManager provideProfileManager(Application app) {
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		try {
			StrictMode.allowThreadDiskWrites();
			return new ProfileManager(app.getApplicationContext());
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	AccountManager provideAccountManager(AndroidAccountManager am) {
		return am;
	}

	@Provides
	@Singleton
	DatabaseConfig provideDatabaseConfig(ProfileManager profileManager) {
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		try {
			StrictMode.allowThreadDiskWrites();
			KeyStrengthener keyStrengthener = SDK_INT >= 23
					? new AndroidKeyStrengthener() : null;
			return new AndroidDatabaseConfig(profileManager, keyStrengthener);
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	@TorDirectory
	File provideTorDirectory(ProfileManager profileManager) {
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		try {
			StrictMode.allowThreadDiskWrites();
			return profileManager.getActiveTorDir();
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	TorPortManager provideTorPortManager(Application app,
			@SecurePrefs SharedPreferences securePrefs) {
		File prefsDir = new File(app.getApplicationInfo().dataDir,
				"shared_prefs");
		File oldFile = new File(prefsDir, "zerion_tor_ports.xml");
		if (oldFile.exists()) {
			app.getSharedPreferences("zerion_tor_ports", MODE_PRIVATE)
					.edit().clear().commit();
			oldFile.delete();
		}
		return new TorPortManager(securePrefs);
	}

	@Provides
	@Singleton
	@TorSocksPort
	int provideTorSocksPort(TorPortManager portManager) {
		int port = portManager.getSocksPort();
		return IS_DEBUG_BUILD ? port + 2 : port;
	}

	@Provides
	@Singleton
	@TorControlPort
	int provideTorControlPort(TorPortManager portManager) {
		int port = portManager.getControlPort();
		return IS_DEBUG_BUILD ? port + 2 : port;
	}

	@Provides
	@Singleton
	PluginConfig providePluginConfig(AndroidTorPluginFactory tor,
			FeatureFlags featureFlags) {
		@NotNullByDefault
		PluginConfig pluginConfig = new PluginConfig() {

			@Override
			public Collection<DuplexPluginFactory> getDuplexFactories() {
				return asList(tor);
			}

			@Override
			public Collection<SimplexPluginFactory> getSimplexFactories() {
				return Collections.emptyList();
			}

			@Override
			public boolean shouldPoll() {
				return true;
			}

			@Override
			public Map<TransportId, List<TransportId>> getTransportPreferences() {
				return Collections.emptyMap();
			}
		};
		return pluginConfig;
	}

	@Provides
	TestAvatarCreator provideTestAvatarCreator(
			TestAvatarCreatorImpl testAvatarCreator) {
		return testAvatarCreator;
	}

	@Provides
	@Singleton
	@SecurePrefs
	SharedPreferences provideSecurePreferences(Application app) {
		if (SecurePrefsHolder.securePrefs != null) {
			return SecurePrefsHolder.securePrefs;
		}
		SecurePrefsHolder.initializeWithStrictModeBypass(app);
		return SecurePrefsHolder.getSecurePrefs();
	}

	@Provides
	@Singleton
	@UiPrefs
	SharedPreferences provideUiPreferences(Application app) {
		if (SecurePrefsHolder.uiPrefs != null) {
			return SecurePrefsHolder.uiPrefs;
		}
		SecurePrefsHolder.initializeWithStrictModeBypass(app);
		return SecurePrefsHolder.getUiPrefs();
	}

	@Provides
	@Singleton
	SharedPreferences provideSharedPreferences(@SecurePrefs SharedPreferences securePrefs) {
		return securePrefs;
	}

	@Provides
	@Singleton
	VaultManager provideVaultManager(Context context) {
		StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
		StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(oldPolicy)
				.permitDiskReads()
				.permitDiskWrites()
				.build());
		try {
			return new VaultManager(context);
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	SecurityManager provideSecurityManager(Application app,
			@UiPrefs SharedPreferences uiPrefs) {
		return new SecurityManager(app, uiPrefs);
	}

	@Provides
	@Singleton
	AntiForensics provideAntiForensics(Context context) {
		return new AntiForensics(context);
	}

	@Provides
	@Singleton
	TorStatusMonitor provideTorStatusMonitor(Context context,
			@TorSocksPort int torSocksPort) {
		return new TorStatusMonitor(context, torSocksPort);
	}

	@Provides
	@Singleton
	AndroidNotificationManager provideAndroidNotificationManager(
			LifecycleManager lifecycleManager, EventBus eventBus,
			AndroidNotificationManagerImpl notificationManager) {
		lifecycleManager.registerService(notificationManager);
		eventBus.addListener(notificationManager);
		return notificationManager;
	}

	@Provides
	@Singleton
	ScreenFilterMonitor provideScreenFilterMonitor(
			LifecycleManager lifecycleManager,
			ScreenFilterMonitorImpl screenFilterMonitor) {
		if (SDK_INT <= 29) {
			lifecycleManager.registerService(screenFilterMonitor);
		}
		return screenFilterMonitor;
	}

	@Provides
	@Singleton
	NetworkUsageMetrics provideNetworkUsageMetrics(
			LifecycleManager lifecycleManager) {
		NetworkUsageMetricsImpl networkUsageMetrics = new NetworkUsageMetricsImpl();
		lifecycleManager.registerService(networkUsageMetrics);
		return networkUsageMetrics;
	}

	@Provides
	@Singleton
	DozeWatchdog provideDozeWatchdog(LifecycleManager lifecycleManager) {
		DozeWatchdogImpl dozeWatchdog = new DozeWatchdogImpl(application);
		lifecycleManager.registerService(dozeWatchdog);
		return dozeWatchdog;
	}

	@Provides
	@Singleton
	LockManager provideLockManager(LifecycleManager lifecycleManager,
			EventBus eventBus, LockManagerImpl lockManager) {
		lifecycleManager.registerService(lockManager);
		eventBus.addListener(lockManager);
		return lockManager;
	}

	@Provides
	@Singleton
	RecentEmoji provideRecentEmoji(LifecycleManager lifecycleManager,
			RecentEmojiImpl recentEmoji) {
		lifecycleManager.registerOpenDatabaseHook(recentEmoji);
		return recentEmoji;
	}

	@Provides
	FeatureFlags provideFeatureFlags() {
		return new FeatureFlags() {

			@Override
			public boolean shouldEnableImageAttachments() {
				return true;
			}

			@Override
			public boolean shouldEnableProfilePictures() {
				return true;
			}

			@Override
			public boolean shouldEnableDisappearingMessages() {
				return true;
			}

			@Override
			public boolean shouldEnablePrivateGroupsInCore() {
				return false;
			}
		};
	}

	@Provides
	@Singleton
	Thread.UncaughtExceptionHandler provideUncaughtExceptionHandler() {
		return (thread, throwable) -> {
		};
	}

}
