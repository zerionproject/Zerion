package com.professor.zerion.android.activity;

import android.app.Activity;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.StartupFailureActivity;
import com.professor.zerion.android.account.SetupActivity;
import com.professor.zerion.android.account.SetupFragment;
import com.professor.zerion.android.account.UnlockActivity;
import com.professor.zerion.android.contact.ContactListFragment;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;
import com.professor.zerion.android.contact.add.remote.AddContactChooserFragment;
import com.professor.zerion.android.contact.add.remote.LinkExchangeFragment;
import com.professor.zerion.android.contact.add.remote.NicknameFragment;
import com.professor.zerion.android.contact.add.remote.PendingContactListActivity;
import com.professor.zerion.android.contact.add.remote.QrExchangeFragment;
import com.professor.zerion.android.conversation.AliasDialogFragment;
import com.professor.zerion.android.conversation.AllMediaActivity;
import com.professor.zerion.android.conversation.ChatSettingsActivity;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.conversation.ConversationSettingsDialog;
import com.professor.zerion.android.conversation.ImageActivity;
import com.professor.zerion.android.conversation.ImageFragment;
import com.professor.zerion.android.conversation.VideoPlayerActivity;
import com.professor.zerion.android.fragment.ScreenFilterDialogFragment;
import com.professor.zerion.android.introduction.ContactChooserFragment;
import com.professor.zerion.android.introduction.IntroductionActivity;
import com.professor.zerion.android.introduction.IntroductionMessageFragment;
import com.professor.zerion.android.login.ChangePasswordActivity;
import com.professor.zerion.android.login.OpenDatabaseFragment;
import com.professor.zerion.android.login.PasswordFragment;
import com.professor.zerion.android.login.StartupActivity;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.navdrawer.TransportsActivity;
import com.professor.zerion.android.panic.PanicPreferencesActivity;
import com.professor.zerion.android.panic.PanicResponderActivity;
import com.professor.zerion.android.settings.ConfirmAvatarDialogFragment;
import com.professor.zerion.android.settings.SettingsActivity;
import com.professor.zerion.android.settings.SettingsFragment;
import com.professor.zerion.android.sharing.SharingModule;
import com.professor.zerion.android.splash.SplashScreenActivity;
import com.professor.zerion.android.test.TestDataActivity;

import dagger.Component;

@ActivityScope
@Component(modules = {
		ActivityModule.class,
		SharingModule.SharingLegacyModule.class
}, dependencies = AndroidComponent.class)
public interface ActivityComponent {

	Activity activity();

	void inject(SplashScreenActivity activity);

	void inject(StartupActivity activity);

	void inject(SetupActivity activity);

	void inject(NavDrawerActivity activity);

	void inject(PanicResponderActivity activity);

	void inject(PanicPreferencesActivity activity);

	void inject(ConversationActivity activity);

	void inject(ChatSettingsActivity activity);

	void inject(AllMediaActivity activity);

	void inject(ImageActivity activity);

	void inject(VideoPlayerActivity activity);

	void inject(com.professor.zerion.android.vault.ui.VaultActivity activity);

	void inject(SettingsActivity activity);

	void inject(TransportsActivity activity);

	void inject(TestDataActivity activity);

	void inject(ChangePasswordActivity activity);

	void inject(IntroductionActivity activity);

	void inject(StartupFailureActivity activity);

	void inject(UnlockActivity activity);

	void inject(AddContactActivity activity);

	void inject(PendingContactListActivity activity);

	void inject(SetupFragment fragment);

	void inject(PasswordFragment imageFragment);

	void inject(OpenDatabaseFragment activity);

	void inject(ContactListFragment fragment);

	void inject(com.professor.zerion.android.grouptr.GroupTrListFragment fragment);

	void inject(com.professor.zerion.android.channel.ChannelListFragment fragment);

	void inject(com.professor.zerion.android.channel.ChannelFeedActivity activity);

	void inject(com.professor.zerion.android.channel.ChannelDelegationsActivity activity);

	void inject(com.professor.zerion.android.channel.ChannelSubscribersActivity activity);

	void inject(com.professor.zerion.android.channel.ChannelCommentsActivity activity);

	void inject(com.professor.zerion.android.channel.ChannelInviteHandlerActivity activity);

	void inject(AddContactChooserFragment fragment);

	void inject(QrExchangeFragment fragment);

	void inject(LinkExchangeFragment fragment);

	void inject(NicknameFragment fragment);

	void inject(ContactChooserFragment fragment);

	void inject(IntroductionMessageFragment fragment);

	void inject(SettingsFragment fragment);

	void inject(ScreenFilterDialogFragment fragment);

	void inject(AliasDialogFragment aliasDialogFragment);

	void inject(ImageFragment imageFragment);

	void inject(ConfirmAvatarDialogFragment fragment);

	void inject(ConversationSettingsDialog dialog);

	void inject(com.professor.zerion.android.vault.ui.VaultDashboardFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultOnboardingFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultUnlockFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultSettingsFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultListFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultGalleryFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultDocumentsFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultPasswordsFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.SecureNoteFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.VaultDocumentViewerFragment fragment);

	void inject(com.professor.zerion.android.vault.ui.TextEditorFragment fragment);

	void inject(com.professor.zerion.android.grouptr.GroupTrAdminActivity activity);

	void inject(com.professor.zerion.android.grouptr.GroupTrConversationActivity activity);

	void inject(com.professor.zerion.android.grouptr.GroupTrCreateActivity activity);

	void inject(com.professor.zerion.android.grouptr.GroupTrInviteMembersActivity activity);

}
