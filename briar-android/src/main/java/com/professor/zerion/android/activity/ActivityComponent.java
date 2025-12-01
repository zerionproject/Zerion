package com.professor.zerion.android.activity;

import android.app.Activity;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.StartupFailureActivity;
import com.professor.zerion.android.account.SetupActivity;
import com.professor.zerion.android.account.SetupFragment;
import com.professor.zerion.android.account.UnlockActivity;
import com.professor.zerion.android.contact.ContactListFragment;
import com.professor.zerion.android.contact.add.nearby.AddNearbyContactActivity;
import com.professor.zerion.android.contact.add.nearby.AddNearbyContactErrorFragment;
import com.professor.zerion.android.contact.add.nearby.AddNearbyContactFragment;
import com.professor.zerion.android.contact.add.nearby.AddNearbyContactIntroFragment;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;
import com.professor.zerion.android.contact.add.remote.LinkExchangeFragment;
import com.professor.zerion.android.contact.add.remote.NicknameFragment;
import com.professor.zerion.android.contact.add.remote.PendingContactListActivity;
import com.professor.zerion.android.contact.connect.ConnectViaBluetoothActivity;
import com.professor.zerion.android.conversation.AliasDialogFragment;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.conversation.ConversationSettingsDialog;
import com.professor.zerion.android.conversation.ImageActivity;
import com.professor.zerion.android.conversation.ImageFragment;
import com.professor.zerion.android.fragment.ScreenFilterDialogFragment;
import com.professor.zerion.android.hotspot.HotspotActivity;
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
import com.professor.zerion.android.privategroup.conversation.GroupActivity;
import com.professor.zerion.android.privategroup.creation.CreateGroupActivity;
import com.professor.zerion.android.privategroup.creation.CreateGroupFragment;
import com.professor.zerion.android.privategroup.creation.CreateGroupModule;
import com.professor.zerion.android.privategroup.creation.GroupInviteActivity;
import com.professor.zerion.android.privategroup.creation.GroupInviteFragment;
import com.professor.zerion.android.privategroup.invitation.GroupInvitationActivity;
import com.professor.zerion.android.privategroup.invitation.GroupInvitationModule;
import com.professor.zerion.android.privategroup.list.GroupListFragment;
import com.professor.zerion.android.privategroup.memberlist.GroupMemberListActivity;
import com.professor.zerion.android.privategroup.memberlist.GroupMemberModule;
import com.professor.zerion.android.privategroup.reveal.GroupRevealModule;
import com.professor.zerion.android.privategroup.reveal.RevealContactsActivity;
import com.professor.zerion.android.privategroup.reveal.RevealContactsFragment;
import com.professor.zerion.android.removabledrive.RemovableDriveActivity;
import com.professor.zerion.android.reporting.CrashFragment;
import com.professor.zerion.android.reporting.CrashReportActivity;
import com.professor.zerion.android.reporting.ReportFormFragment;
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
		CreateGroupModule.class,
		GroupInvitationModule.class,
		GroupMemberModule.class,
		GroupRevealModule.class,
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

	void inject(AddNearbyContactActivity activity);

	void inject(ConversationActivity activity);

	void inject(ImageActivity activity);

	void inject(com.professor.zerion.android.vault.ui.VaultActivity activity);


	void inject(CreateGroupActivity activity);

	void inject(GroupActivity activity);

	void inject(GroupInviteActivity activity);

	void inject(GroupInvitationActivity activity);

	void inject(GroupMemberListActivity activity);

	void inject(RevealContactsActivity activity);


	void inject(SettingsActivity activity);

	void inject(TransportsActivity activity);

	void inject(TestDataActivity activity);

	void inject(ChangePasswordActivity activity);

	void inject(IntroductionActivity activity);


	void inject(StartupFailureActivity activity);

	void inject(UnlockActivity activity);

	void inject(AddContactActivity activity);

	void inject(PendingContactListActivity activity);

	void inject(CrashReportActivity crashReportActivity);

	void inject(HotspotActivity hotspotActivity);

	void inject(RemovableDriveActivity activity);

	// Fragments

	void inject(SetupFragment fragment);

	void inject(PasswordFragment imageFragment);

	void inject(OpenDatabaseFragment activity);

	void inject(ContactListFragment fragment);

	void inject(CreateGroupFragment fragment);

	void inject(GroupListFragment fragment);

	void inject(GroupInviteFragment fragment);

	void inject(RevealContactsFragment activity);


	void inject(AddNearbyContactIntroFragment fragment);

	void inject(AddNearbyContactFragment fragment);

	void inject(LinkExchangeFragment fragment);

	void inject(NicknameFragment fragment);

	void inject(ContactChooserFragment fragment);


	void inject(IntroductionMessageFragment fragment);

	void inject(SettingsFragment fragment);

	void inject(ScreenFilterDialogFragment fragment);

	void inject(AddNearbyContactErrorFragment fragment);

	void inject(AliasDialogFragment aliasDialogFragment);

	void inject(ImageFragment imageFragment);

	void inject(ReportFormFragment reportFormFragment);

	void inject(CrashFragment crashFragment);

	void inject(ConfirmAvatarDialogFragment fragment);

	void inject(ConversationSettingsDialog dialog);


	void inject(ConnectViaBluetoothActivity connectViaBluetoothActivity);

}
