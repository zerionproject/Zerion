package com.professor.zerion.android.privategroup.conversation;

import com.professor.zerion.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public interface GroupConversationModule {

	@Binds
	@IntoMap
	@ViewModelKey(GroupViewModel.class)
	ViewModel bindGroupViewModel(GroupViewModel groupViewModel);

}
