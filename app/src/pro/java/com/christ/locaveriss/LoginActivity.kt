package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private var signupMode = false

    private lateinit var title: TextView
    private lateinit var signupFields: View
    private lateinit var nomInput: EditText
    private lateinit var telInput: EditText
    private lateinit var compteSpinner: Spinner
    private lateinit var emailInput: EditText
    private lateinit var passInput: EditText
    private lateinit var errorText: TextView
    private lateinit var submitBtn: Button
    private lateinit var switchBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContentView(R.layout.activity_login)

        title = findViewById(R.id.title)
        signupFields = findViewById(R.id.signupFields)
        nomInput = findViewById(R.id.nomInput)
        telInput = findViewById(R.id.telInput)
        compteSpinner = findViewById(R.id.compteSpinner)
        emailInput = findViewById(R.id.emailInput)
        passInput = findViewById(R.id.passInput)
        errorText = findViewById(R.id.errorText)
        submitBtn = findViewById(R.id.submitBtn)
        switchBtn = findViewById(R.id.switchBtn)

        compteSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Propriétaire particulier", "Agence immobilière")
        )

        switchBtn.setOnClickListener {
            signupMode = !signupMode
            render()
        }
        submitBtn.setOnClickListener { submit() }
        render()
    }

    private fun render() {
        signupFields.visibility = if (signupMode) View.VISIBLE else View.GONE
        title.text = if (signupMode) "Créer un compte propriétaire / agence" else "Connexion propriétaire / agence"
        submitBtn.text = if (signupMode) "Créer mon compte" else "Se connecter"
        switchBtn.text =
            if (signupMode) "Déjà un compte ? Se connecter" else "Pas encore de compte ? Créer un compte"
        errorText.visibility = View.GONE
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    private fun submit() {
        val email = emailInput.text.toString().trim()
        val pass = passInput.text.toString()
        val nom = nomInput.text.toString().trim()
        val tel = telInput.text.toString().trim()
        val typeCompte = if (compteSpinner.selectedItemPosition == 1) "agence" else "particulier"

        if (!email.contains("@") || pass.length < 6) {
            showError("Entre un email valide et un mot de passe d'au moins 6 caractères.")
            return
        }
        if (signupMode && (nom.isEmpty() || tel.filter { it.isDigit() }.length < 8)) {
            showError("Entre ton nom et un numéro de téléphone valide.")
            return
        }

        val signup = signupMode
        submitBtn.isEnabled = false
        errorText.visibility = View.GONE
        lifecycleScope.launch {
            var error: String? = null
            val connected = withContext(Dispatchers.IO) {
                try {
                    if (signup) {
                        Api.signUp(email, pass, nom, tel, typeCompte)
                    } else {
                        Api.signIn(email, pass)
                        true
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Erreur inconnue."
                    false
                }
            }
            submitBtn.isEnabled = true
            when {
                error != null -> showError(error ?: "")
                connected -> {
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
                else -> {
                    signupMode = false
                    render()
                    showError("Compte créé. Confirme ton email (lien reçu par mail), puis connecte-toi.")
                }
            }
        }
    }
}
