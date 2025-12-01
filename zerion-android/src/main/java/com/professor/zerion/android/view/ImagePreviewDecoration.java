package com.professor.zerion.android.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.State;

@NotNullByDefault
class ImagePreviewDecoration extends ItemDecoration {

	private final int border;

	ImagePreviewDecoration(Context ctx) {
		Resources res = ctx.getResources();
		border = res.getDimensionPixelSize(R.dimen.message_bubble_border);
	}

	@Override
	public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
			State state) {
		if (state.getItemCount() == parent.getChildAdapterPosition(view) + 1) {
			return;
		}
		outRect.right = border;
	}

}
