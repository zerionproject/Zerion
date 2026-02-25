package com.professor.zerion.android.contact;

import android.view.View;

public interface OnContactClickListener<I> {

	void onItemClick(View view, I item);

	default void onItemLongClick(View view, I item) {}

}
