package com.christ.locaveriss

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    private lateinit var annonce: Annonce

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        val json = intent.getStringExtra("annonce")
        if (json == null) {
            finish()
            return
        }
        annonce = Annonce.from(json)
        setContentView(R.layout.activity_detail)

        val photos = findViewById<RecyclerView>(R.id.photos)
        if (annonce.photos.isEmpty()) {
            photos.visibility = View.GONE
        } else {
            photos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            photos.adapter = PhotoAdapter(annonce.photos, 320, 240)
        }

        findViewById<TextView>(R.id.titre).text = annonce.titre
        findViewById<TextView>(R.id.prix).text = annonce.prix()
        findViewById<TextView>(R.id.lieu).text =
            listOf(annonce.type, annonce.lieu()).filter { it.isNotBlank() }.joinToString(" · ")

        val lines = ArrayList<String>()
        if (annonce.pieces > 0) lines.add("Pièces : ${annonce.pieces}")
        lines.add("Caution : ${annonce.cautionMois} mois")
        lines.add("Avance : ${annonce.avanceMois} mois")
        findViewById<TextView>(R.id.conditions).text = lines.joinToString("\n")
        findViewById<TextView>(R.id.description).text = annonce.description

        findViewById<Button>(R.id.callBtn).setOnClickListener {
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + annonce.telephone.filter { it.isDigit() || it == '+' })))
        }
        findViewById<Button>(R.id.whatsappBtn).setOnClickListener {
            var digits = annonce.telephone.filter { it.isDigit() }
            // Numéro ivoirien à 10 chiffres sans indicatif : on ajoute 225.
            if (digits.length == 10 && !annonce.telephone.trim().startsWith("+")) digits = "225$digits"
            val text = Uri.encode("Bonjour, je suis intéressé(e) par votre annonce sur Locavériss : ${annonce.titre}")
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=$text")))
        }
        findViewById<Button>(R.id.reportBtn).setOnClickListener { askReport() }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aucune application pour cette action.", Toast.LENGTH_LONG).show()
        }
    }

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
                    Toast.makeText(this, "Explique le problème en quelques mots.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(
                this@DetailActivity,
                error ?: "Merci, le signalement a été envoyé.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
