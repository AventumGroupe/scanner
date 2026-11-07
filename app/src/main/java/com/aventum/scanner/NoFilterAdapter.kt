package com.aventum.scanner

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * Liste déroulante qui affiche toujours toutes les options : l'adaptateur standard filtre
 * d'après le texte affiché et ne montrait plus que le format déjà choisi.
 */
class NoFilterAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {

    private val noFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply {
                values = items
                count = items.size
            }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
    }

    override fun getFilter(): Filter = noFilter
}
