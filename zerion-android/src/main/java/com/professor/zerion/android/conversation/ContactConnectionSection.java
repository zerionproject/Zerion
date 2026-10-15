package com.professor.zerion.android.conversation;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ContactConnectionLines.Line;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import androidx.annotation.UiThread;

@NotNullByDefault
final class ContactConnectionSection {

	private final LinearLayout rows;

	ContactConnectionSection(LinearLayout rows) {
		this.rows = rows;
	}

	@UiThread
	void show(List<Line> lines) {
		Resources r = rows.getResources();
		LayoutInflater inflater = LayoutInflater.from(rows.getContext());
		while (rows.getChildCount() < lines.size()) {
			rows.addView(inflater.inflate(
					R.layout.list_item_contact_connection, rows, false));
		}
		for (int i = 0; i < rows.getChildCount(); i++) {
			TextView row = (TextView) rows.getChildAt(i);
			if (i < lines.size()) {
				String text = ContactConnectionLines.format(r, lines.get(i));
				if (!text.contentEquals(row.getText())) row.setText(text);
				row.setVisibility(View.VISIBLE);
			} else {
				row.setVisibility(View.GONE);
			}
		}
	}
}
