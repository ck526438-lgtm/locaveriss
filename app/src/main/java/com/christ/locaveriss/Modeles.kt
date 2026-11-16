package com.christ.locaveriss

import org.json.JSONObject
import java.util.Locale

private fun JSONObject.txt(key: String): String = if (isNull(key)) "" else optString(key, "")

/** Avis laissé par un chercheur sur une annonce : une note de 1 à 5 et un commentaire. */
data class Avis(
    val id: String,
    val auteur: String,
    val auteurNom: String,
    val note: Int,
    val commentaire: String,
    val date: String
) {
    companion object {
        fun from(o: JSONObject) = Avis(
            id = o.txt("id"),
            auteur = o.txt("auteur"),
            auteurNom = o.txt("auteur_nom").ifBlank { "Utilisateur" },
            note = o.optInt("note", 0).coerceIn(0, 5),
            commentaire = o.txt("commentaire"),
            date = Ui.date(o.txt("created_at"))
        )
    }
}

/** Profil d'un compte. Les champs après le téléphone forment le « profil locataire ». */
data class Profil(
    val id: String,
    val nom: String,
    val telephone: String,
    val profession: String,
    val situation: String,
    val personnes: Int,
    val budget: Int,
    val zone: String,
    val presentation: String
) {
    fun initiale(): String = nom.trim().take(1).uppercase(Locale.FRANCE).ifEmpty { "?" }

    fun budgetTexte(): String =
        if (budget > 0) String.format(Locale.FRANCE, "%,d FCFA / mois", budget) else "Non précisé"

    companion object {
        val SITUATIONS = listOf(
            "Non précisé", "Seul(e)", "En couple", "Famille avec enfants", "Étudiant(e)", "Colocation"
        )

        fun from(o: JSONObject) = Profil(
            id = o.txt("id"),
            nom = o.txt("nom"),
            telephone = o.txt("telephone"),
            profession = o.txt("profession"),
            situation = o.txt("situation"),
            personnes = o.optInt("personnes", 1),
            budget = o.optInt("budget", 0),
            zone = o.txt("zone"),
            presentation = o.txt("presentation")
        )
    }
}

/** Demande envoyée par un chercheur au propriétaire d'une annonce, avec son profil locataire. */
data class Demande(
    val id: String,
    val message: String,
    val status: String,
    val date: String,
    val annonceTitre: String,
    val locataire: Profil?,
    /** JSON d'origine, pour passer la demande d'un écran à l'autre. */
    val raw: String
) {
    fun etatTexte(): String = when (status) {
        "acceptee" -> "Acceptée"
        "refusee" -> "Refusée"
        else -> "Nouvelle"
    }

    fun etatCouleur(): Int = when (status) {
        "acceptee" -> R.color.green_dark
        "refusee" -> R.color.red
        else -> R.color.orange
    }

    companion object {
        fun from(o: JSONObject): Demande {
            val p = o.optJSONObject("profils")
            return Demande(
                id = o.txt("id"),
                message = o.txt("message"),
                status = o.txt("status"),
                date = Ui.date(o.txt("created_at")),
                annonceTitre = o.optJSONObject("annonces")?.txt("titre").orEmpty(),
                locataire = if (p != null) Profil.from(p) else null,
                raw = o.toString()
            )
        }

        fun from(json: String): Demande = from(JSONObject(json))
    }
}
