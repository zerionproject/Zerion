package com.professor.zerion.android.viewmodel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

@Singleton
@NotNullByDefault
class ViewModelFactory implements ViewModelProvider.Factory {

	private final Map<Class<? extends ViewModel>, Provider<ViewModel>> creators;

	@Inject
	ViewModelFactory(Map<Class<? extends ViewModel>,
			Provider<ViewModel>> creators) {
		this.creators = creators;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ViewModel> T create(Class<T> modelClass) {
		Provider<? extends ViewModel> creator = creators.get(modelClass);
		if (creator == null) {
			for (Entry<Class<? extends ViewModel>, Provider<ViewModel>> entry :
					creators.entrySet()) {
				if (modelClass.isAssignableFrom(entry.getKey())) {
					creator = entry.getValue();
					break;
				}
			}
		}
		if (creator == null) {
			throw new IllegalArgumentException(
					"unknown model class " + modelClass);
		}
		return (T) creator.get();
	}

}
