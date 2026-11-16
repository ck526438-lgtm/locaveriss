package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appli Pro : les annonces du propriétaire ou de l'agence connecté(e). */
class MainActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: AnnonceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (!Api.loggedIn) {
            goToLogin()
            return
        }
        setContentView(R.layout.activity_main)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.emptyText)

        adapter = AnnonceAdapter(true) { a ->
            startActivity(Intent(this, EditActivity::class.java).putExtra("annonce", a.raw))
        }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.addBtn).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            Api.signOut()
            goToLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Api.loggedIn && ::adapter.isInitialized) load()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            var error: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    Api.mesAnnonces()
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                    emptyList()
                }
            }
            progress.visibility = View.GONE
            if (!Api.loggedIn) {
                goToLogin()
                return@launch
            }
            adapter.submit(result)
            if (error != null) {
                emptyText.text = error
                emptyText.visibility = View.VISIBLE
            } else if (result.isEmpty()) {
                emptyText.text = "Tu n'as pas encore d'annonce.\nPublie ton premier logement."
                emptyText.visibility = View.VISIBLE
            }
        }
    }
}
