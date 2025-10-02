package com.professor.zerion.android.contactselection;

import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.contact.ContactItemViewHolder;
import com.professor.zerion.android.contact.OnContactClickListener;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

import static com.professor.zerion.android.util.UiUtils.GREY_OUT;

@UiThread
@NotNullByDefault
public abstract class BaseSelectableContactHolder<I extends BaseSelectableContactItem>
		extends ContactItemViewHolder<I> {

	private final CheckBox checkBox;
	protected final TextView info;

	public BaseSelectableContactHolder(View v) {
		super(v);
		checkBox = v.findViewById(R.id.checkBox);
		info = v.findViewById(R.id.infoView);
	}

	@Override
	protected void bind(I item, @Nullable
			OnContactClickListener<I> listener) {
		super.bind(item, listener);

		if (item.isSelected()) {
			checkBox.setChecked(true);
		} else {
			checkBox.setChecked(false);
		}

		if (item.isDisabled()) {
			layout.setEnabled(false);
			grayOutItem(true);
		} else {
			layout.setEnabled(true);
			grayOutItem(false);
		}
	}

	protected void grayOutItem(boolean gray) {
		float alpha = gray ? GREY_OUT : 1f;
		avatar.setAlpha(alpha);
		name.setAlpha(alpha);
		checkBox.setAlpha(alpha);
		info.setAlpha(alpha);
	}

}
