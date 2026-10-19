package com.professor.zerion.android.homelist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.ColorInt;
import androidx.recyclerview.widget.RecyclerView;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@NotNullByDefault
final class HomeListDivider extends RecyclerView.ItemDecoration {

	static final int START_INSET_DP = 80;

	private final Paint paint = new Paint();
	private final int height;
	private final int inset;

	HomeListDivider(Context ctx) {
		float density = ctx.getResources().getDisplayMetrics().density;
		height = Math.max(1, (int) (0.5f * density));
		inset = (int) (START_INSET_DP * density);
		paint.setColor(resolveColorAttribute(ctx, R.attr.zerionColorDivider));
	}

	@ColorInt
	int getColor() {
		return paint.getColor();
	}

	int getHeight() {
		return height;
	}

	static boolean dividesBelow(RecyclerView.Adapter<?> adapter,
			int position) {
		if (position == RecyclerView.NO_POSITION) return false;
		if (position + 1 >= adapter.getItemCount()) return false;
		return adapter.getItemViewType(position) == HomeItem.TYPE_ROW
				&& adapter.getItemViewType(position + 1) == HomeItem.TYPE_ROW;
	}

	@Override
	public void getItemOffsets(Rect out, View view, RecyclerView parent,
			RecyclerView.State state) {
		RecyclerView.Adapter<?> adapter = parent.getAdapter();
		if (adapter == null) return;
		if (dividesBelow(adapter, parent.getChildAdapterPosition(view))) {
			out.set(0, 0, 0, height);
		} else {
			out.set(0, 0, 0, 0);
		}
	}

	@Override
	public void onDraw(Canvas c, RecyclerView parent,
			RecyclerView.State state) {
		RecyclerView.Adapter<?> adapter = parent.getAdapter();
		if (adapter == null) return;
		boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
		int left = parent.getPaddingLeft() + (rtl ? 0 : inset);
		int right = parent.getWidth() - parent.getPaddingRight()
				- (rtl ? inset : 0);
		for (int i = 0; i < parent.getChildCount(); i++) {
			View child = parent.getChildAt(i);
			if (!dividesBelow(adapter, parent.getChildAdapterPosition(child))) {
				continue;
			}
			int top = child.getBottom() + Math.round(child.getTranslationY());
			c.drawRect(left, top, right, top + height, paint);
		}
	}
}
