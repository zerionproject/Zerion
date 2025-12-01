
package com.professor.zerion.android.view;

import android.text.Layout;
import android.text.Spannable;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.widget.TextView;

public class ArticleMovementMethod extends ArrowKeyMovementMethod {

	private static ArticleMovementMethod sInstance;

	public static MovementMethod getInstance() {
		if (sInstance == null) {
			sInstance = new ArticleMovementMethod();
		}
		return sInstance;
	}

	@Override
	public boolean onTouchEvent(TextView widget, Spannable buffer,
			MotionEvent event) {
		int action = event.getAction();

		if (action == MotionEvent.ACTION_UP) {
			int x = (int) event.getX();
			int y = (int) event.getY();

			x -= widget.getTotalPaddingLeft();
			y -= widget.getTotalPaddingTop();

			x += widget.getScrollX();
			y += widget.getScrollY();

			Layout layout = widget.getLayout();
			int line = layout.getLineForVertical(y);
			int off = layout.getOffsetForHorizontal(line, x);

			ClickableSpan[] link =
					buffer.getSpans(off, off, ClickableSpan.class);

			if (link.length != 0) {
				link[0].onClick(widget);
			}
		}
		return super.onTouchEvent(widget, buffer, event);
	}

}
