package com.professor.zerion.android;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.briarproject.bramble.BrambleAndroidEagerSingletons;
import org.briarproject.bramble.BrambleAndroidModule;
import org.briarproject.bramble.BrambleAppComponent;
import org.briarproject.bramble.BrambleCoreEagerSingletons;
import org.briarproject.bramble.BrambleCoreModule;
import org.briarproject.bramble.account.AccountModule;
import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactExchangeManager;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.keyagreement.KeyAgreementTask;
import org.briarproject.bramble.api.keyagreement.PayloadEncoder;
import org.briarproject.bramble.api.keyagreement.PayloadParser;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.mailbox.ModularMailboxModule;
import org.briarproject.bramble.plugin.file.RemovableDriveModule;
import org.briarproject.bramble.system.ClockModule;
import org.briarproject.briar.BriarCoreEagerSingletons;
import org.briarproject.briar.BriarCoreModule;
import com.professor.zerion.android.attachment.AttachmentModule;
import com.professor.zerion.android.attachment.media.MediaModule;
import com.professor.zerion.android.contact.connect.BluetoothIntroFragment;
import com.professor.zerion.android.conversation.glide.BriarModelLoader;
import com.professor.zerion.android.hotspot.AbstractTabsFragment;
import com.professor.zerion.android.hotspot.FallbackFragment;
import com.professor.zerion.android.hotspot.HotspotIntroFragment;
import com.professor.zerion.android.hotspot.ManualHotspotFragment;
import com.professor.zerion.android.hotspot.QrHotspotFragment;
import com.professor.zerion.android.logging.CachingLogHandler;
import com.professor.zerion.android.login.SignInReminderReceiver;
import com.professor.zerion.android.removabledrive.ChooserFragment;
import com.professor.zerion.android.removabledrive.ReceiveFragment;
import com.professor.zerion.android.removabledrive.SendFragment;
import com.professor.zerion.android.settings.ConnectionsFragment;
import com.professor.zerion.android.settings.NotificationsFragment;
import com.professor.zerion.android.settings.SecurityFragment;
import com.professor.zerion.android.settings.SettingsFragment;
import com.professor.zerion.android.view.EmojiTextInputView;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.api.ScreenFilterMonitor;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.briar.api.test.TestDataCreator;
import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.LocationUtils;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import androidx.lifecycle.ViewModelProvider;
import dagger.Component;

@Singleton
@Component(modules = {
		BrambleCoreModule.class,
		BriarCoreModule.class,
		BrambleAndroidModule.class,
		AccountModule.class,
		AppModule.class,
		AttachmentModule.class,
		ClockModule.class,
		MediaModule.class,
		ModularMailboxModule.class,
		RemovableDriveModule.class
})
public interface AndroidComponent
		extends BrambleCoreEagerSingletons, BrambleAndroidEagerSingletons,
		BriarCoreEagerSingletons, AndroidEagerSingletons, BrambleAppComponent {

	// Exposed objects
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

	PrivateGroupManager privateGroupManager();

	GroupInvitationFactory groupInvitationFactory();

	GroupInvitationManager groupInvitationManager();

	PrivateGroupFactory privateGroupFactory();

	GroupMessageFactory groupMessageFactory();

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

	CachingLogHandler logHandler();

	Thread.UncaughtExceptionHandler exceptionHandler();

	AutoDeleteManager autoDeleteManager();

	void inject(SignInReminderReceiver briarService);

	void inject(BriarService briarService);

	void inject(NotificationCleanupService notificationCleanupService);

	void inject(EmojiTextInputView textInputView);

	void inject(BriarModelLoader briarModelLoader);

	void inject(SettingsFragment settingsFragment);

	void inject(ConnectionsFragment connectionsFragment);

	void inject(SecurityFragment securityFragment);

	void inject(NotificationsFragment notificationsFragment);

	void inject(HotspotIntroFragment hotspotIntroFragment);

	void inject(AbstractTabsFragment abstractTabsFragment);

	void inject(QrHotspotFragment qrHotspotFragment);

	void inject(ManualHotspotFragment manualHotspotFragment);

	void inject(FallbackFragment fallbackFragment);

	void inject(ChooserFragment chooserFragment);

	void inject(SendFragment sendFragment);

	void inject(ReceiveFragment receiveFragment);

	void inject(BluetoothIntroFragment bluetoothIntroFragment);

}
