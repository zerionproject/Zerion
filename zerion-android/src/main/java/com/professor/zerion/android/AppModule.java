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
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.mailbox.MailboxDirectory;
import org.briarproject.bramble.api.plugin.PluginConfig;
import org.briarproject.bramble.api.plugin.TorControlPort;
import org.briarproject.bramble.api.plugin.TorDirectory;
import org.briarproject.bramble.api.plugin.TorSocksPort;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.bramble.api.plugin.simplex.SimplexPluginFactory;
import org.briarproject.bramble.plugin.file.AndroidRemovableDrivePluginFactory;
import org.briarproject.bramble.plugin.file.MailboxPluginFactory;
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
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_CONTROL_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_SOCKS_PORT;
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

	static class EagerSingletons {
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
		StrictMode.ThreadPolicy tp = StrictMode.allowThreadDiskReads();
		StrictMode.allowThreadDiskWrites();
		File dbDir = app.getApplicationContext().getDir("db", MODE_PRIVATE);
		File keyDir = app.getApplicationContext().getDir("key", MODE_PRIVATE);
		StrictMode.setThreadPolicy(tp);
		KeyStrengthener keyStrengthener = SDK_INT >= 23
				? new AndroidKeyStrengthener() : null;
		return new AndroidDatabaseConfig(dbDir, keyDir, keyStrengthener);
	}

	@Provides
	@Singleton
	@MailboxDirectory
	File provideMailboxDirectory(Application app) {
		return app.getDir("mailbox", MODE_PRIVATE);
	}

	@Provides
	@Singleton
	@TorDirectory
	File provideTorDirectory(Application app) {
		return app.getDir("tor", MODE_PRIVATE);
	}

	@Provides
	@Singleton
	@TorSocksPort
	int provideTorSocksPort() {
		if (!IS_DEBUG_BUILD) {
			return DEFAULT_SOCKS_PORT;
		} else {
			return DEFAULT_SOCKS_PORT + 2;
		}
	}

	@Provides
	@Singleton
	@TorControlPort
	int provideTorControlPort() {
		if (!IS_DEBUG_BUILD) {
			return DEFAULT_CONTROL_PORT;
		} else {
			return DEFAULT_CONTROL_PORT + 2;
		}
	}

	@Provides
	@Singleton
	PluginConfig providePluginConfig(AndroidTorPluginFactory tor,
			AndroidRemovableDrivePluginFactory drive,
			MailboxPluginFactory mailbox, FeatureFlags featureFlags) {
		@NotNullByDefault
		PluginConfig pluginConfig = new PluginConfig() {

			@Override
			public Collection<DuplexPluginFactory> getDuplexFactories() {
				return asList(tor);
			}

			@Override
			public Collection<SimplexPluginFactory> getSimplexFactories() {
				List<SimplexPluginFactory> simplex = new ArrayList<>();
				simplex.add(mailbox);
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

	@Provides
	@Singleton
	@SecurePrefs
	SharedPreferences provideSecurePreferences(Application app) {
		StrictMode.ThreadPolicy tp = StrictMode.allowThreadDiskReads();
		StrictMode.allowThreadDiskWrites();
		try {
			MasterKey masterKey = new MasterKey.Builder(app.getApplicationContext())
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();

			return EncryptedSharedPreferences.create(
					app.getApplicationContext(),
					"secure_prefs",
					masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
			);
		} catch (Exception e) {
			throw new RuntimeException("EncryptedSharedPreferences init failed", e);
		} finally {
			StrictMode.setThreadPolicy(tp);
		}
	}

	@Provides
	@Singleton
	@UiPrefs
	SharedPreferences provideUiPreferences(Application app) {
		StrictMode.ThreadPolicy tp = StrictMode.allowThreadDiskReads();
		StrictMode.allowThreadDiskWrites();
		try {
			MasterKey masterKey = new MasterKey.Builder(app.getApplicationContext())
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();

			return EncryptedSharedPreferences.create(
					app.getApplicationContext(),
					"ui_prefs",
					masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
			);
		} catch (Exception e) {
			throw new RuntimeException("EncryptedSharedPreferences init failed", e);
		} finally {
			StrictMode.setThreadPolicy(tp);
		}
	}

	@Provides
	@Singleton
	SharedPreferences provideSharedPreferences(@SecurePrefs SharedPreferences securePrefs) {
		return securePrefs;
	}

	@Provides
	@Singleton
	VaultManager provideVaultManager(Context context) {
		return new VaultManager(context);
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
	TorStatusMonitor provideTorStatusMonitor(Context context) {
		return new TorStatusMonitor(context);
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
}
