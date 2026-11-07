package com.aventum.scanner

import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Liste « Mes scans » de l'écran d'accueil. */
class ScanAdapter(private val onClick: (ScanRecord) -> Unit) : RecyclerView.Adapter<ScanAdapter.Holder>() {

    private var scans: List<ScanRecord> = emptyList()
    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.FRANCE)

    fun submit(newScans: List<ScanRecord>) {
        scans = newScans
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.name)
        val details: TextView = view.findViewById(R.id.details)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = scans[position]
        val context = holder.itemView.context
        holder.thumb.setImageBitmap(BitmapFactory.decodeFile(scan.thumbnail.absolutePath))
        holder.name.text = scan.name
        holder.details.text = buildList {
            add(context.resources.getQuantityString(R.plurals.pages, scan.pageCount, scan.pageCount))
            add(scan.mode.shortLabel)
            add(dateFormat.format(Date(scan.created)))
            add(Formatter.formatShortFileSize(context, scan.totalSize))
            if (scan.saved) add(context.getString(R.string.saved_badge))
        }.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(scan) }
    }

    override fun getItemCount() = scans.size
}
