package ro.camara.app.data

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionTest {
    private val key = "test-" + "a".repeat(40)

    @Test fun legacyLanAddressCanBeClearedOrChangedWithoutChangingStorageIdentity() {
        val current = ConnectionConfig("https://camara-m4tteq.duckdns.org/", key).validated()
        assertEquals("192.168.1.134", current.lanAddress)
        assertEquals("fd00::1234", current.copy(lanAddress = "[fd00::1234]").validated().lanAddress)
        assertNull(current.copy(lanAddress = null).validated().lanAddress)
        assertEquals(current.storageIdentity, current.copy(lanAddress = "192.168.1.5").validated().storageIdentity)
        for (address in listOf("8.8.8.8", "lan.example", "127.0.0.1", "192.168.001.4", "192.168.1.4:443")) {
            assertThrows(IllegalArgumentException::class.java) { current.copy(lanAddress = address).validated() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig("http://192.168.1.5", key, lanAddress = "192.168.1.5").validated()
        }
        assertEquals("http://192.168.1.5:8088", ConnectionConfig("http://192.168.1.5:8088", key).validated().baseUrl)
    }

    @Test fun losingTheResponseDoesNotReplayWritesOnTheOtherRoute() {
        assertTrue(mayTryOtherRoute("PUT", headersStarted = false))
        assertTrue(mayTryOtherRoute("GET", headersStarted = true))
        for (method in listOf("PUT", "POST", "DELETE")) assertFalse(mayTryOtherRoute(method, headersStarted = true))
    }

    @Test fun statusProbeSupportsThePreviousApiAndReusesAnHttpConnection() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val worker = Executors.newSingleThreadExecutor()
        val requests = worker.submit<List<String>> {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                List(5) {
                    val path = checkNotNull(input.readLine()).split(' ')[1]
                    val headers = generateSequence(input::readLine).takeWhile { it.isNotEmpty() }.toList()
                    val (status, body) = when {
                        headers.none { it.substringBefore(':').equals("Authorization", true) && it.substringAfter(": ") == "Bearer $key" } ->
                            "401 Unauthorized" to ""
                        path == "/v1/status" -> "404 Not Found" to ""
                        else -> "200 OK" to """{"status":"ok","version":"0.6.1"}"""
                    }
                    output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
                    output.flush()
                    path
                }
            }
        }
        try {
            val config = ConnectionConfig("http://127.0.0.1:${server.localPort}", key).validated()
            repeat(2) { assertEquals("0.6.1", CamaraApi(config).testConnection().status.version) }
            val result = runCatching { CamaraApi(config.copy(token = "x".repeat(40))).testConnection() }
            assertEquals(401, (result.exceptionOrNull() as ApiException).code)
            assertEquals(listOf("/v1/status", "/health", "/v1/status", "/health", "/v1/status"),
                requests.get(5, TimeUnit.SECONDS))
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }

    @Test fun lostWriteResponseCanBeRetriedWithTheSameActionAfterNetworkReturns() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val worker = Executors.newSingleThreadExecutor()
        val requests = worker.submit<List<Pair<String, String>>> {
            List(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val request = checkNotNull(input.readLine())
                    val headers = generateSequence(input::readLine).takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.single { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(body, read, length - read)
                        check(count > 0)
                        read += count
                    }
                    if (attempt == 1) {
                        val response = """{"replayed":true}"""
                        val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\n\r\n$response")
                        output.flush()
                    }
                    request to String(body)
                }
            }
        }
        try {
            CamaraApi.networkChanged(101L)
            val wifi = CamaraApi.routeGeneration
            val config = ConnectionConfig("http://127.0.0.1:${server.localPort}", key).validated()
            val action = ActionRules.action("Salt", listOf(Change("pantry", "salt", after = ActionRules.record(PantryItem("salt", "Salt", 1.0)))))
            assertTrue(runCatching { CamaraApi(config).applyAction(action.id, action.mutation) }.exceptionOrNull() is IOException)
            CamaraApi.networkChanged(null)
            CamaraApi.networkChanged(102L)
            assertEquals(wifi + 2, CamaraApi.routeGeneration)
            CamaraApi.networkChanged(102L)
            assertEquals(wifi + 2, CamaraApi.routeGeneration)
            assertTrue(CamaraApi(config).applyAction(action.id, action.mutation).replayed)
            val recorded = requests.get(5, TimeUnit.SECONDS)
            assertEquals("PUT /v1/actions/${action.id} HTTP/1.1", recorded.first().first)
            assertEquals(recorded.first(), recorded.last())
        } finally {
            CamaraApi.networkChanged(null)
            server.close()
            worker.shutdownNow()
        }
    }
}
