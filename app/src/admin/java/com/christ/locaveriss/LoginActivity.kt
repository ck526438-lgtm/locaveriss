package com.christ.locaveriss

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContentView(R.layout.activity_login)

        val emailInput = findViewById<EditText>(R.id.emailInput)
        val passInput = findViewById<EditText>(R.id.passInput)
        val errorText = findViewById<TextView>(R.id.errorText)
        val submitBtn = findViewById<Button>(R.id.submitBtn)

        submitBtn.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (!email.contains("@") || pass.isEmpty()) {
                errorText.text = "Entre ton email et ton mot de passe."
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            submitBtn.isEnabled = false
            errorText.visibility = View.GONE
            lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    try {
                        Api.signIn(email, pass)
                        if (Api.estAdmin()) {
                            null
                        } else {
                            Api.signOut()
                            "Ce compte n'est pas administrateur."
                        }
                    } catch (e: Exception) {
                        Api.signOut()
                        e.message ?: "Erreur inconnue."
                    }
                }
                submitBtn.isEnabled = true
                if (error == null) {
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    errorText.text = error
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }
}
