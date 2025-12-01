package com.professor.zerion.android.util;

public interface ItemReturningAdapter<I> {

	I getItemAt(int position);

	int getItemCount();

}
