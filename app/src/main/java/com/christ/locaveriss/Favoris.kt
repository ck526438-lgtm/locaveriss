package com.christ.locaveriss

import android.content.Context
import android.content.SharedPreferences

/** Annonces enregistrées par le chercheur, gardées sur le téléphone (pas besoin de compte). */
object Favoris {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("favoris", Context.MODE_PRIVATE)
        }
    }

    fun tous(): Set<String> = prefs?.getStringSet("ids", emptySet())?.toSet() ?: emptySet()

    fun contient(id: String): Boolean = tous().contains(id)

    /** Ajoute ou retire l'annonce ; retourne true si elle est maintenant enregistrée. */
    fun basculer(id: String): Boolean {
        val ids = tous().toMutableSet()
        val ajoute = if (ids.contains(id)) {
            ids.remove(id)
            false
        } else {
            ids.add(id)
            true
        }
        prefs?.edit()?.putStringSet("ids", ids)?.apply()
        return ajoute
    }
}
