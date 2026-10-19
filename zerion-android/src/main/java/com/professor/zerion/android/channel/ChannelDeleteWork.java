package com.professor.zerion.android.channel;

import android.app.Activity;
import android.content.Context;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.lang.ref.WeakReference;

import javax.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class ChannelDeleteWork extends ViewModel {

	final MutableLiveData<ChannelDeleteFlow.Outcome> outcome =
			new MutableLiveData<>();
	volatile boolean running = false;
	volatile boolean here = false;
	boolean offerHere = false;
	String name = "";
	private volatile WeakReference<Activity> host = new WeakReference<>(null);
	@Nullable
	private volatile Context app;
	private volatile boolean cleared = false;

	void attach(Activity activity) {
		host = new WeakReference<>(activity);
		app = activity.getApplicationContext();
	}

	@Nullable
	Context context() {
		Activity a = host.get();
		if (a != null && !a.isDestroyed()) return a;
		return app;
	}

	boolean isCleared() {
		return cleared;
	}

	@Nullable
	Context appContext() {
		return app;
	}

	@Override
	protected void onCleared() {
		cleared = true;
	}
}
