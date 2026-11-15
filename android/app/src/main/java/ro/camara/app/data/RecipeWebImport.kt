package ro.camara.app.data

import android.text.Html
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object RecipeWebImport {
    private const val maxPageBytes = 2_000_000L
    private val link = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val scripts = Regex("""<script\b([^>]*)>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val jsonLd = Regex("""\btype\s*=\s*["']?application/ld\+json(?:["'\s]|$)""", RegexOption.IGNORE_CASE)

    fun sharedLink(text: String) = link.find(text.take(16000))?.value?.trimEnd('.', ',', ')', ']')?.take(2048)

    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).filter(::publicAddress).ifEmpty {
                    throw UnknownHostException("Adresa site-ului trebuie să fie publică.")
                }
        }).build()

    private fun publicAddress(address: InetAddress): Boolean {
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

    suspend fun load(raw: String): String = withContext(Dispatchers.IO) {
        var url = raw.trim().toHttpUrlOrNull() ?: throw IOException("Introdu un link către o rețetă.")
        repeat(4) {
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || '.' !in url.host) {
                throw IOException("Folosește adresa HTTPS a unei pagini publice.")
            }
            // OkHttp can bypass DNS for numeric hosts. Reject those before opening a connection.
            if (url.host.all { it.isDigit() || it == '.' }) throw IOException("Folosește numele site-ului din browser.")
            val request = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml").build()
            client.newCall(request).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let(url::resolve) ?: throw IOException("Redirecționarea nu poate fi deschisă.")
                } else {
                    if (!response.isSuccessful) throw IOException("Site-ul nu a putut fi deschis (HTTP ${response.code}).")
                    val body = response.body ?: throw IOException("Pagina nu conține text.")
                    val type = body.contentType()
                    if (type != null && type.subtype !in setOf("html", "xhtml+xml")) throw IOException("Linkul trebuie să deschidă o pagină web.")
                    val source = body.source()
                    if (source.request(maxPageBytes + 1)) throw IOException("Pagina rețetei este prea mare.")
                    val html = source.readString(type?.charset() ?: Charsets.UTF_8)
                    return@withContext extract(html) ?: throw IOException("Site-ul nu publică o fișă de rețetă. Copiază ingredientele și pașii în «Lipește o rețetă».")
                }
            }
        }
        throw IOException("Site-ul a redirecționat de prea multe ori.")
    }

    private fun nodes(value: Any?, depth: Int = 0): Sequence<JSONObject> = sequence {
        if (depth > 12) return@sequence
        when (value) {
            is JSONObject -> {
                yield(value)
                value.keys().forEach { yieldAll(nodes(value.opt(it), depth + 1)) }
            }
            is JSONArray -> for (index in 0 until value.length()) yieldAll(nodes(value.opt(index), depth + 1))
        }
    }

    private fun lines(value: Any?, depth: Int = 0): List<String> {
        if (depth > 10) return emptyList()
        return when (value) {
            is JSONArray -> (0 until value.length()).flatMap { lines(value.opt(it), depth + 1) }
            is JSONObject -> lines(value.opt("itemListElement") ?: value.opt("text") ?: value.opt("value") ?: value.opt("name"), depth + 1)
            is String -> Html.fromHtml(value.replace("\n", "<br>"), Html.FROM_HTML_MODE_COMPACT)
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            is Number -> listOf(value.toString())
            else -> emptyList()
        }
    }

    internal fun extract(html: String): String? {
        for (script in scripts.findAll(html)) {
            if (!jsonLd.containsMatchIn(script.groupValues[1])) continue
            val root = runCatching { JSONTokener(script.groupValues[2].trim()).nextValue() }.getOrNull()
            for (entry in nodes(root)) {
                val types = when (val type = entry.opt("@type")) {
                    is JSONArray -> (0 until type.length()).map { type.optString(it) }
                    is String -> listOf(type)
                    else -> emptyList()
                }
                if (types.none { it.substringAfterLast('/').substringAfterLast(':') == "Recipe" }) continue
                val ingredients = lines(entry.opt("recipeIngredient"))
                val steps = lines(entry.opt("recipeInstructions"))
                if (ingredients.isEmpty() || steps.isEmpty()) continue
                val minutes = runCatching { Duration.parse(entry.optString("totalTime")).toMinutes() }.getOrNull()
                val servings = lines(entry.opt("recipeYield")).firstOrNull()?.let {
                    Regex("""^(\d{1,2})(?:$|\s+(?:porții|servings|persoane)\b)""", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1)
                }
                val title = lines(entry.opt("name")).firstOrNull() ?: "Rețetă de pe web"
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
