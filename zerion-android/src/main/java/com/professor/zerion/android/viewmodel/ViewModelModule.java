package com.professor.zerion.android.viewmodel;

import com.professor.zerion.android.chat.ChatsViewModel;
import com.professor.zerion.android.contact.add.remote.AddContactViewModel;
import com.professor.zerion.android.contact.add.remote.PendingContactListViewModel;
import com.professor.zerion.android.conversation.ConversationViewModel;
import com.professor.zerion.android.conversation.ImageViewModel;
import com.professor.zerion.android.vault.ui.VaultViewModel;

import javax.inject.Singleton;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class ViewModelModule {

	@Binds
	@IntoMap
	@ViewModelKey(ConversationViewModel.class)
	abstract ViewModel bindConversationViewModel(
			ConversationViewModel conversationViewModel);

	@Binds
	@IntoMap
	@ViewModelKey(ChatsViewModel.class)
	abstract ViewModel bindChatsViewModel(ChatsViewModel chatsViewModel);

	@Binds
	@IntoMap
	@ViewModelKey(ImageViewModel.class)
	abstract ViewModel bindImageViewModel(
			ImageViewModel imageViewModel);

	@Binds
	@IntoMap
	@ViewModelKey(AddContactViewModel.class)
	abstract ViewModel bindAddContactViewModel(
			AddContactViewModel addContactViewModel);

	@Binds
	@IntoMap
	@ViewModelKey(
			com.professor.zerion.android.contact.add.nearby.AddNearbyContactViewModel.class)
	abstract ViewModel bindAddNearbyContactViewModel(
			com.professor.zerion.android.contact.add.nearby.AddNearbyContactViewModel vm);

	@Binds
	@IntoMap
	@ViewModelKey(PendingContactListViewModel.class)
	abstract ViewModel bindPendingRequestsViewModel(
			PendingContactListViewModel pendingContactListViewModel);

	@Binds
	@IntoMap
	@ViewModelKey(VaultViewModel.class)
	abstract ViewModel bindVaultViewModel(
			VaultViewModel vaultViewModel);

	@Binds
	@Singleton
	abstract ViewModelProvider.Factory bindViewModelFactory(
			ViewModelFactory viewModelFactory);

}
