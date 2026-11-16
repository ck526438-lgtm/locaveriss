package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appli chercheur : liste des annonces vérifiées, avec recherche. */
class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var maxInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: AnnonceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.searchInput)
        maxInput = findViewById(R.id.maxInput)
        typeSpinner = findViewById(R.id.typeSpinner)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)

        typeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Tous les types") + Annonce.TYPES
        )

        adapter = AnnonceAdapter(false) { a ->
            startActivity(Intent(this, DetailActivity::class.java).putExtra("annonce", a.raw))
        }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.searchBtn).setOnClickListener { load() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                load()
                true
            } else false
        }
        load()
    }

    private fun load() {
        val q = searchInput.text.toString()
        val pos = typeSpinner.selectedItemPosition
        val type = if (pos <= 0) null else Annonce.TYPES[pos - 1]
        val max = maxInput.text.toString().toIntOrNull()

        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            var error: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    Api.listPublic(q, type, max)
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                    emptyList()
                }
            }
            progress.visibility = View.GONE
            adapter.submit(result)
            if (error != null) {
                emptyText.text = error
                emptyText.visibility = View.VISIBLE
            } else if (result.isEmpty()) {
                emptyText.text = "Aucune annonce pour le moment.\nReviens bientôt ou élargis ta recherche."
                emptyText.visibility = View.VISIBLE
            }
        }
    }
}
