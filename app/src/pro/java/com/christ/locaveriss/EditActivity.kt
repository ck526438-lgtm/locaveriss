package com.christ.locaveriss

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Création ou modification d'une annonce. */
class EditActivity : AppCompatActivity() {

    private var existing: Annonce? = null

    /** Adresses web déjà en ligne, ou images locales (content://) pas encore envoyées. */
    private val photos = ArrayList<String>()
    private lateinit var photoAdapter: PhotoAdapter

    private lateinit var titreInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var villeInput: EditText
    private lateinit var quartierInput: EditText
    private lateinit var loyerInput: EditText
    private lateinit var cautionInput: EditText
    private lateinit var avanceInput: EditText
    private lateinit var piecesInput: EditText
    private lateinit var telInput: EditText
    private lateinit var descInput: EditText
    private lateinit var photoLabel: TextView
    private lateinit var errorText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var saveBtn: Button
    private lateinit var dispoBtn: Button
    private lateinit var deleteBtn: Button

    private val picker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            for (u in uris) {
                if (photos.size < MAX_PHOTOS) photos.add(u.toString())
            }
            refreshPhotos()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        setContentView(R.layout.activity_edit)

        titreInput = findViewById(R.id.titreInput)
        typeSpinner = findViewById(R.id.typeSpinner)
        villeInput = findViewById(R.id.villeInput)
        quartierInput = findViewById(R.id.quartierInput)
        loyerInput = findViewById(R.id.loyerInput)
        cautionInput = findViewById(R.id.cautionInput)
        avanceInput = findViewById(R.id.avanceInput)
        piecesInput = findViewById(R.id.piecesInput)
        telInput = findViewById(R.id.telInput)
        descInput = findViewById(R.id.descInput)
        photoLabel = findViewById(R.id.photoLabel)
        errorText = findViewById(R.id.errorText)
        progress = findViewById(R.id.progress)
        saveBtn = findViewById(R.id.saveBtn)
        dispoBtn = findViewById(R.id.dispoBtn)
        deleteBtn = findViewById(R.id.deleteBtn)

        typeSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Annonce.TYPES)

        photoAdapter = PhotoAdapter(photos, 110, 90) { pos ->
            if (pos in photos.indices) {
                photos.removeAt(pos)
                refreshPhotos()
            }
        }
        val list = findViewById<RecyclerView>(R.id.photos)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = photoAdapter

        intent.getStringExtra("annonce")?.let { fill(Annonce.from(it)) }

        findViewById<Button>(R.id.addPhotoBtn).setOnClickListener {
            if (photos.size >= MAX_PHOTOS) {
                Toast.makeText(this, "5 photos maximum.", Toast.LENGTH_SHORT).show()
            } else {
                picker.launch("image/*")
            }
        }
        saveBtn.setOnClickListener { save() }
        refreshPhotos()
    }

    private fun fill(a: Annonce) {
        existing = a
        findViewById<TextView>(R.id.screenTitle).text = "Modifier l'annonce"
        titreInput.setText(a.titre)
        typeSpinner.setSelection(Annonce.TYPES.indexOf(a.type).coerceAtLeast(0))
        villeInput.setText(a.ville)
        quartierInput.setText(a.quartier)
        loyerInput.setText(a.loyer.toString())
        cautionInput.setText(a.cautionMois.toString())
        avanceInput.setText(a.avanceMois.toString())
        piecesInput.setText(a.pieces.toString())
        telInput.setText(a.telephone)
        descInput.setText(a.description)
        photos.addAll(a.photos)

        val status = findViewById<TextView>(R.id.statusText)
        var text = a.statutTexte()
        if (a.status == "refusee" && a.motifRefus.isNotBlank()) text += "\nMotif : ${a.motifRefus}"
        if (a.status == "verifiee") text += "\nToute modification renvoie l'annonce en vérification."
        status.text = text
        val color = when (a.status) {
            "verifiee" -> R.color.green_dark
            "refusee" -> R.color.red
            else -> R.color.orange
        }
        status.setTextColor(ContextCompat.getColor(this, color))
        status.visibility = View.VISIBLE

        saveBtn.text = "Enregistrer les modifications"
        dispoBtn.visibility = View.VISIBLE
        dispoBtn.text = if (a.disponible) "Marquer comme loué" else "Remettre en disponible"
        dispoBtn.setOnClickListener {
            lancer("Mise à jour faite.") { Api.changerDisponibilite(a.id, !a.disponible) }
        }
        deleteBtn.visibility = View.VISIBLE
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Supprimer cette annonce ?")
                .setMessage("Cette action est définitive.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer") { _, _ ->
                    lancer("Annonce supprimée.") { Api.supprimer(a.id) }
                }
                .show()
        }
    }

    private fun refreshPhotos() {
        photoAdapter.notifyDataSetChanged()
        photoLabel.text = "Photos (${photos.size}/$MAX_PHOTOS) — touche une photo pour la retirer"
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        saveBtn.isEnabled = !busy
        dispoBtn.isEnabled = !busy
        deleteBtn.isEnabled = !busy
    }

    /** Lance une action réseau puis ferme l'écran si elle réussit. */
    private fun lancer(successMessage: String, action: () -> Unit) {
        errorText.visibility = View.GONE
        setBusy(true)
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    action()
                    null
                } catch (e: Exception) {
                    e.message ?: "Erreur inconnue."
                }
            }
            setBusy(false)
            if (error == null) {
                Toast.makeText(this@EditActivity, successMessage, Toast.LENGTH_LONG).show()
                finish()
            } else {
                showError(error)
            }
        }
    }

    private fun save() {
        val titre = titreInput.text.toString().trim()
        val ville = villeInput.text.toString().trim()
        val quartier = quartierInput.text.toString().trim()
        val loyer = loyerInput.text.toString().toIntOrNull() ?: 0
        val tel = telInput.text.toString().trim()

        when {
            titre.length < 5 -> return showError("Donne un titre clair à l'annonce.")
            ville.isEmpty() || quartier.isEmpty() -> return showError("Indique la ville et le quartier.")
            loyer <= 0 -> return showError("Indique le loyer mensuel en FCFA.")
            tel.filter { it.isDigit() }.length < 8 -> return showError("Indique un numéro de téléphone valide.")
            photos.isEmpty() -> return showError("Ajoute au moins une photo du logement.")
        }

        val champs = JSONObject()
            .put("titre", titre)
            .put("type", Annonce.TYPES[typeSpinner.selectedItemPosition.coerceAtLeast(0)])
            .put("ville", ville)
            .put("quartier", quartier)
            .put("loyer", loyer)
            .put("caution_mois", cautionInput.text.toString().toIntOrNull() ?: 0)
            .put("avance_mois", avanceInput.text.toString().toIntOrNull() ?: 0)
            .put("pieces", piecesInput.text.toString().toIntOrNull() ?: 0)
            .put("telephone", tel)
            .put("description", descInput.text.toString().trim())
        val id = existing?.id
        val current = ArrayList(photos)

        lancer("Annonce envoyée. Elle sera visible après vérification.") {
            val urls = JSONArray()
            for (p in current) {
                if (p.startsWith("http")) urls.put(p) else urls.put(Api.envoyerPhoto(compress(Uri.parse(p))))
            }
            champs.put("photos", urls)
            Api.enregistrer(id, champs)
        }
    }

    /** Réduit la photo (1280 px max, JPEG) pour qu'elle passe même avec une connexion faible. */
    private fun compress(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2

        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IOException("Photo illisible.")

        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val largest = maxOf(bmp.width, bmp.height)
        val scale = if (largest > 1280) 1280f / largest else 1f
        if (scale < 1f || degrees != 0f) {
            val m = Matrix()
            m.postScale(scale, scale)
            m.postRotate(degrees)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    companion object {
        private const val MAX_PHOTOS = 5
    }
}
