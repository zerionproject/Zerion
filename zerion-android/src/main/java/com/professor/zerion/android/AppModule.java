package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StrictMode;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.vanniktech.emoji.RecentEmoji;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.reporting.DevConfig;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.PluginConfig;
import org.briarproject.bramble.api.plugin.TorControlPort;
import org.briarproject.bramble.api.plugin.TorDirectory;
import org.briarproject.bramble.api.plugin.TorSocksPort;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.bramble.api.plugin.simplex.SimplexPluginFactory;
import org.briarproject.bramble.plugin.file.AndroidRemovableDrivePluginFactory;
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
import com.professor.zerion.android.privategroup.conversation.GroupConversationModule;
import com.professor.zerion.android.privategroup.list.GroupListModule;
import com.professor.zerion.android.removabledrive.TransferDataModule;
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
		GroupListModule.class,
		GroupConversationModule.class,
		SharingModule.class,
		TransferDataModule.class,
})
public class AppModule {

	@Qualifier
	@Retention(RUNTIME)
	public @interface SecurePrefs {}

	@Qualifier
	@Retention(RUNTIME)
	public @interface UiPrefs {}

	/**
	 * Lazy holder for EncryptedSharedPreferences.
	 * Initialization ideally happens on background thread during eager singleton injection.
	 * When accessed before background init completes (e.g., during SplashScreen injection),
	 * falls back to synchronous init with StrictMode bypass.
	 */
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

		/**
		 * Initialize with StrictMode bypass for fallback scenarios.
		 * Called when prefs are accessed before background init completes.
		 * Bypasses disk read/write and custom slow call (keystore crypto) violations.
		 */
		static void initializeWithStrictModeBypass(Application app) {
			synchronized (lock) {
				if (securePrefs == null) {
					// Save current policy and permit all I/O and slow calls
					// EncryptedSharedPreferences uses Android Keystore which triggers:
					// - DiskRead violations (keystore access)
					// - CustomSlowCall violations (keystore crypto operations)
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
			try {
				MasterKey masterKey = new MasterKey.Builder(app.getApplicationContext())
						.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
						.build();

				securePrefs = EncryptedSharedPreferences.create(
						app.getApplicationContext(),
						"secure_prefs",
						masterKey,
						EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
						EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
				);

				uiPrefs = EncryptedSharedPreferences.create(
						app.getApplicationContext(),
						"ui_prefs",
						masterKey,
						EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
						EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
				);
			} catch (Exception e) {
				throw new RuntimeException("EncryptedSharedPreferences init failed", e);
			}
		}

		static SharedPreferences getSecurePrefs() {
			if (securePrefs == null) {
				throw new IllegalStateException("SecurePrefs not initialized");
			}
			return securePrefs;
		}

		static SharedPreferences getUiPrefs() {
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
			// Initialize encrypted preferences on background thread FIRST
			// This MUST happen before any component accesses the preferences
			SecurePrefsHolder.initialize(application);

			// Now safe to do migration which uses the preferences
			preferencesMigration.migrateVaultSettingsIfNeeded();
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
	DatabaseConfig provideDatabaseConfig(Application app) {
		// Temporarily allow disk access for directory creation
		// This is unavoidable as DatabaseConfig is consumed by bramble-core
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		try {
			StrictMode.allowThreadDiskWrites();
			File dbDir = app.getApplicationContext().getDir("db", MODE_PRIVATE);
			File keyDir = app.getApplicationContext().getDir("key", MODE_PRIVATE);
			KeyStrengthener keyStrengthener = SDK_INT >= 23
					? new AndroidKeyStrengthener() : null;
			return new AndroidDatabaseConfig(dbDir, keyDir, keyStrengthener);
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	@TorDirectory
	File provideTorDirectory(Application app) {
		// Temporarily allow disk access for directory creation
		// This is unavoidable as the File is consumed by bramble-core
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		try {
			StrictMode.allowThreadDiskWrites();
			return app.getDir("tor", MODE_PRIVATE);
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
	}

	@Provides
	@Singleton
	TorPortManager provideTorPortManager(Application app) {
		// TorPortManager handles dynamic port selection to avoid conflicts with Briar
		return new TorPortManager(app);
	}

	@Provides
	@Singleton
	@TorSocksPort
	int provideTorSocksPort(TorPortManager portManager) {
		int port = portManager.getSocksPort();
		// Add offset for debug builds to allow running alongside release
		return IS_DEBUG_BUILD ? port + 2 : port;
	}

	@Provides
	@Singleton
	@TorControlPort
	int provideTorControlPort(TorPortManager portManager) {
		int port = portManager.getControlPort();
		// Add offset for debug builds to allow running alongside release
		return IS_DEBUG_BUILD ? port + 2 : port;
	}

	@Provides
	@Singleton
	PluginConfig providePluginConfig(AndroidTorPluginFactory tor,
			AndroidRemovableDrivePluginFactory drive, FeatureFlags featureFlags) {
		@NotNullByDefault
		PluginConfig pluginConfig = new PluginConfig() {

			@Override
			public Collection<DuplexPluginFactory> getDuplexFactories() {
				return asList(tor);
			}

			@Override
			public Collection<SimplexPluginFactory> getSimplexFactories() {
				List<SimplexPluginFactory> simplex = new ArrayList<>();
				simplex.add(drive);
				return simplex;
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

	/**
	 * Provides SecurePrefs from the lazy holder.
	 * The actual initialization happens on background thread during eager singleton init.
	 * If accessed before initialization, falls back to synchronous init with StrictMode bypass.
	 */
	@Provides
	@Singleton
	@SecurePrefs
	SharedPreferences provideSecurePreferences(Application app) {
		// Try to get from pre-initialized holder first (fast path, no I/O)
		if (SecurePrefsHolder.securePrefs != null) {
			return SecurePrefsHolder.securePrefs;
		}
		// Fallback: initialize synchronously with StrictMode bypass
		// This can happen during Activity injection before app fully starts
		SecurePrefsHolder.initializeWithStrictModeBypass(app);
		return SecurePrefsHolder.getSecurePrefs();
	}

	/**
	 * Provides UiPrefs from the lazy holder.
	 * The actual initialization happens on background thread during eager singleton init.
	 * If accessed before initialization, falls back to synchronous init with StrictMode bypass.
	 */
	@Provides
	@Singleton
	@UiPrefs
	SharedPreferences provideUiPreferences(Application app) {
		// Try to get from pre-initialized holder first (fast path, no I/O)
		if (SecurePrefsHolder.uiPrefs != null) {
			return SecurePrefsHolder.uiPrefs;
		}
		// Fallback: initialize synchronously with StrictMode bypass
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
		// VaultManager -> SecureFileIO constructor calls getNoBackupFilesDir()
		// which does disk I/O, so bypass StrictMode
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
				return true;
			}
		};
	}

	@Provides
	@Singleton
	Thread.UncaughtExceptionHandler provideUncaughtExceptionHandler() {
		return (thread, throwable) -> {
		};
	}

	@Provides
	@Singleton
	DevConfig provideDevConfig(Application app, CryptoComponent crypto) {
		// Temporarily allow disk access for directory creation
		StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
		final File reportDir;
		try {
			StrictMode.allowThreadDiskWrites();
			reportDir = app.getApplicationContext().getDir("reports", MODE_PRIVATE);
		} finally {
			StrictMode.setThreadPolicy(oldPolicy);
		}
		@NotNullByDefault
		DevConfig devConfig = new DevConfig() {
			@Override
			public PublicKey getDevPublicKey() {
				// Return a dummy public key - dev reporting disabled
				try {
					return crypto.getSignatureKeyParser().parsePublicKey(new byte[32]);
				} catch (Exception e) {
					throw new RuntimeException("Failed to create dev public key", e);
				}
			}

			@Override
			public String getDevOnionAddress() {
				return "";
			}

			@Override
			public File getReportDir() {
				return reportDir;
			}

			@Override
			public File getLogcatFile() {
				return new File(reportDir, "logcat.txt");
			}
		};
		return devConfig;
	}
}
