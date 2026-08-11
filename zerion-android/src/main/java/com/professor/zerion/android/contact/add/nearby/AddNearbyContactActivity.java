package com.professor.zerion.android.contact.add.nearby;

import android.os.Bundle;
import android.view.MenuItem;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.appcompat.app.ActionBar;

/** Hosts the offline (nearby) QR pairing screen. */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AddNearbyContactActivity extends ZerionActivity
		implements BaseFragmentListener {

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_fragment_container_toolbar);
		setUpCustomToolbar(false);
		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
			ab.setTitle(R.string.nearby_pairing_title);
		}
		if (state == null) {
			showInitialFragment(AddNearbyContactFragment.newInstance());
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
