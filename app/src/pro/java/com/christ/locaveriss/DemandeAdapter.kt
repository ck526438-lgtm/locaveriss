package com.christ.locaveriss

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Demandes reçues par le propriétaire : nom du locataire, annonce concernée, état. */
class DemandeAdapter(
    private val onClick: (Demande) -> Unit
) : RecyclerView.Adapter<DemandeAdapter.VH>() {

    private val items = ArrayList<Demande>()

    fun submit(list: List<Demande>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_demande, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        val nom = d.locataire?.nom?.ifBlank { null } ?: "Locataire"
        val detail = listOf(d.annonceTitre, d.date).filter { it.isNotBlank() }.joinToString(" · ")
        Ui.remplirDemande(h.itemView, nom, detail, d)
        h.itemView.setOnClickListener { onClick(d) }
    }
}
