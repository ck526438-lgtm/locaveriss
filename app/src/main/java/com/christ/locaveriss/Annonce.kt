package com.christ.locaveriss

import org.json.JSONObject
import java.util.Locale

data class Annonce(
    val id: String,
    val titre: String,
    val type: String,
    val ville: String,
    val quartier: String,
    val loyer: Int,
    val cautionMois: Int,
    val avanceMois: Int,
    val pieces: Int,
    val description: String,
    val telephone: String,
    val photos: List<String>,
    val disponible: Boolean,
    val status: String,
    val motifRefus: String,
    val proprietaire: String,
    val noteMoyenne: Double,
    val nbAvis: Int,
    /** Campus ou école proche ("" si aucun). */
    val campus: String,
    /** Distance du campus en minutes à pied (0 = non précisée). */
    val distanceCampus: Int,
    /** Compteur CIE/SODECI individuel. */
    val compteurIndividuel: Boolean,
    val fraisAgence: Int,
    /** JSON d'origine, pour passer l'annonce d'un écran à l'autre. */
    val raw: String
) {
    fun prix(): String = String.format(Locale.FRANCE, "%,d FCFA / mois", loyer)

    fun prixCourt(): String = String.format(Locale.FRANCE, "%,d FCFA", loyer)

    /** « ★ 4,5 (12) », ou « Nouveau » tant qu'il n'y a aucun avis. */
    fun noteTexte(): String =
        if (nbAvis <= 0) "Nouveau"
        else String.format(Locale.FRANCE, "★ %.1f (%d)", noteMoyenne, nbAvis)

    /** « 10 min à pied · INP-HB (Yamoussoukro) », ou "" si aucun campus. */
    fun campusTexte(): String = when {
        campus.isBlank() -> ""
        distanceCampus > 0 -> "$distanceCampus min à pied · $campus"
        else -> "Près de : $campus"
    }

    fun fraisAgenceTexte(): String =
        if (fraisAgence <= 0) "Frais d'agence : 0 FCFA"
        else String.format(Locale.FRANCE, "Frais d'agence : %,d FCFA", fraisAgence)

    fun lieu(): String = listOf(quartier, ville).filter { it.isNotBlank() }.joinToString(", ")

    fun statutTexte(): String {
        val base = when (status) {
            "verifiee" -> "✔ Vérifiée et en ligne"
            "refusee" -> "✖ Refusée"
            else -> "⏳ En attente de vérification"
        }
        return if (disponible) base else "$base · Loué"
    }

    companion object {
        val TYPES = listOf(
            "Studio", "Chambre", "Colocation", "Appartement", "Villa", "Magasin", "Bureau", "Terrain"
        )

        /** Grands pôles universitaires ciblés au lancement. */
        val CAMPUS = listOf(
            "INP-HB (Yamoussoukro)",
            "Université Félix Houphouët-Boigny (Cocody)",
            "Université Nangui Abrogoua (Abobo-Adjamé)",
            "Université Alassane Ouattara (Bouaké)",
            "Université Jean Lorougnon Guédé (Daloa)",
            "Autre école / université"
        )

        /** Loi n° 2019-576 : 2 mois de caution et 2 mois d'avance au maximum. */
        const val MAX_MOIS_LEGAL = 2

        private fun JSONObject.str(key: String): String =
            if (isNull(key)) "" else optString(key, "")

        fun from(o: JSONObject): Annonce {
            val photos = ArrayList<String>()
            val arr = o.optJSONArray("photos")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotBlank()) photos.add(p)
                }
            }
            return Annonce(
                id = o.str("id"),
                titre = o.str("titre"),
                type = o.str("type"),
                ville = o.str("ville"),
                quartier = o.str("quartier"),
                loyer = o.optInt("loyer", 0),
                cautionMois = o.optInt("caution_mois", 0),
                avanceMois = o.optInt("avance_mois", 0),
                pieces = o.optInt("pieces", 0),
                description = o.str("description"),
                telephone = o.str("telephone"),
                photos = photos,
                disponible = o.optBoolean("disponible", true),
                status = o.str("status"),
                motifRefus = o.str("motif_refus"),
                proprietaire = o.str("proprietaire"),
                noteMoyenne = if (o.isNull("note_moyenne")) 0.0 else o.optDouble("note_moyenne", 0.0),
                nbAvis = o.optInt("nb_avis", 0),
                campus = o.str("campus"),
                distanceCampus = o.optInt("distance_campus", 0),
                compteurIndividuel = o.optBoolean("compteur_individuel", false),
                fraisAgence = o.optInt("frais_agence", 0),
                raw = o.toString()
            )
        }

        fun from(json: String): Annonce = from(JSONObject(json))
    }
}
