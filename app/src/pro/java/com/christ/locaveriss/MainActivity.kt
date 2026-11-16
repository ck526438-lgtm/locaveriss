package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Appli Pro : les annonces du propriétaire ou de l'agence connecté(e),
 * et les demandes reçues avec le profil de chaque locataire.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private lateinit var annonces: AnnonceAdapter
    private lateinit var demandes: DemandeAdapter
    private lateinit var onglets: List<ImageView>

    private var surDemandes = false

    /** Numéro du dernier chargement lancé : une réponse plus ancienne est ignorée. */
    private var requete = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (!Api.loggedIn) {
            goToLogin()
            return
        }
        setContentView(R.layout.activity_main)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)
        list = findViewById(R.id.list)

        annonces = AnnonceAdapter(true) { a ->
            startActivity(Intent(this, EditActivity::class.java).putExtra("annonce", a.raw))
        }
        demandes = DemandeAdapter { d ->
            startActivity(Intent(this, LocataireActivity::class.java).putExtra("demande", d.raw))
        }
        list.layoutManager = LinearLayoutManager(this)

        onglets = listOf<ImageView>(findViewById(R.id.navAnnonces), findViewById(R.id.navDemandes))
        onglets[0].setOnClickListener { montrer(false) }
        onglets[1].setOnClickListener { montrer(true) }
        findViewById<View>(R.id.addBtn).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            Api.signOut()
            goToLogin()
        }
        afficherOnglet()
    }

    override fun onResume() {
        super.onResume()
        if (Api.loggedIn && ::annonces.isInitialized) load()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun montrer(demandesRecues: Boolean) {
        surDemandes = demandesRecues
        afficherOnglet()
        load()
    }

    private fun afficherOnglet() {
        Ui.choisirOnglet(onglets, if (surDemandes) 1 else 0)
        list.adapter = if (surDemandes) demandes else annonces
        title.text = if (surDemandes) "Demandes\ndes locataires" else "Mes annonces"
        subtitle.text =
            if (surDemandes) "Touche une demande pour voir le profil du locataire et lui répondre."
            else "Chaque annonce est vérifiée par l'équipe Locavériss avant d'être visible par les chercheurs."
    }

    private fun load() {
        val moi = ++requete
        val pourDemandes = surDemandes
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            var error: String? = null
            var mesAnnonces: List<Annonce> = emptyList()
            var recues: List<Demande> = emptyList()
            withContext(Dispatchers.IO) {
                try {
                    if (pourDemandes) recues = Api.demandesRecues() else mesAnnonces = Api.mesAnnonces()
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                }
            }
            if (moi != requete) return@launch
            progress.visibility = View.GONE
            if (!Api.loggedIn) {
                goToLogin()
                return@launch
            }
            val vide: Boolean
            if (pourDemandes) {
                demandes.submit(recues)
                vide = recues.isEmpty()
            } else {
                annonces.submit(mesAnnonces)
                vide = mesAnnonces.isEmpty()
            }
            if (error != null) {
                emptyText.text = error
                emptyText.visibility = View.VISIBLE
            } else if (vide) {
                emptyText.text =
                    if (pourDemandes) "Aucune demande pour le moment.\nLes chercheurs intéressés par tes annonces apparaîtront ici avec leur profil."
                    else "Tu n'as pas encore d'annonce.\nTouche « + Publier » pour ajouter ton premier logement."
                emptyText.visibility = View.VISIBLE
            }
        }
    }
}
