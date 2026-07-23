package com.professor.zerion.android.test;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import org.zerionproject.app.api.test.TestDataCreator;

import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static com.professor.zerion.android.ZerionApplication.ENTRY_ACTIVITY;

public class TestDataActivity extends ZerionActivity {

	@Inject
	TestDataCreator testDataCreator;

	private TextView contactsTextView;
	private SeekBar contactsSeekBar, messagesSeekBar, avatarsSeekBar;

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setHomeButtonEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setContentView(R.layout.activity_test_data);
		contactsTextView = findViewById(R.id.textViewContactsSb);
		TextView messagesTextView = findViewById(R.id.textViewMessagesSb);
		TextView avatarsTextView = findViewById(R.id.textViewAvatarsSb);
		contactsSeekBar = findViewById(R.id.seekBarContacts);
		messagesSeekBar = findViewById(R.id.seekBarMessages);
		avatarsSeekBar = findViewById(R.id.seekBarAvatars);

		contactsSeekBar.setOnSeekBarChangeListener(
				new AbstractOnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress,
							boolean fromUser) {
						contactsTextView.setText(String.valueOf(progress + 1));
					}
				});

		messagesSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(messagesTextView));
		avatarsSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(avatarsTextView));

		findViewById(R.id.buttonZeroValues).setOnClickListener(v -> {
			contactsSeekBar.setProgress(0);
			messagesSeekBar.setProgress(0);
			avatarsSeekBar.setProgress(0);
		});

		findViewById(R.id.buttonCreateTestData).setOnClickListener(
				v -> createTestData());
	}

	private void createTestData() {
		testDataCreator.createTestData(contactsSeekBar.getProgress() + 1,
				messagesSeekBar.getProgress(), avatarsSeekBar.getProgress());
		Intent intent = new Intent(this, ENTRY_ACTIVITY);
		intent.addFlags(FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
		finish();
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return false;
	}

	private static class OnSeekBarChangeUpdateProgress
			extends AbstractOnSeekBarChangeListener {
		private final TextView textView;

		private OnSeekBarChangeUpdateProgress(TextView textView) {
			this.textView = textView;
		}

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress,
				boolean fromUser) {
			textView.setText(String.valueOf(progress));
		}
	}

	private abstract static class AbstractOnSeekBarChangeListener
			implements OnSeekBarChangeListener {
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
		}
	}
}
