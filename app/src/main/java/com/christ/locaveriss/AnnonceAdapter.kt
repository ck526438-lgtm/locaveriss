package com.christ.locaveriss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * Cartes d'annonces : grande photo, titre et loyer dessus, note des avis.
 * showStatus = appli Pro et Admin (statut de vérification) ;
 * favoris = appli chercheur (bouton « Voir le logement » et marque-page).
 */
class AnnonceAdapter(
    private val showStatus: Boolean,
    private val favoris: Boolean = false,
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
        val voir: TextView = v.findViewById(R.id.voir)
        val note: TextView = v.findViewById(R.id.note)
        val fav: ImageView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_annonce, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.titre.text = a.titre
        h.prix.text = a.prixCourt() + "\npar mois"
        h.lieu.text = listOf(a.type, a.lieu()).filter { it.isNotBlank() }.joinToString(" · ")
        h.note.text = a.noteTexte()
        h.img.load(a.photos.firstOrNull()) {
            placeholder(R.drawable.ph)
            error(R.drawable.ph)
            fallback(R.drawable.ph)
        }
        if (showStatus) {
            h.voir.visibility = View.GONE
            h.statut.visibility = View.VISIBLE
            h.statut.text = a.statutTexte()
            val color = when (a.status) {
                "verifiee" -> R.color.green_dark
                "refusee" -> R.color.red
                else -> R.color.orange
            }
            h.statut.setTextColor(ContextCompat.getColor(h.statut.context, color))
        } else {
            h.voir.visibility = View.VISIBLE
            h.statut.visibility = View.GONE
        }
        if (favoris) {
            h.fav.visibility = View.VISIBLE
            marquer(h.fav, Favoris.contient(a.id))
            h.fav.setOnClickListener { marquer(h.fav, Favoris.basculer(a.id)) }
        } else {
            h.fav.visibility = View.GONE
        }
        h.itemView.setOnClickListener { onClick(a) }
    }

    private fun marquer(icone: ImageView, enregistre: Boolean) {
        icone.setImageResource(if (enregistre) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        icone.contentDescription =
            if (enregistre) "Retirer de mes favoris" else "Enregistrer dans mes favoris"
    }
}
