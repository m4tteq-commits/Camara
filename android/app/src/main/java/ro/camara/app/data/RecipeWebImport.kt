package ro.camara.app.data

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl

internal object RecipeWebImport {
    private const val maxPageBytes = 2_000_000L
    private val link = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val scripts = Regex("""<script\b([^>]*)>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val jsonLd = Regex("""\btype\s*=\s*["']?application/ld\+json(?:["'\s]|$)""", RegexOption.IGNORE_CASE)

    fun sharedLink(text: String) = link.find(text.take(16000))?.value?.trimEnd('.', ',', ')', ']')?.take(2048)

    private val tags = Regex("<[^>]*>")
    private val lineBreak = Regex("</?(?:br|p|li|div)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "deg" to "°", "frac12" to "½", "frac14" to "¼", "frac34" to "¾")
    private fun plain(value: String): String = entities.replace(tags.replace(value, "")) { match ->
        val entity = match.groupValues[1]
        val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else if (entity.startsWith('#')) entity.drop(1).toIntOrNull() else null
        if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF && code > 0) String(Character.toChars(code))
        else namedEntities[entity] ?: match.value
    }.trim()

    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).filter(::publicAddress).ifEmpty {
                    throw UnknownHostException("Adresa site-ului trebuie să fie publică.")
                }
        }).build()

    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress || address.isMulticastAddress) return false
        val octets = address.address.map { it.toInt() and 255 }
        if (octets.size == 16) {
            return octets[0] in 32..63 && !(octets[0] == 32 && octets[1] in 1..2) &&
                !(octets[0] == 63 && octets[1] == 255)
        }
        val (a, b, c) = octets
        return a in 1..223 && a !in setOf(10, 127) &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && (b in setOf(0, 168) || b == 88 && c == 99)) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
    }

    internal fun validateUrl(url: HttpUrl) {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            '.' !in url.host || ':' in url.host || url.host.all { it.isDigit() || it == '.' }) {
            throw IOException("Folosește adresa HTTPS a unui site public, fără IP sau parolă.")
        }
    }

    private data class Page(val redirect: String? = null, val html: String? = null)
    private suspend fun fetch(url: HttpUrl, deadline: Long): Page = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml").build())
        call.timeout().deadlineNanoTime(deadline)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (it.code in setOf(301, 302, 303, 307, 308)) {
                        Page(redirect = it.header("Location") ?: throw IOException("Redirecționare invalidă."))
                    } else {
                        if (!it.isSuccessful) throw IOException("Site-ul nu a putut fi deschis (HTTP ${it.code}).")
                        val body = it.body ?: throw IOException("Pagina nu conține text.")
                        val type = body.contentType()
                        if (type != null && type.subtype !in setOf("html", "xhtml+xml")) throw IOException("Linkul trebuie să deschidă o pagină web.")
                        val source = body.source()
                        if (source.request(maxPageBytes + 1)) throw IOException("Pagina rețetei este prea mare.")
                        Page(html = source.readString(type?.charset() ?: Charsets.UTF_8))
                    }
                } }
                result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }

    suspend fun load(raw: String): String = withContext(Dispatchers.IO) {
        try { withTimeout(10_000) { loadWithinDeadline(raw) } }
        catch (e: TimeoutCancellationException) { throw IOException("Site-ul nu a răspuns în 10 secunde. Reîncearcă.", e) }
    }

    private suspend fun loadWithinDeadline(raw: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var url = raw.trim().toHttpUrlOrNull() ?: throw IOException("Introdu un link către o rețetă.")
        repeat(4) {
            validateUrl(url)
            val page = fetch(url, deadline)
            if (page.redirect != null) url = url.resolve(page.redirect) ?: throw IOException("Redirecționare invalidă.")
            else return extract(page.html.orEmpty()) ?: throw IOException("Site-ul nu publică o fișă de rețetă. Copiază ingredientele și pașii în «Lipește o rețetă».")
        }
        throw IOException("Site-ul a redirecționat de prea multe ori.")
    }

    private fun nodes(value: JsonElement?, depth: Int = 0): Sequence<JsonObject> = sequence {
        if (depth > 12) return@sequence
        when (value) {
            is JsonObject -> {
                yield(value)
                value.values.forEach { yieldAll(nodes(it, depth + 1)) }
            }
            is JsonArray -> value.forEach { yieldAll(nodes(it, depth + 1)) }
            else -> Unit
        }
    }

    private fun lines(value: JsonElement?, depth: Int = 0): List<String> {
        if (depth > 10) return emptyList()
        return when (value) {
            is JsonArray -> value.flatMap { lines(it, depth + 1) }
            is JsonObject -> lines(value["itemListElement"] ?: value["text"] ?: value["value"] ?: value["name"], depth + 1)
            is JsonPrimitive -> value.contentOrNull?.let { lineBreak.replace(it, "\n") }?.lineSequence()
                ?.map(::plain)?.filter(String::isNotEmpty)?.toList().orEmpty()
            else -> emptyList()
        }
    }

    internal fun extract(html: String): String? {
        if (html.toByteArray(Charsets.UTF_8).size > maxPageBytes) return null
        for (script in scripts.findAll(html)) {
            if (!jsonLd.containsMatchIn(script.groupValues[1])) continue
            val root = runCatching { Json.parseToJsonElement(script.groupValues[2].trim()) }.getOrNull()
            for (entry in nodes(root)) {
                val types = lines(entry["@type"])
                if (types.none { it.substringAfterLast('/').substringAfterLast(':') == "Recipe" }) continue
                val ingredients = lines(entry["recipeIngredient"])
                val steps = lines(entry["recipeInstructions"])
                if (ingredients.isEmpty() || steps.isEmpty()) continue
                val minutes = runCatching { Duration.parse((entry["totalTime"] as? JsonPrimitive)?.content).toMinutes() }.getOrNull()
                val servings = lines(entry["recipeYield"]).firstOrNull()?.let {
                    Regex("""^(\d{1,2})(?:$|\s+(?:porții|servings|persoane)\b)""", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1)
                }
                val title = lines(entry["name"]).firstOrNull() ?: "Rețetă de pe web"
                val text = buildList {
                    add(title)
                    if (minutes != null && minutes in 1..180) add("Timp: $minutes min")
                    if (servings != null && servings.toInt() in 1..8) add("Porții: $servings")
                    add("Ingrediente:")
                    addAll(ingredients)
                    add("Pași:")
                    addAll(steps)
                }.joinToString("\n")
                if (text.length <= RECIPE_TEXT_LIMIT) return text
            }
        }
        return null
    }
}
