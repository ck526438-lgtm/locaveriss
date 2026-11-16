package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appli chercheur : annonces vérifiées avec recherche, tri, filtres, favoris et accès au profil. */
class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var tris: View
    private lateinit var filterBtn: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: AnnonceAdapter
    private lateinit var onglets: List<ImageView>
    private lateinit var boutonsTri: List<Pair<TextView, String>>

    private var tri = Api.TRI_RECENTS
    private var type: String? = null
    private var loyerMax: Int? = null
    private var favorisSeuls = false

    /** Numéro de la dernière recherche lancée : une réponse plus ancienne est ignorée. */
    private var requete = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        Favoris.init(this)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.searchInput)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        tris = findViewById(R.id.tris)
        filterBtn = findViewById(R.id.filterBtn)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)

        adapter = AnnonceAdapter(false, true) { a ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("annonce", a.raw))
        }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        boutonsTri = listOf(
            Pair(findViewById<TextView>(R.id.triRecents), Api.TRI_RECENTS),
            Pair(findViewById<TextView>(R.id.triNotes), Api.TRI_NOTES),
            Pair(findViewById<TextView>(R.id.triPrix), Api.TRI_PRIX)
        )
        for ((bouton, valeur) in boutonsTri) {
            bouton.setOnClickListener {
                tri = valeur
                afficherEtat()
                load()
            }
        }

        onglets = listOf<ImageView>(
            findViewById(R.id.navAccueil), findViewById(R.id.navFavoris), findViewById(R.id.navProfil)
        )
        onglets[0].setOnClickListener { montrerFavoris(false) }
        onglets[1].setOnClickListener { montrerFavoris(true) }
        onglets[2].setOnClickListener {
            if (Api.loggedIn) {
                startActivity(Intent(this, ProfilActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java).putExtra("suite", "profil"))
            }
        }

        filterBtn.setOnClickListener { choisirFiltres() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                load()
                true
            } else false
        }
        afficherEtat()
    }

    override fun onResume() {
        super.onResume()
        // Au retour d'une fiche : notes, avis et favoris ont pu changer.
        load()
    }

    private fun montrerFavoris(seuls: Boolean) {
        favorisSeuls = seuls
        afficherEtat()
        load()
    }

    private fun afficherEtat() {
        Ui.choisirOnglet(onglets, if (favorisSeuls) 1 else 0)
        for ((bouton, valeur) in boutonsTri) bouton.isSelected = valeur == tri

        title.text = if (favorisSeuls) "Mes\nfavoris" else "Trouve le\nlogement idéal"
        subtitle.text =
            if (favorisSeuls) "Les logements que tu as enregistrés"
            else "Des annonces vérifiées, sans faux démarcheurs"
        val recherche = if (favorisSeuls) View.GONE else View.VISIBLE
        searchInput.visibility = recherche
        tris.visibility = recherche
        filterBtn.visibility = recherche

        // Le bouton des filtres passe au noir quand un filtre est actif.
        val actif = type != null || loyerMax != null
        filterBtn.setBackgroundResource(if (actif) R.drawable.bg_circle_ink else R.drawable.bg_circle_grey)
        filterBtn.setColorFilter(getColor(if (actif) R.color.white else R.color.ink))
    }

    private fun choisirFiltres() {
        val d = resources.displayMetrics.density
        val marge = (22 * d).toInt()
        val boite = LinearLayout(this)
        boite.orientation = LinearLayout.VERTICAL
        boite.setPadding(marge, (8 * d).toInt(), marge, 0)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Tous les types") + Annonce.TYPES
        )
        spinner.setSelection(Annonce.TYPES.indexOf(type) + 1)
        boite.addView(spinner, LinearLayout.LayoutParams.MATCH_PARENT, (52 * d).toInt())

        val max = EditText(this)
        max.hint = "Loyer maximum (FCFA)"
        max.inputType = InputType.TYPE_CLASS_NUMBER
        max.setText(loyerMax?.toString() ?: "")
        boite.addView(max, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        AlertDialog.Builder(this)
            .setTitle("Filtrer les annonces")
            .setView(boite)
            .setNeutralButton("Tout effacer") { _, _ ->
                type = null
                loyerMax = null
                afficherEtat()
                load()
            }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Appliquer") { _, _ ->
                val pos = spinner.selectedItemPosition
                type = if (pos <= 0) null else Annonce.TYPES[pos - 1]
                loyerMax = max.text.toString().take(9).toIntOrNull()?.takeIf { it > 0 }
                afficherEtat()
                load()
            }
            .show()
    }

    private fun load() {
        val moi = ++requete
        val q = searchInput.text.toString()
        val favoris = favorisSeuls
        val ids = Favoris.tous()
        val triChoisi = tri
        val typeChoisi = type
        val maxChoisi = loyerMax

        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            var error: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    if (favoris) Api.listParIds(ids)
                    else Api.listPublic(q, typeChoisi, maxChoisi, triChoisi)
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                    emptyList()
                }
            }
            if (moi != requete) return@launch
            progress.visibility = View.GONE
            adapter.submit(result)
            if (error != null) {
                emptyText.text = error
                emptyText.visibility = View.VISIBLE
            } else if (result.isEmpty()) {
                emptyText.text =
                    if (favoris) "Aucun favori pour le moment.\nTouche le marque-page d'une annonce pour la retrouver ici."
                    else "Aucune annonce pour le moment.\nReviens bientôt ou élargis ta recherche."
                emptyText.visibility = View.VISIBLE
            }
        }
    }
}
