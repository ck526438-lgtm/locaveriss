package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Fiche d'une annonce : photos, conditions, contact, demande au propriétaire, avis et notes. */
class DetailActivity : AppCompatActivity() {

    private lateinit var annonce: Annonce
    private lateinit var favBtn: ImageView
    private lateinit var noteResume: TextView
    private lateinit var avisMoyenne: TextView
    private lateinit var avisNombre: TextView
    private lateinit var avisListe: LinearLayout
    private lateinit var avisBtn: Button

    private var avis: List<Avis> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        Favoris.init(this)
        val json = intent.getStringExtra("annonce")
        if (json == null) {
            finish()
            return
        }
        annonce = Annonce.from(json)
        setContentView(R.layout.activity_detail)

        favBtn = findViewById(R.id.favBtn)
        noteResume = findViewById(R.id.noteResume)
        avisMoyenne = findViewById(R.id.avisMoyenne)
        avisNombre = findViewById(R.id.avisNombre)
        avisListe = findViewById(R.id.avisListe)
        avisBtn = findViewById(R.id.avisBtn)

        montrerPhotos()

        findViewById<TextView>(R.id.titre).text = annonce.titre
        findViewById<TextView>(R.id.lieu).text = annonce.lieu()
        findViewById<TextView>(R.id.prix).text = annonce.prixCourt()
        val description = findViewById<TextView>(R.id.description)
        description.text = annonce.description
        if (annonce.description.isBlank()) description.visibility = View.GONE

        findViewById<TextView>(R.id.chipType).text = annonce.type
        val pieces = findViewById<TextView>(R.id.chipPieces)
        if (annonce.pieces > 0) {
            pieces.text = if (annonce.pieces == 1) "1 pièce" else "${annonce.pieces} pièces"
        } else {
            pieces.visibility = View.GONE
        }
        findViewById<TextView>(R.id.chipCaution).text = "Caution : ${annonce.cautionMois} mois"
        findViewById<TextView>(R.id.chipAvance).text = "Avance : ${annonce.avanceMois} mois"

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        marquerFavori(Favoris.contient(annonce.id))
        favBtn.setOnClickListener {
            val enregistre = Favoris.basculer(annonce.id)
            marquerFavori(enregistre)
            Ui.toast(this, if (enregistre) "Ajouté à tes favoris." else "Retiré de tes favoris.")
        }

        findViewById<Button>(R.id.callBtn).setOnClickListener { Ui.appeler(this, annonce.telephone) }
        findViewById<Button>(R.id.whatsappBtn).setOnClickListener {
            Ui.whatsapp(
                this, annonce.telephone,
                "Bonjour, je suis intéressé(e) par votre annonce sur Locavériss : ${annonce.titre}"
            )
        }
        findViewById<Button>(R.id.demandeBtn).setOnClickListener {
            if (connecte("Connecte-toi pour envoyer ta demande au propriétaire.")) demander()
        }
        avisBtn.setOnClickListener {
            if (connecte("Connecte-toi pour donner ton avis.")) demanderAvis()
        }
        findViewById<Button>(R.id.reportBtn).setOnClickListener { askReport() }

        afficherAvis()
        chargerAvis()
    }

    override fun onResume() {
        super.onResume()
        // Au retour de l'écran de connexion, « Donner » devient « Modifier » si un avis existe déjà.
        if (::avisBtn.isInitialized) afficherAvis()
    }

    private fun montrerPhotos() {
        val photos = findViewById<RecyclerView>(R.id.photos)
        val compteur = findViewById<TextView>(R.id.compteur)
        val total = annonce.photos.size
        if (total == 0) {
            photos.visibility = View.GONE
            return
        }
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        photos.layoutManager = lm
        photos.adapter = PhotoAdapter(annonce.photos, 0, 0)
        PagerSnapHelper().attachToRecyclerView(photos)
        if (total > 1) {
            compteur.visibility = View.VISIBLE
            compteur.text = "1/$total"
            photos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    val pos = lm.findFirstCompletelyVisibleItemPosition()
                    if (pos >= 0) compteur.text = "${pos + 1}/$total"
                }
            })
        }
    }

    private fun marquerFavori(enregistre: Boolean) {
        favBtn.setImageResource(if (enregistre) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        favBtn.contentDescription =
            if (enregistre) "Retirer de mes favoris" else "Enregistrer dans mes favoris"
    }

    /** Ouvre l'écran de connexion si besoin ; retourne true si un compte est déjà connecté. */
    private fun connecte(raison: String): Boolean {
        if (Api.loggedIn) return true
        Ui.toast(this, raison)
        startActivity(Intent(this, LoginActivity::class.java))
        return false
    }

    // ---------- Avis et notes ----------

    private fun chargerAvis() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Api.avis(annonce.id)
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                avis = result
                afficherAvis()
            } else {
                avisNombre.text = "Impossible de charger les avis pour le moment."
            }
        }
    }

    private fun afficherAvis() {
        val nombre = avis.size
        if (nombre == 0) {
            noteResume.text = "Nouveau · pas encore d'avis"
            avisMoyenne.text = "–"
            avisNombre.text = "Sois le premier à donner ton avis."
        } else {
            val moyenne = avis.sumOf { it.note }.toDouble() / nombre
            val moyenneTexte = String.format(Locale.FRANCE, "%.1f", moyenne)
            val compte = if (nombre == 1) "1 avis" else "$nombre avis"
            noteResume.text = "★ $moyenneTexte · $compte"
            avisMoyenne.text = "$moyenneTexte / 5"
            avisNombre.text = "${Ui.etoiles(Math.round(moyenne).toInt())}  $compte"
        }
        val mien = avis.any { it.auteur == Api.userId }
        avisBtn.text = if (mien) "Modifier mon avis" else "Donner mon avis"

        avisListe.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (a in avis) {
            val ligne = inflater.inflate(R.layout.item_avis, avisListe, false)
            ligne.findViewById<TextView>(R.id.initiale).text =
                a.auteurNom.trim().take(1).uppercase(Locale.FRANCE).ifEmpty { "?" }
            ligne.findViewById<TextView>(R.id.nom).text = a.auteurNom
            ligne.findViewById<TextView>(R.id.date).text = a.date
            ligne.findViewById<TextView>(R.id.etoiles).text = Ui.etoiles(a.note)
            val commentaire = ligne.findViewById<TextView>(R.id.commentaire)
            commentaire.text = a.commentaire
            if (a.commentaire.isBlank()) commentaire.visibility = View.GONE
            avisListe.addView(ligne)
        }
    }

    private fun demanderAvis() {
        val mien = avis.firstOrNull { it.auteur == Api.userId }
        var note = mien?.note ?: 0
        val d = resources.displayMetrics.density
        val marge = (22 * d).toInt()

        val boite = LinearLayout(this)
        boite.orientation = LinearLayout.VERTICAL
        boite.setPadding(marge, (8 * d).toInt(), marge, 0)

        val ligne = LinearLayout(this)
        ligne.gravity = Gravity.CENTER
        val etoiles = ArrayList<TextView>()
        for (i in 1..5) {
            val etoile = TextView(this)
            etoile.textSize = 40f
            etoile.setTextColor(getColor(R.color.star))
            etoile.setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            etoile.contentDescription = "$i sur 5"
            etoile.text = if (i <= note) "★" else "☆"
            etoile.setOnClickListener {
                note = i
                etoiles.forEachIndexed { index, t -> t.text = if (index < note) "★" else "☆" }
            }
            etoiles.add(etoile)
            ligne.addView(etoile)
        }
        boite.addView(ligne)

        val input = EditText(this)
        input.hint = "Ton expérience : visite, état du logement, sérieux du propriétaire…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.minLines = 3
        input.setText(mien?.commentaire ?: "")
        boite.addView(input)

        AlertDialog.Builder(this)
            .setTitle(if (mien == null) "Donner mon avis" else "Modifier mon avis")
            .setView(boite)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Publier") { _, _ ->
                if (note == 0) {
                    Ui.toast(this, "Touche les étoiles pour choisir une note.")
                } else {
                    envoyerAvis(note, input.text.toString().trim().take(1000))
                }
            }
            .show()
    }

    private fun envoyerAvis(note: Int, commentaire: String) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Api.donnerAvis(annonce.id, note, commentaire)
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            Ui.toast(this@DetailActivity, error ?: "Merci, ton avis est publié.")
            if (error == null) chargerAvis()
        }
    }

    // ---------- Demande au propriétaire ----------

    private fun demander() {
        val d = resources.displayMetrics.density
        val marge = (22 * d).toInt()
        val boite = LinearLayout(this)
        boite.orientation = LinearLayout.VERTICAL
        boite.setPadding(marge, (8 * d).toInt(), marge, 0)

        val info = TextView(this)
        info.text = "Le propriétaire recevra ta demande avec ton profil locataire " +
            "(nom, téléphone, profession, budget…). Complète-le dans l'onglet Profil."
        info.textSize = 14f
        boite.addView(info)

        val input = EditText(this)
        input.hint = "Message (facultatif) : date d'emménagement, questions…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.minLines = 2
        boite.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Envoyer ma demande")
            .setView(boite)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Envoyer") { _, _ ->
                envoyerDemande(input.text.toString().trim().take(1000))
            }
            .show()
    }

    private fun envoyerDemande(message: String) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Api.envoyerDemande(annonce.id, message)
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            Ui.toast(
                this@DetailActivity,
                error ?: "Demande envoyée. Le propriétaire peut maintenant voir ton profil."
            )
        }
    }

    // ---------- Signalement ----------

    private fun askReport() {
        val input = EditText(this)
        input.hint = "Ex. faux démarcheur, demande d'argent avant visite…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 2
        AlertDialog.Builder(this)
            .setTitle("Signaler cette annonce")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Envoyer") { _, _ ->
                val motif = input.text.toString().trim()
                if (motif.isEmpty()) {
                    Ui.toast(this, "Explique le problème en quelques mots.")
                } else {
                    sendReport(motif)
                }
            }
            .show()
    }

    private fun sendReport(motif: String) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Api.signaler(annonce.id, motif)
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            Ui.toast(this@DetailActivity, error ?: "Merci, le signalement a été envoyé.")
        }
    }
}
