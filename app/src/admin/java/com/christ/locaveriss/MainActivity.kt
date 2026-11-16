package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appli Admin : annonces à vérifier, en ligne, refusées, et signalements. */
class MainActivity : AppCompatActivity() {

    private val filtres = listOf(
        "À vérifier (en attente)" to "en_attente",
        "En ligne (vérifiées)" to "verifiee",
        "Refusées" to "refusee",
        "Signalements des chercheurs" to "signalements"
    )

    private lateinit var spinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private lateinit var sigList: ListView
    private lateinit var adapter: AnnonceAdapter
    private var signalements: List<Signalement> = emptyList()
    private var job: Job? = null
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (!Api.loggedIn) {
            goToLogin()
            return
        }
        setContentView(R.layout.activity_main)
        spinner = findViewById(R.id.filtreSpinner)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)
        list = findViewById(R.id.list)
        sigList = findViewById(R.id.sigList)

        adapter = AnnonceAdapter(true) { a ->
            startActivity(Intent(this, ReviewActivity::class.java).putExtra("annonce", a.raw))
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        sigList.setOnItemClickListener { _, _, position, _ ->
            val s = signalements.getOrNull(position) ?: return@setOnItemClickListener
            val a = s.annonce ?: return@setOnItemClickListener
            startActivity(
                Intent(this, ReviewActivity::class.java)
                    .putExtra("annonce", a.raw)
                    .putExtra("signalement_id", s.id)
                    .putExtra("signalement_motif", s.motif)
            )
        }

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, filtres.map { it.first }
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                load()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
        findViewById<Button>(R.id.refreshBtn).setOnClickListener { load() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            Api.signOut()
            goToLogin()
        }
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (ready && Api.loggedIn) load()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun load() {
        val filtre = filtres[spinner.selectedItemPosition.coerceIn(0, filtres.size - 1)].second
        val showSig = filtre == "signalements"
        job?.cancel()
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        list.visibility = if (showSig) View.GONE else View.VISIBLE
        sigList.visibility = if (showSig) View.VISIBLE else View.GONE

        job = lifecycleScope.launch {
            var error: String? = null
            var annonces: List<Annonce> = emptyList()
            var sigs: List<Signalement> = emptyList()
            withContext(Dispatchers.IO) {
                try {
                    if (showSig) sigs = Api.signalements() else annonces = Api.annoncesParStatut(filtre)
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                }
            }
            progress.visibility = View.GONE
            if (!Api.loggedIn) {
                goToLogin()
                return@launch
            }
            if (showSig) {
                signalements = sigs
                val lines = sigs.map { s ->
                    val a = s.annonce
                    "⚠ " + s.motif + "\n→ " + (if (a != null) a.titre + " (" + a.lieu() + ")" else "annonce supprimée")
                }
                sigList.adapter =
                    ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, lines)
            } else {
                adapter.submit(annonces)
            }
            val empty = if (showSig) sigs.isEmpty() else annonces.isEmpty()
            if (error != null) {
                emptyText.text = error
                emptyText.visibility = View.VISIBLE
            } else if (empty) {
                emptyText.text = if (showSig) "Aucun signalement." else "Aucune annonce dans cette liste."
                emptyText.visibility = View.VISIBLE
            }
        }
    }
}
