package ro.camara.app.data

import java.io.IOException
import java.io.File
import android.content.ContentResolver
import android.net.Uri
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

class ApiException(val code: Int, message: String) : Exception(message)

@Serializable
data class ServerStatus(val status: String, val version: String, val database: String? = null, val schema_version: Int? = null,
    val last_backup_at: String? = null, val backup_error: String? = null)

data class ConnectionReport(val status: ServerStatus, val viaLan: Boolean, val https: Boolean)
internal data class SnapshotReply(val snapshot: Snapshot?, val etag: String?)

internal fun mayTryOtherRoute(method: String, headersStarted: Boolean): Boolean =
    !headersStarted || method == "GET" || method == "HEAD"

class CamaraApi(private val config: ConnectionConfig, fast: Boolean = false, private val epoch: String = "") {
    private val url = config.baseUrl.toHttpUrl()
    private val profile = if (fast) Profile.Fast else Profile.Normal

    private fun execute(request: Request, profile: Profile = this.profile): Response {
        val lanAddress = config.lanAddress?.takeIf { url.isHttps }
        if (lanAddress == null) return primaryClients.getValue(profile).newCall(request).execute()
        val route = synchronized(routes) {
            routes.getOrPut(url to lanAddress) { LanRoute(url.host, lanAddress) }.also {
                if (routes.size > 4) routes.remove(routes.keys.first())
            }
        }
        val lanFirst = route.preferred
        fun run(viaLan: Boolean, attempt: RouteAttempt): Response {
            val transport = if (viaLan) route.clients.getValue(profile) else primaryClients.getValue(profile)
            return transport.newCall(request.newBuilder().tag(RouteAttempt::class.java, attempt).build())
                .execute().also { if (it.isSuccessful) route.preferred = viaLan }
        }
        val attempt = RouteAttempt(lanFirst)
        try {
            return run(lanFirst, attempt)
        } catch (error: IOException) {
            if (!mayTryOtherRoute(request.method, attempt.headersStarted) || Thread.currentThread().isInterrupted) throw error
            try {
                return run(!lanFirst, RouteAttempt(!lanFirst))
            } catch (fallback: IOException) {
                // Keep certificate failures visible even if the other address is unreachable.
                if (error is javax.net.ssl.SSLException && fallback !is javax.net.ssl.SSLException) throw error
                throw fallback
            }
        }
    }

    private class RouteAttempt(val viaLan: Boolean) : EventListener() {
        var headersStarted = false
            private set

        override fun requestHeadersStart(call: Call) {
            headersStarted = true
        }
    }

    private enum class Profile(val connect: Long, val read: Long, val total: Long) {
        Fast(3, 8, 12), Normal(8, 250, 260), Download(8, 60, 1200)
    }

    private class LanRoute(host: String, address: String) {
        @Volatile var preferred = false
        private val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == host) listOf(InetAddress.getByName(address)) else Dns.SYSTEM.lookup(hostname)
        }
        val clients = primaryClients.mapValues { (_, client) -> client.newBuilder().dns(dns).proxy(Proxy.NO_PROXY).build() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        private val transport = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .eventListenerFactory { call -> call.request().tag(RouteAttempt::class.java) ?: EventListener.NONE }
            .followRedirects(false).followSslRedirects(false).build()
        private val primaryClients = Profile.entries.associateWith {
            transport.newBuilder().connectTimeout(it.connect, TimeUnit.SECONDS).readTimeout(it.read, TimeUnit.SECONDS)
                .callTimeout(it.total, TimeUnit.SECONDS).build()
        }
        private val routes = linkedMapOf<Pair<okhttp3.HttpUrl, String>, LanRoute>()
        private var activeNetwork: Long? = null
        @Volatile internal var routeGeneration = 0L
            private set

        internal fun networkChanged(network: Long?) {
            synchronized(routes) {
                if (network == activeNetwork) return
                activeNetwork = network
                routes.clear()
                transport.connectionPool.evictAll()
                routeGeneration++
            }
        }
    }

    suspend fun testConnection(): ConnectionReport = withContext(Dispatchers.IO) {
        fun probe(path: String): ConnectionReport? {
            val request = Request.Builder().url(config.baseUrl + path)
                .header("Authorization", "Bearer ${config.token}").build()
            return execute(request, Profile.Fast).use { response ->
                if (path == "/v1/status" && response.code == 404) return@use null
                if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                    401, 403 -> "Cheia de acces a fost respinsă."
                    503 -> "Serverul a răspuns, dar serviciul nu este disponibil."
                    else -> "Serverul a răspuns cu HTTP ${response.code}."
                })
                val status = json.decodeFromString<ServerStatus>(response.body?.string().orEmpty())
                require(status.status == "ok" && status.version.isNotBlank())
                ConnectionReport(status, response.request.tag(RouteAttempt::class.java)?.viaLan == true, url.isHttps)
            }
        }
        probe("/v1/status") ?: checkNotNull(probe("/health"))
    }

    private suspend fun request(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(config.baseUrl + path)
            .header("Authorization", "Bearer ${config.token}")
            .header("X-Camara-Epoch", epoch)
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                throw ApiException(response.code, detail ?: when (response.code) {
                    401 -> "Cheia de acces nu este corectă. Verifică setările."
                    422 -> "Verifică datele introduse."
                    else -> "Serverul a răspuns cu eroarea ${response.code}."
                })
            }
            text
        }
    }

    suspend fun snapshot(): Snapshot = checkNotNull(snapshotReply().snapshot)
    internal suspend fun snapshotReply(etag: String? = null, base: Snapshot? = null): SnapshotReply = withContext(Dispatchers.IO) {
        fun fetch(tag: String?, snapshot: Snapshot?): SnapshotReply? {
            val target = url.newBuilder().addPathSegments("v1/snapshot").apply {
                if (tag != null && snapshot != null) addQueryParameter("since", tag)
            }.build()
            val builder = Request.Builder().url(target).header("Authorization", "Bearer ${config.token}")
            tag?.let { builder.header("If-None-Match", it) }
            return execute(builder.build()).use { response ->
                val newTag = response.header("ETag")
                if (response.code == 304 && tag != null) return@use SnapshotReply(null, newTag ?: tag)
                if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
                    401 -> "Cheia de acces nu este corectă. Verifică setările."
                    429 -> "Prea multe cereri. Reîncearcă puțin mai târziu."
                    else -> "Sincronizarea nu a reușit (HTTP ${response.code})."
                })
                val body = response.body?.string().orEmpty()
                if (response.header("X-Camara-Snapshot-Mode") != "delta") {
                    SnapshotReply(json.decodeFromString<Snapshot>(body), newTag)
                } else if (tag != null && snapshot != null && newTag != null) {
                    try { SnapshotReply(applySnapshotDelta(json, body, tag, snapshot), newTag) }
                    catch (_: IllegalArgumentException) { null }
                } else null
            }
        }
        fetch(etag, base) ?: fetch(null, null) ?: throw IOException("Serverul nu a trimis o copie completă a datelor.")
    }
    suspend fun aiStatus(): AiStatus = json.decodeFromString(request("GET", "/v1/ai/status"))
    suspend fun cloudProviders(): Map<String, String> = try {
        json.decodeFromString(request("GET", "/v1/ai/providers"))
    } catch (error: ApiException) {
        if (error.code != 404) throw error
        emptyMap()
    }
    suspend fun weekPreview(input: WeekRequest): WeekPreview = json.decodeFromString(request("POST", "/v1/plans/preview", json.encodeToString(input)))
    suspend fun touchAiSession(id: String) { request("PUT", "/v1/ai/sessions/$id", "{}") }
    suspend fun releaseAiSession(id: String) { request("DELETE", "/v1/ai/sessions/$id") }
    suspend fun putPantry(id: String, input: PantryInput) { request("PUT", "/v1/pantry/$id", json.encodeToString(input)) }
    suspend fun deletePantry(id: String) { request("DELETE", "/v1/pantry/$id") }
    suspend fun putShopping(id: String, input: ShoppingInput) { request("PUT", "/v1/shopping/$id", json.encodeToString(input)) }
    suspend fun deleteShopping(id: String) { request("DELETE", "/v1/shopping/$id") }
    suspend fun generate(input: RecipeRequest): Recipe = json.decodeFromString(request("POST", "/v1/recipes/generate", json.encodeToString(input)))
    suspend fun generateCloud(provider: String, input: RecipeRequest): Recipe {
        require(provider in cloudAiLabels)
        return json.decodeFromString(request("POST", "/v1/recipes/cloud/$provider", json.encodeToString(input)))
    }
    suspend fun recipeSuggestions(input: RecipeRequest): List<Recipe> =
        json.decodeFromString(request("POST", "/v1/recipes/suggestions", json.encodeToString(input)))
    internal suspend fun importRecipeText(text: String): AiImportedRecipe =
        json.decodeFromString(request("POST", "/v1/personal-recipes/import-preview", json.encodeToString(RecipeImportText(text))))
    internal suspend fun uploadRecipePdf(resolver: ContentResolver, uri: Uri): PdfUpload =
        withContext(Dispatchers.IO) {
            val body = object : RequestBody() {
                override fun contentType(): MediaType = "application/pdf".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    val source = resolver.openInputStream(uri) ?: throw IOException("Nu pot deschide PDF-ul ales.")
                    source.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            size += count
                            if (size > RECIPE_PDF_MAX_BYTES) throw IOException("PDF-ul depășește 20 MiB.")
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            }
            val target = url.newBuilder().addPathSegments("v1/personal-recipes/pdf-uploads").build()
            val call = Request.Builder().url(target).header("Authorization", "Bearer ${config.token}").post(body).build()
            execute(call).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                    throw ApiException(response.code, detail ?: "PDF-ul nu a putut fi importat.")
                }
                json.decodeFromString<PdfUpload>(text)
            }
        }
    internal suspend fun previewRecipePdf(id: String, first: Int, last: Int): PdfRecipes =
        json.decodeFromString(request("POST", "/v1/personal-recipes/pdf-uploads/$id/preview?first=$first&last=$last", "{}"))
    internal suspend fun recipeAdvice(id: String, goal: String): RecipeAdvice =
        json.decodeFromString(request("POST", "/v1/personal-recipes/$id/advice", json.encodeToString(RecipeAdviceRequest(goal))))
    internal suspend fun recipeConversation(id: String, input: RecipeConversationRequest): RecipeConversationReply =
        json.decodeFromString(request("POST", "/v1/personal-recipes/$id/conversation", json.encodeToString(input)))
    internal suspend fun catalogConversation(recipe: Recipe, input: RecipeConversationRequest): RecipeConversationReply =
        json.decodeFromString(request("POST", "/v1/recipes/conversation", json.encodeToString(CatalogConversation(recipe, input))))

    @Serializable
    private data class CatalogConversation(val recipe: Recipe, val conversation: RecipeConversationRequest)
    suspend fun useSoonIdeas(): List<UseSoonIdea> = json.decodeFromString(request("GET", "/v1/recipes/use-soon"))
    internal suspend fun history(kind: HistoryKind, cursor: String?): HistoryRows {
        // The API returns a URL-safe opaque cursor.
        require(cursor == null || Regex("[A-Za-z0-9_-]{1,256}").matches(cursor))
        val query = cursor?.let { "?cursor=$it" }.orEmpty()
        return json.decodeFromString<HistoryPage>(request("GET", "/v1/history/${kind.path}$query")).rows(kind, json)
    }
    suspend fun addMissing(id: String) { request("POST", "/v1/recipes/$id/shopping", "{}") }
    suspend fun preview(text: String): BulkPreview = json.decodeFromString(request("POST", "/v1/pantry/preview", json.encodeToString(BulkTextRequest(text))))
    suspend fun importBatch(id: String, items: List<PantryItem>) { request("PUT", "/v1/pantry/batches/$id", json.encodeToString(PantryBatch(items))) }
    suspend fun recipeOptions(input: RecipeRequest): DeviceOptions = json.decodeFromString(request("POST", "/v1/recipes/options", json.encodeToString(input)))
    suspend fun choose(input: DeviceChoice): Recipe = json.decodeFromString(request("POST", "/v1/recipes/choose", json.encodeToString(input)))
    suspend fun modelInfo(): DeviceModelInfo = json.decodeFromString(request("GET", "/v1/ai/device-model"))
    internal suspend fun applyAction(id: String, action: Mutation): MutationResult =
        json.decodeFromString(request("PUT", "/v1/actions/$id", ActionRules.json.encodeToString(action)))
    suspend fun backups(): BackupOverview = json.decodeFromString(request("GET", "/v1/backups"))
    suspend fun createBackup() { request("POST", "/v1/backups", "{}") }
    suspend fun configureBackups(value: BackupSettings) { request("PUT", "/v1/backups/settings", json.encodeToString(value)) }
    suspend fun previewRestore(id: String): RestorePreview = json.decodeFromString(request("POST", "/v1/backups/$id/preview", "{}"))
    suspend fun restore(id: String, value: RestoreRequest) { request("POST", "/v1/backups/$id/restore", json.encodeToString(value)) }
    suspend fun scanReceipt(bytes: ByteArray): ReceiptPreview =
        json.decodeFromString(uploadPhoto("/v1/receipts/preview", bytes, "Bonul nu a putut fi citit."))

    internal suspend fun recipePhotoText(bytes: ByteArray): String =
        json.decodeFromString<RecipePhotoText>(uploadPhoto("/v1/personal-recipes/photo-text", bytes,
            "Fotografia nu a putut fi citită.")).text

    private suspend fun uploadPhoto(path: String, bytes: ByteArray, fallbackError: String): String = withContext(Dispatchers.IO) {
        val call = Request.Builder().url(config.baseUrl + path)
            .header("Authorization", "Bearer ${config.token}").post(bytes.toRequestBody("image/jpeg".toMediaType())).build()
        execute(call).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull()
                throw ApiException(response.code, message ?: fallbackError)
            }
            text
        }
    }

    suspend fun downloadModel(info: DeviceModelInfo, target: File, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(info.path == "/v1/ai/device-model/file" && info.bytes in 1..1_000_000_000 && info.sha256.matches(Regex("[a-f0-9]{64}")))
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".partial")
        val request = Request.Builder().url(config.baseUrl + info.path).header("Authorization", "Bearer ${config.token}").build()
        try {
            execute(request, Profile.Download).use { response ->
                if (!response.isSuccessful) throw IOException("Modelul nu poate fi descărcat de pe server.")
                val body = response.body ?: throw IOException("Fișierul modelului lipsește.")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastProgress = -1
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > info.bytes) throw IOException("Modelul primit este mai mare decât cel așteptat.")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (total * 100 / info.bytes).toInt()
                        if (percent != lastProgress) {
                            lastProgress = percent
                            progress(percent)
                        }
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != info.bytes || hash != info.sha256) throw IOException("Fișierul modelului nu a trecut verificarea. Reia descărcarea.")
                check(temporary.renameTo(target)) { "Nu pot salva modelul pe dispozitiv." }
            }
        } finally { temporary.delete() }
    }
}
