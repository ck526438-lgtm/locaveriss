package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Profil locataire du chercheur (vu par les propriétaires) et suivi de ses demandes. */
class ProfilActivity : AppCompatActivity() {

    private lateinit var nomInput: EditText
    private lateinit var telInput: EditText
    private lateinit var professionInput: EditText
    private lateinit var situationSpinner: Spinner
    private lateinit var personnesInput: EditText
    private lateinit var budgetInput: EditText
    private lateinit var zoneInput: EditText
    private lateinit var presentationInput: EditText
    private lateinit var errorText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var saveBtn: Button
    private lateinit var demandesVide: TextView
    private lateinit var demandesListe: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        if (!Api.loggedIn) {
            goToLogin()
            return
        }
        setContentView(R.layout.activity_profil)

        nomInput = findViewById(R.id.nomInput)
        telInput = findViewById(R.id.telInput)
        professionInput = findViewById(R.id.professionInput)
        situationSpinner = findViewById(R.id.situationSpinner)
        personnesInput = findViewById(R.id.personnesInput)
        budgetInput = findViewById(R.id.budgetInput)
        zoneInput = findViewById(R.id.zoneInput)
        presentationInput = findViewById(R.id.presentationInput)
        errorText = findViewById(R.id.errorText)
        progress = findViewById(R.id.progress)
        saveBtn = findViewById(R.id.saveBtn)
        demandesVide = findViewById(R.id.demandesVide)
        demandesListe = findViewById(R.id.demandesListe)

        situationSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Profil.SITUATIONS)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            Api.signOut()
            finish()
        }
        saveBtn.setOnClickListener { save() }
        load()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).putExtra("suite", "profil"))
        finish()
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            var error: String? = null
            var profil: Profil? = null
            var demandes: List<Demande> = emptyList()
            withContext(Dispatchers.IO) {
                try {
                    profil = Api.monProfil()
                    demandes = Api.mesDemandes()
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                }
            }
            progress.visibility = View.GONE
            if (!Api.loggedIn) {
                goToLogin()
                return@launch
            }
            saveBtn.isEnabled = true
            profil?.let { fill(it) }
            showDemandes(demandes)
            error?.let { showError(it) }
        }
    }

    private fun fill(p: Profil) {
        nomInput.setText(p.nom)
        telInput.setText(p.telephone)
        professionInput.setText(p.profession)
        situationSpinner.setSelection(Profil.SITUATIONS.indexOf(p.situation).coerceAtLeast(0))
        personnesInput.setText(p.personnes.coerceAtLeast(1).toString())
        budgetInput.setText(if (p.budget > 0) p.budget.toString() else "")
        zoneInput.setText(p.zone)
        presentationInput.setText(p.presentation)
    }

    private fun showDemandes(demandes: List<Demande>) {
        demandesVide.visibility = if (demandes.isEmpty()) View.VISIBLE else View.GONE
        demandesListe.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (d in demandes) {
            val ligne = inflater.inflate(R.layout.item_demande, demandesListe, false)
            ligne.isClickable = false
            val titre = d.annonceTitre.ifBlank { "Annonce retirée" }
            Ui.remplirDemande(ligne, titre, "Envoyée le ${d.date}", d)
            ligne.setBackgroundResource(R.drawable.bg_field)
            demandesListe.addView(ligne)
        }
    }

    private fun save() {
        val nom = nomInput.text.toString().trim()
        val tel = telInput.text.toString().trim()
        if (nom.isEmpty() || tel.filter { it.isDigit() }.length < 8) {
            showError("Entre ton nom et un numéro de téléphone valide.")
            return
        }
        val position = situationSpinner.selectedItemPosition
        val champs = JSONObject()
            .put("nom", nom)
            .put("telephone", tel)
            .put("profession", professionInput.text.toString().trim())
            .put("situation", if (position <= 0) "" else Profil.SITUATIONS[position])
            .put("personnes", (personnesInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99))
            .put("budget", budgetInput.text.toString().toIntOrNull() ?: 0)
            .put("zone", zoneInput.text.toString().trim())
            .put("presentation", presentationInput.text.toString().trim())

        errorText.visibility = View.GONE
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Api.enregistrerProfil(champs)
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            saveBtn.isEnabled = true
            if (error == null) {
                Ui.toast(this@ProfilActivity, "Profil enregistré.")
            } else {
                showError(error)
            }
        }
    }
}
