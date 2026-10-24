package com.aventum.scanner

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/** Grille des miniatures des pages scannées. */
class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

    private var pages: List<Bitmap> = emptyList()

    fun submit(newPages: List<Bitmap>) {
        pages = newPages
        notifyDataSetChanged()
    }

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
        return Holder(view as ImageView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.image.setImageBitmap(pages[position])
        holder.image.contentDescription =
            holder.image.context.getString(R.string.page_description, position + 1)
    }

    override fun getItemCount() = pages.size
}
