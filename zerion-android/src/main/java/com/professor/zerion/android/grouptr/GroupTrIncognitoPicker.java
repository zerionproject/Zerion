package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrIncognitoNames;

import java.security.SecureRandom;
import java.util.Random;

import javax.annotation.Nullable;

@NotNullByDefault
public final class GroupTrIncognitoPicker {

	public interface Answer {

		void join(@Nullable String incognitoName);
	}

	private final Context context;
	private final Random random;
	private final MaterialSwitch toggle;
	private final TextView summary;
	private final View nameRow;
	private final TextView nameView;
	private String name;
	private boolean available = true;

	GroupTrIncognitoPicker(View root, Random random) {
		this.context = root.getContext();
		this.random = random;
		toggle = root.findViewById(R.id.incognitoSwitch);
		summary = root.findViewById(R.id.incognitoSummary);
		nameRow = root.findViewById(R.id.incognitoNameRow);
		nameView = root.findViewById(R.id.incognitoName);
		MaterialButton newName = root.findViewById(R.id.incognitoNewName);
		name = GroupTrIncognitoNames.generate(random);
		showName();
		toggle.setOnCheckedChangeListener((b, checked) ->
				nameRow.setVisibility(checked ? View.VISIBLE : View.GONE));
		newName.setOnClickListener(v -> {
			name = GroupTrIncognitoNames.generate(this.random);
			showName();
		});
	}

	private void showName() {
		nameView.setText(context.getString(R.string.grouptr_incognito_name,
				name));
	}

	void setAvailable(boolean available) {
		this.available = available;
		if (available) return;
		toggle.setChecked(false);
		toggle.setEnabled(false);
		summary.setText(R.string.grouptr_member_invite_contact_outdated);
	}

	boolean isChecked() {
		return toggle.isChecked();
	}

	void setOnCheckedChangeListener(Runnable r) {
		toggle.setOnCheckedChangeListener((b, checked) -> {
			nameRow.setVisibility(checked ? View.VISIBLE : View.GONE);
			r.run();
		});
	}

	@Nullable
	String chosenName() {
		return available && toggle.isChecked() ? name : null;
	}

	public static void askToJoin(Context context, @Nullable String title,
			boolean incognitoAvailable, Answer answer) {
		View root = LayoutInflater.from(context).inflate(
				R.layout.view_grouptr_incognito, null, false);
		int pad = (int) (20 * context.getResources().getDisplayMetrics()
				.density);
		root.setPadding(pad, pad, pad, 0);
		GroupTrIncognitoPicker picker =
				new GroupTrIncognitoPicker(root, new SecureRandom());
		picker.setAvailable(incognitoAvailable);
		SecureAlertDialogBuilder builder = new SecureAlertDialogBuilder(
				context);
		if (title != null) builder.setTitle(title);
		builder.setView(root)
				.setPositiveButton(R.string.grouptr_pending_accept,
						(d, w) -> answer.join(picker.chosenName()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	public static void showIncognitoInfo(Context context, String name) {
		new SecureAlertDialogBuilder(context)
				.setTitle(R.string.grouptr_incognito)
				.setMessage(context.getString(R.string.grouptr_incognito_name,
						name) + "\n\n" + context.getString(
						R.string.grouptr_incognito_summary))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
