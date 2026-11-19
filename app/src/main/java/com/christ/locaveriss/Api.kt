package com.christ.locaveriss

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

/** Signalement envoyé par un chercheur, avec l'annonce concernée. */
data class Signalement(val id: String, val motif: String, val annonce: Annonce?)

/** Filtres de recherche de l'appli chercheur. */
data class Filtres(
    val type: String? = null,
    val loyerMax: Int? = null,
    val campus: String? = null,
    /** Distance maximale du campus, en minutes à pied. */
    val distanceMax: Int? = null,
    val compteurIndividuel: Boolean = false
) {
    val actifs: Boolean
        get() = type != null || loyerMax != null || campus != null || distanceMax != null || compteurIndividuel
}

/** Pass Réservation : obligatoire ou non, prix, durée et fin du Pass en cours (null si aucun). */
data class EtatPass(val obligatoire: Boolean, val prix: Int, val duree: Int, val fin: String?) {
    val actif: Boolean get() = fin != null
}

/** Pièce justificative d'une annonce : "cni" ou "propriete". */
data class Justificatif(val id: String, val type: String, val chemin: String)

/** Accès à Supabase (Auth, base de données, stockage) par son API REST. À appeler hors du thread principal. */
object Api {
    const val TRI_RECENTS = "recents"
    const val TRI_NOTES = "notes"
    const val TRI_PRIX = "prix"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val JPEG_TYPE = "image/jpeg".toMediaType()

    /** Colonnes lisibles des annonces : le téléphone n'en fait pas partie (voir contact()). */
    private const val COLONNES =
        "id,proprietaire,titre,type,ville,quartier,loyer,caution_mois,avance_mois,pieces," +
            "description,photos,disponible,status,motif_refus,created_at,note_moyenne,nb_avis," +
            "campus,distance_campus,compteur_individuel,frais_agence"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_KEY.trim()

    private var prefs: SharedPreferences? = null

    val configured: Boolean
        get() = baseUrl.startsWith("https://") && apiKey.isNotEmpty()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("session", Context.MODE_PRIVATE)
        }
    }

    private fun get(key: String): String? = prefs?.getString(key, null)

    private fun put(key: String, value: String?) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    val userId: String? get() = get("user_id")
    val loggedIn: Boolean get() = !get("refresh").isNullOrEmpty()

    fun signOut() {
        put("access", null)
        put("refresh", null)
        put("user_id", null)
    }

    // ---------- HTTP ----------

    private fun url(path: String): HttpUrl.Builder {
        if (!configured) throw ApiException(0, "Application non configurée (Supabase).")
        return "$baseUrl/$path".toHttpUrl().newBuilder()
    }

    private fun call(
        method: String, url: HttpUrl, body: RequestBody?, auth: Boolean, prefer: String?
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url).header("apikey", apiKey)
        val token = if (auth) get("access") else null
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        if (prefer != null) builder.header("Prefer", prefer)
        val needsBody = method == "POST" || method == "PATCH" || method == "PUT"
        val reqBody = body ?: if (needsBody) ByteArray(0).toRequestBody(null) else null
        builder.method(method, reqBody)
        return try {
            client.newCall(builder.build()).execute().use { r ->
                Pair(r.code, r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ApiException(-1, "Connexion impossible. Vérifie ta connexion internet.")
        }
    }

    private fun send(
        method: String, url: HttpUrl, body: RequestBody? = null,
        auth: Boolean = false, prefer: String? = null
    ): String {
        var res = call(method, url, body, auth, prefer)
        if (res.first == 401 && auth && refresh()) {
            res = call(method, url, body, auth, prefer)
        }
        if (res.first !in 200..299) throw ApiException(res.first, errorMessage(res.first, res.second))
        return res.second
    }

    private fun json(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON_TYPE)

    private fun errorMessage(code: Int, text: String): String {
        var msg = ""
        try {
            val o = JSONObject(text)
            for (k in listOf("msg", "message", "error_description", "error")) {
                if (!o.isNull(k) && o.optString(k).isNotBlank()) {
                    msg = o.optString(k)
                    break
                }
            }
        } catch (_: Exception) {
        }
        return when {
            msg.contains("Invalid login credentials", true) -> "Email ou mot de passe incorrect."
            msg.contains("already registered", true) -> "Un compte existe déjà avec cet email."
            msg.contains("Email not confirmed", true) ->
                "Confirme d'abord ton email (lien reçu par mail), puis connecte-toi."
            msg.contains("Password should be", true) -> "Mot de passe trop court (6 caractères minimum)."
            msg.contains("row-level security", true) -> "Action non autorisée avec ce compte."
            code == 401 || code == 403 -> "Session expirée ou accès refusé. Reconnecte-toi."
            msg.isNotBlank() -> msg
            else -> "Erreur du serveur ($code)."
        }
    }

    // ---------- Comptes ----------

    private fun saveSession(o: JSONObject): Boolean {
        val access = if (o.isNull("access_token")) "" else o.optString("access_token")
        if (access.isEmpty()) return false
        put("access", access)
        put("refresh", o.optString("refresh_token"))
        put("user_id", o.optJSONObject("user")?.optString("id"))
        return true
    }

    @Synchronized
    private fun refresh(): Boolean {
        val rt = get("refresh")
        if (rt.isNullOrEmpty()) return false
        val u = url("auth/v1/token").addQueryParameter("grant_type", "refresh_token").build()
        val res = try {
            call("POST", u, json(JSONObject().put("refresh_token", rt)), false, null)
        } catch (e: ApiException) {
            return false
        }
        if (res.first !in 200..299) {
            if (res.first in 400..499) signOut()
            return false
        }
        return try {
            saveSession(JSONObject(res.second))
        } catch (e: Exception) {
            false
        }
    }

    /** Retourne true si le compte est connecté tout de suite, false s'il faut confirmer l'email. */
    fun signUp(email: String, password: String, nom: String, telephone: String, typeCompte: String): Boolean {
        val data = JSONObject().put("nom", nom).put("telephone", telephone).put("type_compte", typeCompte)
        val body = JSONObject().put("email", email).put("password", password).put("data", data)
        val text = send("POST", url("auth/v1/signup").build(), json(body))
        return saveSession(JSONObject(text))
    }

    fun signIn(email: String, password: String) {
        val u = url("auth/v1/token").addQueryParameter("grant_type", "password").build()
        val text = send("POST", u, json(JSONObject().put("email", email).put("password", password)))
        if (!saveSession(JSONObject(text))) throw ApiException(0, "Connexion impossible.")
    }

    // ---------- Annonces ----------

    private fun parseList(text: String): List<Annonce> {
        val arr = JSONArray(text)
        val out = ArrayList<Annonce>(arr.length())
        for (i in 0 until arr.length()) out.add(Annonce.from(arr.getJSONObject(i)))
        return out
    }

    /** Annonces vérifiées et disponibles, visibles par tout le monde. */
    fun listPublic(recherche: String, filtres: Filtres, tri: String = TRI_RECENTS): List<Annonce> {
        val ordre = when (tri) {
            TRI_NOTES -> "note_moyenne.desc,nb_avis.desc,created_at.desc"
            TRI_PRIX -> "loyer.asc,created_at.desc"
            else -> "created_at.desc"
        }
        val b = url("rest/v1/annonces")
            .addQueryParameter("select", COLONNES)
            .addQueryParameter("status", "eq.verifiee")
            .addQueryParameter("disponible", "eq.true")
            .addQueryParameter("order", ordre)
            .addQueryParameter("limit", "100")
        val q = recherche.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
        if (q.isNotEmpty()) {
            b.addQueryParameter("or", "(ville.ilike.*$q*,quartier.ilike.*$q*,titre.ilike.*$q*)")
        }
        val f = filtres
        if (f.type != null) b.addQueryParameter("type", "eq.${f.type}")
        if (f.loyerMax != null) b.addQueryParameter("loyer", "lte.${f.loyerMax}")
        if (f.campus != null) b.addQueryParameter("campus", "eq.${f.campus}")
        if (f.distanceMax != null) b.addQueryParameter("and", "(distance_campus.gt.0,distance_campus.lte.${f.distanceMax})")
        if (f.compteurIndividuel) b.addQueryParameter("compteur_individuel", "eq.true")
        return parseList(send("GET", b.build()))
    }

    /** Annonces enregistrées en favoris qui sont toujours en ligne. */
    fun listParIds(ids: Collection<String>): List<Annonce> {
        val propres = ids.filter { id -> id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' } }
        if (propres.isEmpty()) return emptyList()
        val u = url("rest/v1/annonces")
            .addQueryParameter("select", COLONNES)
            .addQueryParameter("id", "in.(${propres.joinToString(",")})")
            .addQueryParameter("status", "eq.verifiee")
            .addQueryParameter("disponible", "eq.true")
            .addQueryParameter("order", "created_at.desc")
            .build()
        return parseList(send("GET", u))
    }

    fun mesAnnonces(): List<Annonce> {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/annonces")
            .addQueryParameter("select", COLONNES)
            .addQueryParameter("proprietaire", "eq.$uid")
            .addQueryParameter("order", "created_at.desc")
            .build()
        return parseList(send("GET", u, auth = true))
    }

    /** Crée l'annonce (id == null) ou la modifie. Retourne l'identifiant de l'annonce. */
    fun enregistrer(id: String?, champs: JSONObject): String {
        if (id == null) {
            val u = url("rest/v1/annonces").addQueryParameter("select", "id").build()
            val text = send("POST", u, json(champs), auth = true, prefer = "return=representation")
            val arr = JSONArray(text)
            if (arr.length() == 0) throw ApiException(0, "Annonce non enregistrée.")
            return arr.getJSONObject(0).optString("id")
        }
        val u = url("rest/v1/annonces").addQueryParameter("id", "eq.$id").build()
        send("PATCH", u, json(champs), auth = true, prefer = "return=minimal")
        return id
    }

    /**
     * Numéro du propriétaire. Retourne null si le Pass Réservation est exigé et que le compte
     * n'en a pas (le propriétaire et les administrateurs le voient toujours).
     */
    fun contact(annonceId: String): String? {
        val body = JSONObject().put("p_annonce", annonceId)
        val text = send("POST", url("rest/v1/rpc/contact_annonce").build(), json(body), auth = true).trim()
        if (text.isEmpty() || text == "null") return null
        val arr = JSONArray("[$text]")
        return if (arr.isNull(0)) null else arr.optString(0).ifBlank { null }
    }

    fun changerDisponibilite(id: String, disponible: Boolean) {
        enregistrer(id, JSONObject().put("disponible", disponible))
    }

    fun supprimer(id: String) {
        val u = url("rest/v1/annonces").addQueryParameter("id", "eq.$id").build()
        send("DELETE", u, auth = true)
    }

    /** Envoie une photo JPEG et retourne son adresse publique. */
    fun envoyerPhoto(jpeg: ByteArray): String {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val path = "$uid/${UUID.randomUUID()}.jpg"
        send("POST", url("storage/v1/object/photos/$path").build(), jpeg.toRequestBody(JPEG_TYPE), auth = true)
        return "$baseUrl/storage/v1/object/public/photos/$path"
    }

    // ---------- Justificatifs (espace privé) ----------

    /** Envoie la photo d'un justificatif dans l'espace privé et retourne son chemin. */
    fun envoyerJustificatif(jpeg: ByteArray): String {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val path = "$uid/${UUID.randomUUID()}.jpg"
        send("POST", url("storage/v1/object/justificatifs/$path").build(), jpeg.toRequestBody(JPEG_TYPE), auth = true)
        return path
    }

    fun ajouterJustificatif(annonceId: String, type: String, chemin: String) {
        val body = JSONObject().put("annonce_id", annonceId).put("type", type).put("chemin", chemin)
        send("POST", url("rest/v1/justificatifs").build(), json(body), auth = true, prefer = "return=minimal")
    }

    fun justificatifs(annonceId: String): List<Justificatif> {
        val u = url("rest/v1/justificatifs")
            .addQueryParameter("select", "id,type,chemin")
            .addQueryParameter("annonce_id", "eq.$annonceId")
            .addQueryParameter("order", "created_at.desc")
            .build()
        val arr = JSONArray(send("GET", u, auth = true))
        val out = ArrayList<Justificatif>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Justificatif(o.optString("id"), o.optString("type"), o.optString("chemin")))
        }
        return out
    }

    /** Adresse temporaire (15 min) pour ouvrir un justificatif : propriétaire ou administrateur. */
    fun lienJustificatif(chemin: String): String {
        val u = url("storage/v1/object/sign/justificatifs/$chemin").build()
        val text = send("POST", u, json(JSONObject().put("expiresIn", 900)), auth = true)
        val o = JSONObject(text)
        val lien = o.optString("signedURL").ifBlank { o.optString("signedUrl") }
        if (lien.isBlank()) throw ApiException(0, "Lien indisponible.")
        return when {
            lien.startsWith("http") -> lien
            lien.startsWith("/storage/") -> baseUrl + lien
            else -> "$baseUrl/storage/v1" + (if (lien.startsWith("/")) lien else "/$lien")
        }
    }

    // ---------- Pass Réservation (Mobile Money via CinetPay) ----------

    fun etatPass(): EtatPass {
        val text = send("POST", url("rest/v1/rpc/etat_pass").build(), json(JSONObject()), auth = true)
        val o = JSONObject(text)
        return EtatPass(
            obligatoire = o.optBoolean("obligatoire", false),
            prix = o.optInt("prix", 1500),
            duree = o.optInt("duree", 30),
            fin = if (o.isNull("fin")) null else o.optString("fin").ifBlank { null }
        )
    }

    /** Crée le paiement et retourne l'adresse de la page CinetPay (Wave, Orange, MTN, Moov). */
    fun initierPass(): String {
        val u = url("functions/v1/pass-cinetpay").addQueryParameter("action", "initier").build()
        val o = JSONObject(send("POST", u, json(JSONObject()), auth = true))
        put("pass_tx", o.optString("transaction_id"))
        return o.optString("payment_url")
    }

    /** Transaction en attente sur ce téléphone (null si aucune). */
    val passEnAttente: String? get() = get("pass_tx")?.ifBlank { null }

    /** Vérifie la transaction en attente ; retourne la fin du Pass s'il est activé, sinon null. */
    fun verifierPass(): String? {
        val tx = passEnAttente ?: return null
        val u = url("functions/v1/pass-cinetpay").addQueryParameter("action", "verifier").build()
        val o = JSONObject(send("POST", u, json(JSONObject().put("transaction_id", tx)), auth = true))
        val statut = o.optString("statut")
        if (statut != "en_attente") put("pass_tx", null)
        return if (statut == "paye" && !o.isNull("fin")) o.optString("fin") else null
    }

    fun signaler(annonceId: String, motif: String) {
        val body = JSONObject().put("annonce_id", annonceId).put("motif", motif)
        send("POST", url("rest/v1/signalements").build(), json(body), prefer = "return=minimal")
    }

    // ---------- Avis et notes (appli chercheur) ----------

    /** Les avis d'une annonce, du plus récent au plus ancien. Lisibles sans compte. */
    fun avis(annonceId: String): List<Avis> {
        val u = url("rest/v1/avis")
            .addQueryParameter("select", "id,auteur,auteur_nom,note,commentaire,created_at")
            .addQueryParameter("annonce_id", "eq.$annonceId")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", "100")
            .build()
        val arr = JSONArray(send("GET", u))
        val out = ArrayList<Avis>(arr.length())
        for (i in 0 until arr.length()) out.add(Avis.from(arr.getJSONObject(i)))
        return out
    }

    /** Publie l'avis du compte connecté, ou remplace celui qu'il a déjà donné sur cette annonce. */
    fun donnerAvis(annonceId: String, note: Int, commentaire: String) {
        val uid = userId ?: throw ApiException(401, "Connecte-toi pour donner ton avis.")
        val existant = url("rest/v1/avis")
            .addQueryParameter("select", "id")
            .addQueryParameter("annonce_id", "eq.$annonceId")
            .addQueryParameter("auteur", "eq.$uid")
            .build()
        val deja = JSONArray(send("GET", existant, auth = true))
        val champs = JSONObject().put("note", note.coerceIn(1, 5)).put("commentaire", commentaire)
        if (deja.length() > 0) {
            val id = deja.getJSONObject(0).optString("id")
            val u = url("rest/v1/avis").addQueryParameter("id", "eq.$id").build()
            send("PATCH", u, json(champs), auth = true, prefer = "return=minimal")
        } else {
            champs.put("annonce_id", annonceId)
            send("POST", url("rest/v1/avis").build(), json(champs), auth = true, prefer = "return=minimal")
        }
    }

    // ---------- Profil locataire et demandes ----------

    /** Profil du compte connecté (null s'il est introuvable). */
    fun monProfil(): Profil? {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/profils")
            .addQueryParameter("select", "*")
            .addQueryParameter("id", "eq.$uid")
            .build()
        val arr = JSONArray(send("GET", u, auth = true))
        return if (arr.length() > 0) Profil.from(arr.getJSONObject(0)) else null
    }

    fun enregistrerProfil(champs: JSONObject) {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/profils").addQueryParameter("id", "eq.$uid").build()
        send("PATCH", u, json(champs), auth = true, prefer = "return=minimal")
    }

    /** Le chercheur envoie sa demande au propriétaire ; son profil locataire l'accompagne. */
    fun envoyerDemande(annonceId: String, message: String) {
        val body = JSONObject().put("annonce_id", annonceId).put("message", message)
        try {
            send("POST", url("rest/v1/demandes").build(), json(body), auth = true, prefer = "return=minimal")
        } catch (e: ApiException) {
            if (e.code == 409) throw ApiException(409, "Tu as déjà envoyé une demande pour ce logement.")
            throw e
        }
    }

    /** Demandes envoyées par le chercheur connecté. */
    fun mesDemandes(): List<Demande> {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/demandes")
            .addQueryParameter("select", "id,message,status,created_at,annonces(titre)")
            .addQueryParameter("locataire", "eq.$uid")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", "100")
            .build()
        return parseDemandes(send("GET", u, auth = true))
    }

    /** Demandes reçues sur les annonces du propriétaire connecté, avec le profil de chaque locataire. */
    fun demandesRecues(): List<Demande> {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/demandes")
            .addQueryParameter("select", "id,message,status,created_at,profils(*),annonces!inner(titre,proprietaire)")
            .addQueryParameter("annonces.proprietaire", "eq.$uid")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", "200")
            .build()
        return parseDemandes(send("GET", u, auth = true))
    }

    /** Le propriétaire accepte ("acceptee") ou refuse ("refusee") une demande. */
    fun repondreDemande(id: String, status: String) {
        val u = url("rest/v1/demandes").addQueryParameter("id", "eq.$id").build()
        send("PATCH", u, json(JSONObject().put("status", status)), auth = true, prefer = "return=minimal")
    }

    private fun parseDemandes(text: String): List<Demande> {
        val arr = JSONArray(text)
        val out = ArrayList<Demande>(arr.length())
        for (i in 0 until arr.length()) out.add(Demande.from(arr.getJSONObject(i)))
        return out
    }

    // ---------- Administration (comptes présents dans la table admins) ----------

    fun estAdmin(): Boolean {
        val text = send("POST", url("rest/v1/rpc/is_admin").build(), json(JSONObject()), auth = true)
        return text.trim() == "true"
    }

    fun annoncesParStatut(status: String): List<Annonce> {
        val u = url("rest/v1/annonces")
            .addQueryParameter("select", COLONNES)
            .addQueryParameter("status", "eq.$status")
            .addQueryParameter("order", if (status == "en_attente") "created_at.asc" else "created_at.desc")
            .addQueryParameter("limit", "200")
            .build()
        return parseList(send("GET", u, auth = true))
    }

    fun changerStatut(id: String, status: String, motif: String?) {
        val body = JSONObject()
            .put("status", status)
            .put("motif_refus", if (motif.isNullOrBlank()) JSONObject.NULL else motif)
        val u = url("rest/v1/annonces").addQueryParameter("id", "eq.$id").build()
        send("PATCH", u, json(body), auth = true, prefer = "return=minimal")
    }

    fun signalements(): List<Signalement> {
        val u = url("rest/v1/signalements")
            .addQueryParameter("select", "id,motif,created_at,annonces($COLONNES)")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", "200")
            .build()
        val arr = JSONArray(send("GET", u, auth = true))
        val out = ArrayList<Signalement>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val a = o.optJSONObject("annonces")
            out.add(Signalement(o.optString("id"), o.optString("motif"), if (a != null) Annonce.from(a) else null))
        }
        return out
    }

    fun supprimerSignalement(id: String) {
        val u = url("rest/v1/signalements").addQueryParameter("id", "eq.$id").build()
        send("DELETE", u, auth = true)
    }

    /** Nom, téléphone et type de compte d'un propriétaire (null si introuvable). */
    fun profil(id: String): JSONObject? {
        val u = url("rest/v1/profils")
            .addQueryParameter("select", "nom,telephone,type_compte")
            .addQueryParameter("id", "eq.$id")
            .build()
        val arr = JSONArray(send("GET", u, auth = true))
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }
}
