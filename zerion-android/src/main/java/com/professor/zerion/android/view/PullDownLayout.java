
package com.professor.zerion.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.customview.widget.ViewDragHelper;

@NotNullByDefault
public class PullDownLayout extends FrameLayout {

	private final ViewDragHelper dragger;

	private final int minimumFlingVelocity;

	@Nullable
	private Callback callback;

	public PullDownLayout(Context context) {
		this(context, null);
	}

	public PullDownLayout(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public PullDownLayout(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		dragger = ViewDragHelper.create(this, 1f / 8f, new ViewDragCallback());
		minimumFlingVelocity =
				ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
	}

	public void setCallback(@Nullable Callback callback) {
		this.callback = callback;
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		return dragger.shouldInterceptTouchEvent(ev);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		dragger.processTouchEvent(event);
		return true;
	}

	@Override
	public void computeScroll() {
		if (dragger.continueSettling(true)) {
			ViewCompat.postInvalidateOnAnimation(this);
		}
	}

	public interface Callback {

		void onPullStart();

		void onPull(float progress);

		void onPullCancel();

		void onPullComplete();

	}

	private class ViewDragCallback extends ViewDragHelper.Callback {

		@Override
		public boolean tryCaptureView(View child, int pointerId) {
			return true;
		}

		@Override
		public int clampViewPositionHorizontal(View child, int left, int dx) {
			return 0;
		}

		@Override
		public int clampViewPositionVertical(View child, int top, int dy) {
			return Math.max(0, top);
		}

		@Override
		public int getViewHorizontalDragRange(View child) {
			return 0;
		}

		@Override
		public int getViewVerticalDragRange(View child) {
			return getHeight();
		}

		@Override
		public void onViewCaptured(View capturedChild, int activePointerId) {
			if (callback != null) {
				callback.onPullStart();
			}
		}

		@Override
		public void onViewPositionChanged(View changedView, int left, int top,
				int dx, int dy) {
			if (callback != null) {
				callback.onPull((float) top / (float) getHeight());
			}
		}

		@Override
		public void onViewReleased(View releasedChild, float xvel, float yvel) {
			int slop = yvel > minimumFlingVelocity ? getHeight() / 6 :
					getHeight() / 3;
			if (releasedChild.getTop() > slop) {
				if (callback != null) {
					callback.onPullComplete();
				}
			} else {
				if (callback != null) {
					callback.onPullCancel();
				}

				dragger.settleCapturedViewAt(0, 0);
				invalidate();
			}
		}

	}

}
