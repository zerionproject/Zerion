package com.professor.zerion.android.contact.add.nearby;

import com.professor.zerion.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class AddNearbyContactModule {

	@Binds
	@IntoMap
	@ViewModelKey(AddNearbyContactViewModel.class)
	abstract ViewModel bindContactExchangeViewModel(
			AddNearbyContactViewModel addNearbyContactViewModel);

}
