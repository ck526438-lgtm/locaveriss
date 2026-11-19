package com.christ.locaveriss

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Contrôle d'une annonce : valider, refuser avec un motif, ou supprimer. */
class ReviewActivity : AppCompatActivity() {

    private lateinit var annonce: Annonce
    private lateinit var errorText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<Button>
    private var telephone = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        val json = intent.getStringExtra("annonce")
        if (json == null) {
            finish()
            return
        }
        annonce = Annonce.from(json)
        setContentView(R.layout.activity_review)

        errorText = findViewById(R.id.errorText)
        progress = findViewById(R.id.progress)
        val validerBtn = findViewById<Button>(R.id.validerBtn)
        val refuserBtn = findViewById<Button>(R.id.refuserBtn)
        val callBtn = findViewById<Button>(R.id.callBtn)
        val sigBtn = findViewById<Button>(R.id.sigBtn)
        val deleteBtn = findViewById<Button>(R.id.deleteBtn)
        buttons = listOf(validerBtn, refuserBtn, sigBtn, deleteBtn)

        val photos = findViewById<RecyclerView>(R.id.photos)
        if (annonce.photos.isEmpty()) {
            photos.visibility = View.GONE
        } else {
            photos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            photos.adapter = PhotoAdapter(annonce.photos, 320, 240)
        }

        val statut = findViewById<TextView>(R.id.statut)
        var statutTexte = annonce.statutTexte()
        if (annonce.status == "refusee" && annonce.motifRefus.isNotBlank()) {
            statutTexte += "\nMotif : ${annonce.motifRefus}"
        }
        statut.text = statutTexte
        val color = when (annonce.status) {
            "verifiee" -> R.color.green_dark
            "refusee" -> R.color.red
            else -> R.color.orange
        }
        statut.setTextColor(ContextCompat.getColor(this, color))

        findViewById<TextView>(R.id.titre).text = annonce.titre
        findViewById<TextView>(R.id.prix).text = annonce.prix()
        findViewById<TextView>(R.id.lieu).text =
            listOf(annonce.type, annonce.lieu()).filter { it.isNotBlank() }.joinToString(" · ")
        afficherConditions()
        findViewById<TextView>(R.id.description).text =
            annonce.description.ifBlank { "(Pas de description)" }

        if (annonce.status == "verifiee") validerBtn.visibility = View.GONE
        if (annonce.status == "refusee") refuserBtn.visibility = View.GONE

        validerBtn.setOnClickListener {
            lancer("Annonce validée. Elle est en ligne.") {
                Api.changerStatut(annonce.id, "verifiee", null)
            }
        }
        refuserBtn.setOnClickListener { askRefus() }
        callBtn.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:" + telephone.filter { it.isDigit() || it == '+' })
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Aucune application d'appel.", Toast.LENGTH_LONG).show()
            }
        }
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Supprimer cette annonce ?")
                .setMessage("Cette action est définitive.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer") { _, _ ->
                    lancer("Annonce supprimée.") { Api.supprimer(annonce.id) }
                }
                .show()
        }

        val sigId = intent.getStringExtra("signalement_id")
        if (sigId != null) {
            val sig = findViewById<TextView>(R.id.signalement)
            sig.text = "⚠ Signalement : " + (intent.getStringExtra("signalement_motif") ?: "")
            sig.visibility = View.VISIBLE
            sigBtn.visibility = View.VISIBLE
            sigBtn.setOnClickListener {
                lancer("Signalement classé.") { Api.supprimerSignalement(sigId) }
            }
        }

        loadOwner()
        chargerPrive()
    }

    private fun afficherConditions() {
        val lignes = arrayListOf(
            "Pièces : ${annonce.pieces}",
            "Caution : ${annonce.cautionMois} mois",
            "Avance : ${annonce.avanceMois} mois",
            annonce.fraisAgenceTexte(),
            "Téléphone de l'annonce : " + telephone.ifBlank { "chargement…" }
        )
        if (annonce.campus.isNotBlank()) lignes.add("Campus : " + annonce.campusTexte())
        if (annonce.compteurIndividuel) lignes.add("Compteur CIE/SODECI individuel")
        if (annonce.cautionMois > Annonce.MAX_MOIS_LEGAL || annonce.avanceMois > Annonce.MAX_MOIS_LEGAL) {
            lignes.add("⚠ Caution ou avance au-delà de la limite légale de 2 mois")
        }
        findViewById<TextView>(R.id.conditions).text = lignes.joinToString("\n")
    }

    /** Numéro de l'annonce et justificatifs (espace privé), lisibles par l'administrateur. */
    private fun chargerPrive() {
        val etat = findViewById<TextView>(R.id.justifsEtat)
        val liste = findViewById<LinearLayout>(R.id.justifsListe)
        lifecycleScope.launch {
            val tel = withContext(Dispatchers.IO) {
                try {
                    Api.contact(annonce.id)
                } catch (e: Exception) {
                    null
                }
            }
            telephone = tel.orEmpty()
            afficherConditions()

            var erreur: String? = null
            val justifs = withContext(Dispatchers.IO) {
                try {
                    Api.justificatifs(annonce.id)
                } catch (e: Exception) {
                    erreur = e.message
                    emptyList()
                }
            }
            val types = justifs.map { it.type }.toSet()
            val complet = "cni" in types && "propriete" in types
            etat.text = when {
                erreur != null -> "Justificatifs : $erreur"
                complet -> "✔ Justificatifs complets — compare le nom de la CNI avec celui de la preuve"
                else -> "⚠ Justificatifs manquants : la validation est bloquée.\n" +
                    (if ("cni" !in types) "• Pièce d'identité\n" else "") +
                    (if ("propriete" !in types) "• Preuve de propriété" else "")
            }
            etat.setTextColor(ContextCompat.getColor(this@ReviewActivity, if (complet) R.color.green_dark else R.color.red))
            liste.removeAllViews()
            for (j in justifs) {
                val b = com.google.android.material.button.MaterialButton(
                    this@ReviewActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                )
                b.text = if (j.type == "cni") "Voir la pièce d'identité" else "Voir la preuve de propriété"
                b.setOnClickListener { ouvrirJustificatif(j.chemin) }
                liste.addView(b, LinearLayout.LayoutParams.MATCH_PARENT, (54 * resources.displayMetrics.density).toInt())
            }
        }
    }

    private fun ouvrirJustificatif(chemin: String) {
        lifecycleScope.launch {
            var erreur: String? = null
            val lien = withContext(Dispatchers.IO) {
                try {
                    Api.lienJustificatif(chemin)
                } catch (e: Exception) {
                    erreur = e.message
                    null
                }
            }
            if (lien == null) {
                Toast.makeText(this@ReviewActivity, erreur ?: "Lien indisponible.", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lien)))
            } catch (e: Exception) {
                Toast.makeText(this@ReviewActivity, "Aucun navigateur.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadOwner() {
        val view = findViewById<TextView>(R.id.proprietaire)
        val ownerId = try {
            JSONObject(annonce.raw).optString("proprietaire", "")
        } catch (e: Exception) {
            ""
        }
        if (ownerId.isBlank()) {
            view.text = "Propriétaire : inconnu"
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val p = Api.profil(ownerId)
                    if (p == null) {
                        "Propriétaire : profil introuvable"
                    } else {
                        val type = if (p.optString("type_compte") == "agence") "Agence" else "Particulier"
                        "Compte : " + p.optString("nom") + " (" + type + ")\n" +
                            "Téléphone du compte : " + p.optString("telephone")
                    }
                } catch (e: Exception) {
                    "Propriétaire : " + (e.message ?: "erreur")
                }
            }
            view.text = text
        }
    }

    private fun askRefus() {
        val input = EditText(this)
        input.hint = "Ex. photos insuffisantes, numéro injoignable…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 2
        AlertDialog.Builder(this)
            .setTitle("Motif du refus")
            .setMessage("Le propriétaire verra ce motif dans Locavériss Pro.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Refuser") { _, _ ->
                val motif = input.text.toString().trim()
                if (motif.isEmpty()) {
                    Toast.makeText(this, "Indique un motif.", Toast.LENGTH_LONG).show()
                } else {
                    lancer("Annonce refusée.") { Api.changerStatut(annonce.id, "refusee", motif) }
                }
            }
            .show()
    }

    /** Lance une action réseau puis ferme l'écran si elle réussit. */
    private fun lancer(successMessage: String, action: () -> Unit) {
        errorText.visibility = View.GONE
        progress.visibility = View.VISIBLE
        buttons.forEach { it.isEnabled = false }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    action()
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            progress.visibility = View.GONE
            buttons.forEach { it.isEnabled = true }
            if (error == null) {
                Toast.makeText(this@ReviewActivity, successMessage, Toast.LENGTH_LONG).show()
                finish()
            } else {
                errorText.text = error
                errorText.visibility = View.VISIBLE
            }
        }
    }
}
