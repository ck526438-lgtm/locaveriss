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

/** Accès à Supabase (Auth, base de données, stockage) par son API REST. À appeler hors du thread principal. */
object Api {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val JPEG_TYPE = "image/jpeg".toMediaType()

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
    fun listPublic(recherche: String, type: String?, loyerMax: Int?): List<Annonce> {
        val b = url("rest/v1/annonces")
            .addQueryParameter("select", "*")
            .addQueryParameter("status", "eq.verifiee")
            .addQueryParameter("disponible", "eq.true")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", "100")
        val q = recherche.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim()
        if (q.isNotEmpty()) {
            b.addQueryParameter("or", "(ville.ilike.*$q*,quartier.ilike.*$q*,titre.ilike.*$q*)")
        }
        if (type != null) b.addQueryParameter("type", "eq.$type")
        if (loyerMax != null) b.addQueryParameter("loyer", "lte.$loyerMax")
        return parseList(send("GET", b.build()))
    }

    fun mesAnnonces(): List<Annonce> {
        val uid = userId ?: throw ApiException(401, "Reconnecte-toi.")
        val u = url("rest/v1/annonces")
            .addQueryParameter("select", "*")
            .addQueryParameter("proprietaire", "eq.$uid")
            .addQueryParameter("order", "created_at.desc")
            .build()
        return parseList(send("GET", u, auth = true))
    }

    /** Crée l'annonce (id == null) ou la modifie. */
    fun enregistrer(id: String?, champs: JSONObject) {
        if (id == null) {
            send("POST", url("rest/v1/annonces").build(), json(champs), auth = true, prefer = "return=minimal")
        } else {
            val u = url("rest/v1/annonces").addQueryParameter("id", "eq.$id").build()
            send("PATCH", u, json(champs), auth = true, prefer = "return=minimal")
        }
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

    fun signaler(annonceId: String, motif: String) {
        val body = JSONObject().put("annonce_id", annonceId).put("motif", motif)
        send("POST", url("rest/v1/signalements").build(), json(body), prefer = "return=minimal")
    }

    // ---------- Administration (comptes présents dans la table admins) ----------

    fun estAdmin(): Boolean {
        val text = send("POST", url("rest/v1/rpc/is_admin").build(), json(JSONObject()), auth = true)
        return text.trim() == "true"
    }

    fun annoncesParStatut(status: String): List<Annonce> {
        val u = url("rest/v1/annonces")
            .addQueryParameter("select", "*")
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
            .addQueryParameter("select", "id,motif,created_at,annonces(*)")
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
