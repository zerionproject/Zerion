package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;

import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrState;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrCreateActivity extends ZerionActivity {

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private TextInputEditText nameInput;
	private TextInputEditText nicknameInput;
	private TextView disappearingValue;
	private MaterialSwitch screenshotSwitch;
	private MaterialButton createButton;
	private long ttlSeconds = 0L;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_grouptr_create);

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> finish());

		nameInput = findViewById(R.id.groupNameInput);
		nicknameInput = findViewById(R.id.nicknameInput);
		disappearingValue = findViewById(R.id.disappearingValue);
		screenshotSwitch = findViewById(R.id.screenshotSwitch);
		createButton = findViewById(R.id.createButton);
		LinearLayout disappearingRow = findViewById(R.id.disappearingRow);

		createButton.setEnabled(false);
		nameInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				createButton.setEnabled(s.toString().trim().length() > 0);
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		disappearingRow.setOnClickListener(v -> showDisappearingPicker());
		screenshotSwitch.setOnCheckedChangeListener((b, checked) -> {
			if (checked) getWindow().addFlags(
					android.view.WindowManager.LayoutParams.FLAG_SECURE);
			else getWindow().clearFlags(
					android.view.WindowManager.LayoutParams.FLAG_SECURE);
		});

		createButton.setOnClickListener(v -> onCreateTapped());
	}

	private void showDisappearingPicker() {
		String[] labels = new String[] {
				getString(R.string.grouptr_ttl_off),
				getString(R.string.grouptr_ttl_1h),
				getString(R.string.grouptr_ttl_1day),
				getString(R.string.grouptr_ttl_7days),
				getString(R.string.grouptr_ttl_30days)
		};
		long[] values = new long[] { 0L, 3600L, 86400L, 7 * 86400L, 30 * 86400L };
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.grouptr_create_disappearing)
				.setItems(labels, (d, w) -> {
					ttlSeconds = values[w];
					disappearingValue.setText(labels[w]);
				})
				.show();
	}

	private void onCreateTapped() {
		String name = nameInput.getText() == null ? "" :
				nameInput.getText().toString().trim();
		if (name.isEmpty()) return;
		String nickname = nicknameInput.getText() == null ? "" :
				nicknameInput.getText().toString().trim();
		createButton.setEnabled(false);
		boolean screenshotBlock = screenshotSwitch.isChecked();
		long ttl = ttlSeconds;
		ioExecutor.execute(() -> {
			try {
				GroupTrState s = groupTrManager.createGroup(name);
				byte[] gid = s.getGroupId();
				if (!nickname.isEmpty()) {
					groupTrManager.setStealthName(gid, nickname);
				}
				if (ttl > 0L) {
					groupTrManager.setGroupAutoDeleteTimer(gid,
							ttl * 1000L);
				}
				if (screenshotBlock) {
					groupTrManager.setLocalScreenshotBlocked(gid, true);
				}
				runOnUiThread(() -> {
					Intent i = GroupTrInviteMembersActivity.intent(this, gid);
					startActivity(i);
					finish();
				});
			} catch (Throwable t) {
				runOnUiThread(() -> {
					Toast.makeText(this, R.string.grouptr_error_create,
							Toast.LENGTH_SHORT).show();
					createButton.setEnabled(true);
				});
			}
		});
	}

	public static Intent intent(android.content.Context ctx) {
		return new Intent(ctx, GroupTrCreateActivity.class);
	}
}
