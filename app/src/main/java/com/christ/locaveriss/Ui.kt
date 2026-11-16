package com.christ.locaveriss

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Petites fonctions d'affichage partagées par les trois applis. */
object Ui {

    /** « 2026-11-16T01:30:09+00:00 » devient « 16/11/2026 ». */
    fun date(iso: String): String {
        if (iso.length < 10) return ""
        val p = iso.substring(0, 10).split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else ""
    }

    fun etoiles(note: Int): String {
        val n = note.coerceIn(0, 5)
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** Barre du bas : l'icône choisie passe dans un rond blanc, les autres restent blanches sur noir. */
    fun choisirOnglet(icones: List<ImageView>, choisi: Int) {
        icones.forEachIndexed { i, icone ->
            icone.isSelected = i == choisi
            val couleur = if (i == choisi) R.color.ink else R.color.white
            icone.setColorFilter(ContextCompat.getColor(icone.context, couleur))
        }
    }

    /** Remplit une ligne item_demande. */
    fun remplirDemande(ligne: View, nom: String, detail: String, d: Demande) {
        ligne.findViewById<TextView>(R.id.initiale).text =
            nom.trim().take(1).uppercase().ifEmpty { "?" }
        ligne.findViewById<TextView>(R.id.nom).text = nom
        ligne.findViewById<TextView>(R.id.detail).text = detail
        val etat = ligne.findViewById<TextView>(R.id.etat)
        etat.text = d.etatTexte()
        etat.setTextColor(ContextCompat.getColor(ligne.context, d.etatCouleur()))
    }

    private fun ouvrir(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            toast(context, "Aucune application pour cette action.")
        }
    }

    fun appeler(context: Context, telephone: String) {
        val numero = telephone.filter { it.isDigit() || it == '+' }
        ouvrir(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero")))
    }

    fun whatsapp(context: Context, telephone: String, message: String) {
        var chiffres = telephone.filter { it.isDigit() }
        // Numéro ivoirien à 10 chiffres sans indicatif : on ajoute 225.
        if (chiffres.length == 10 && !telephone.trim().startsWith("+")) chiffres = "225$chiffres"
        val url = "https://wa.me/$chiffres?text=${Uri.encode(message)}"
        ouvrir(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
