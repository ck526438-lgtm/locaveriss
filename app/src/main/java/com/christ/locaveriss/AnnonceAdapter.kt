package com.christ.locaveriss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class AnnonceAdapter(
    private val showStatus: Boolean,
    private val onClick: (Annonce) -> Unit
) : RecyclerView.Adapter<AnnonceAdapter.VH>() {

    private val items = ArrayList<Annonce>()

    fun submit(list: List<Annonce>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val titre: TextView = v.findViewById(R.id.titre)
        val prix: TextView = v.findViewById(R.id.prix)
        val lieu: TextView = v.findViewById(R.id.lieu)
        val statut: TextView = v.findViewById(R.id.statut)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_annonce, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.titre.text = a.titre
        h.prix.text = a.prix()
        h.lieu.text = listOf(a.type, a.lieu()).filter { it.isNotBlank() }.joinToString(" · ")
        h.img.load(a.photos.firstOrNull()) {
            placeholder(R.drawable.ph)
            error(R.drawable.ph)
            fallback(R.drawable.ph)
        }
        if (showStatus) {
            h.statut.visibility = View.VISIBLE
            h.statut.text = a.statutTexte()
            val color = when (a.status) {
                "verifiee" -> R.color.green_dark
                "refusee" -> R.color.red
                else -> R.color.orange
            }
            h.statut.setTextColor(ContextCompat.getColor(h.statut.context, color))
        } else {
            h.statut.visibility = View.GONE
        }
        h.itemView.setOnClickListener { onClick(a) }
    }
}
