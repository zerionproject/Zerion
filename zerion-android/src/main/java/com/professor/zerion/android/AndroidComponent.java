package com.professor.zerion.android;

import android.content.SharedPreferences;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.core.BrambleAndroidEagerSingletons;
import org.zerionproject.core.BrambleAndroidModule;
import org.zerionproject.core.BrambleAppComponent;
import org.zerionproject.core.BrambleCoreEagerSingletons;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.AndroidDatabaseModule;
import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.keyagreement.KeyAgreementTask;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.zerionproject.core.api.keyagreement.PayloadParser;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.ClockModule;
import org.zerionproject.app.BriarCoreEagerSingletons;
import org.zerionproject.app.BriarCoreModule;
import com.professor.zerion.android.attachment.AttachmentModule;
import com.professor.zerion.android.attachment.media.MediaModule;
import com.professor.zerion.android.conversation.glide.ZerionModelLoader;
import com.professor.zerion.android.login.SignInReminderReceiver;
import com.professor.zerion.android.navdrawer.TorStatusFragment;
import com.professor.zerion.android.settings.ConnectionsFragment;
import com.professor.zerion.android.settings.NotificationsFragment;
import com.professor.zerion.android.settings.SecurityFragment;
import com.professor.zerion.android.settings.SettingsFragment;
import com.professor.zerion.android.view.EmojiTextInputView;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.app.api.introduction.IntroductionManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.test.TestDataCreator;
import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.LocationUtils;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import androidx.lifecycle.ViewModelProvider;
import dagger.Component;

@Singleton
@Component(modules = {
		BrambleCoreModule.class,
		AndroidDatabaseModule.class,
		BriarCoreModule.class,
		BrambleAndroidModule.class,
		AppModule.class,
		AttachmentModule.class,
		ClockModule.class,
		MediaModule.class,
		org.zerionproject.transport.ZerionTransportModule.class,
		org.zerionproject.core.ZerionTorWrapperModule.class,
		ZerionTorModule.class
})
public interface AndroidComponent
		extends BrambleCoreEagerSingletons, BrambleAndroidEagerSingletons,
		BriarCoreEagerSingletons, AndroidEagerSingletons, BrambleAppComponent {

	@CryptoExecutor
	Executor cryptoExecutor();

	PasswordStrengthEstimator passwordStrengthIndicator();

	@DatabaseExecutor
	Executor databaseExecutor();

	TransactionManager transactionManager();

	MessageTracker messageTracker();

	LifecycleManager lifecycleManager();

	IdentityManager identityManager();

	AttachmentReader attachmentReader();

	AuthorManager authorManager();

	PluginManager pluginManager();

	EventBus eventBus();

	AndroidNotificationManager androidNotificationManager();

	ScreenFilterMonitor screenFilterMonitor();

	ConnectionRegistry connectionRegistry();

	ContactManager contactManager();

	ConversationManager conversationManager();

	MessagingManager messagingManager();

	PrivateMessageFactory privateMessageFactory();

	VoiceSignalFactory voiceSignalFactory();

	GroupTrManager groupTrManager();

	org.zerionproject.app.api.channel.ChannelManager channelManager();

	SettingsManager settingsManager();

	ContactExchangeManager contactExchangeManager();

	KeyAgreementTask keyAgreementTask();

	PayloadEncoder payloadEncoder();

	PayloadParser payloadParser();

	IntroductionManager introductionManager();

	AndroidExecutor androidExecutor();

	Clock clock();

	TestDataCreator testDataCreator();

	DozeWatchdog dozeWatchdog();

	@IoExecutor
	Executor ioExecutor();

	AccountManager accountManager();

	LockManager lockManager();

	LocationUtils locationUtils();

	CircumventionProvider circumventionProvider();

	ViewModelProvider.Factory viewModelFactory();

	FeatureFlags featureFlags();

	AndroidWakeLockManager wakeLockManager();

	Thread.UncaughtExceptionHandler exceptionHandler();

	AutoDeleteManager autoDeleteManager();

	org.zerionproject.app.conversation.voice.VoiceCallConnectionManager voiceCallConnectionManager();

	org.zerionproject.app.conversation.voice.VoiceCallCrypto voiceCallCrypto();

	com.professor.zerion.android.vault.VaultManager vaultManager();

	com.professor.zerion.android.security.SecurityManager securityManager();

	com.professor.zerion.android.network.TorStatusMonitor torStatusMonitor();

	@org.zerionproject.core.api.plugin.TorSocksPort
	int torSocksPort();

	com.professor.zerion.android.donation.DonationManager donationManager();

	@AppModule.UiPrefs
	SharedPreferences uiPreferences();

	@AppModule.SecurePrefs
	SharedPreferences securePreferences();

	void inject(SignInReminderReceiver briarService);

	void inject(NotificationQuickReplyReceiver receiver);

	void inject(ZerionService briarService);

	void inject(NotificationCleanupService notificationCleanupService);

	void inject(EmojiTextInputView textInputView);

	void inject(ZerionModelLoader briarModelLoader);

	void inject(SettingsFragment settingsFragment);

	void inject(ConnectionsFragment connectionsFragment);

	void inject(com.professor.zerion.android.settings.BackupFragment backupFragment);

	void inject(com.professor.zerion.android.settings.TransferReceiveFragment transferReceiveFragment);

	void inject(com.professor.zerion.android.settings.TransferSendFragment transferSendFragment);

	void inject(com.professor.zerion.android.account.WelcomeFragment welcomeFragment);

	void inject(SecurityFragment securityFragment);

	void inject(com.professor.zerion.android.settings.ProfilesFragment profilesFragment);

	void inject(NotificationsFragment notificationsFragment);

	void inject(TorStatusFragment torStatusFragment);

	void inject(com.professor.zerion.android.panic.PanicPreferencesFragment panicPreferencesFragment);

	void inject(com.professor.zerion.android.settings.DisplayFragment displayFragment);

	void inject(com.professor.zerion.android.splash.ExpiredActivity expiredActivity);

}
