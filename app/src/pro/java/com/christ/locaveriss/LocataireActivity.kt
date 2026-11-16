package com.christ.locaveriss

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profil du locataire qui a envoyé une demande, avec de quoi le contacter et lui répondre. */
class LocataireActivity : AppCompatActivity() {

    private lateinit var demande: Demande
    private lateinit var infos: LinearLayout
    private lateinit var etat: TextView
    private lateinit var errorText: TextView
    private lateinit var accepterBtn: Button
    private lateinit var refuserBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        val json = intent.getStringExtra("demande")
        if (json == null) {
            finish()
            return
        }
        demande = Demande.from(json)
        setContentView(R.layout.activity_locataire)

        infos = findViewById(R.id.infos)
        etat = findViewById(R.id.etat)
        errorText = findViewById(R.id.errorText)
        accepterBtn = findViewById(R.id.accepterBtn)
        refuserBtn = findViewById(R.id.refuserBtn)

        val p = demande.locataire
        val nom = p?.nom?.ifBlank { null } ?: "Locataire"
        findViewById<TextView>(R.id.initiale).text = p?.initiale() ?: "?"
        findViewById<TextView>(R.id.nom).text = nom
        findViewById<TextView>(R.id.sousTitre).text = "Demande reçue le ${demande.date}"
        afficherEtat(demande.status)

        findViewById<TextView>(R.id.annonce).text =
            demande.annonceTitre.ifBlank { "Annonce" }
        findViewById<TextView>(R.id.message).text =
            demande.message.ifBlank { "Pas de message : le locataire a seulement envoyé son profil." }

        ligne("Téléphone", p?.telephone.orEmpty())
        ligne("Profession", p?.profession.orEmpty())
        ligne("Situation", p?.situation.orEmpty())
        ligne("Personnes dans le logement", if (p != null) p.personnes.toString() else "")
        ligne("Budget maximum", p?.budgetTexte().orEmpty())
        ligne("Zone recherchée", p?.zone.orEmpty())

        val presentation = findViewById<TextView>(R.id.presentation)
        if (p == null || p.presentation.isBlank()) {
            presentation.visibility = View.GONE
        } else {
            presentation.text = p.presentation
        }

        val telephone = p?.telephone.orEmpty()
        val whatsappBtn = findViewById<Button>(R.id.whatsappBtn)
        val callBtn = findViewById<Button>(R.id.callBtn)
        if (telephone.filter { it.isDigit() }.length < 8) {
            whatsappBtn.isEnabled = false
            callBtn.isEnabled = false
        }
        whatsappBtn.setOnClickListener {
            Ui.whatsapp(
                this, telephone,
                "Bonjour $nom, j'ai bien reçu votre demande sur Locavériss pour : ${demande.annonceTitre}"
            )
        }
        callBtn.setOnClickListener { Ui.appeler(this, telephone) }

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        accepterBtn.setOnClickListener { repondre("acceptee") }
        refuserBtn.setOnClickListener { repondre("refusee") }
    }

    /** Ajoute une ligne « libellé … valeur » au profil. */
    private fun ligne(libelle: String, valeur: String) {
        val d = resources.displayMetrics.density
        val rangee = LinearLayout(this)
        rangee.orientation = LinearLayout.HORIZONTAL
        rangee.setPadding(0, (11 * d).toInt(), 0, (11 * d).toInt())

        val gauche = TextView(this)
        gauche.text = libelle
        gauche.textSize = 14f
        gauche.setTextColor(getColor(R.color.text_dim))

        val droite = TextView(this)
        droite.text = valeur.ifBlank { "Non précisé" }
        droite.textSize = 15f
        droite.gravity = Gravity.END
        droite.setTextColor(getColor(R.color.ink))
        droite.setTextIsSelectable(true)
        ResourcesCompat.getFont(this, R.font.outfit_semibold)?.let { droite.typeface = it }

        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        rangee.addView(gauche, LinearLayout.LayoutParams(0, wrap, 1f))
        rangee.addView(droite, LinearLayout.LayoutParams(0, wrap, 1.3f))
        infos.addView(rangee)
    }

    private fun afficherEtat(status: String) {
        val d = Demande("", "", status, "", "", null, "")
        etat.text = "Demande : ${d.etatTexte().lowercase()}"
        etat.setTextColor(getColor(d.etatCouleur()))
        accepterBtn.visibility = if (status == "acceptee") View.GONE else View.VISIBLE
        refuserBtn.visibility = if (status == "refusee") View.GONE else View.VISIBLE
    }

    private fun repondre(status: String) {
        errorText.visibility = View.GONE
        accepterBtn.isEnabled = false
        refuserBtn.isEnabled = false
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Api.repondreDemande(demande.id, status)
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            accepterBtn.isEnabled = true
            refuserBtn.isEnabled = true
            if (error == null) {
                afficherEtat(status)
                Ui.toast(
                    this@LocataireActivity,
                    if (status == "acceptee") "Demande acceptée. Contacte le locataire pour organiser la visite."
                    else "Demande refusée."
                )
            } else {
                errorText.text = error
                errorText.visibility = View.VISIBLE
            }
        }
    }
}
