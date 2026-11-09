package com.lidseeker.app.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lidseeker.app.data.Repository
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.*
import retrofit2.HttpException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

enum class ConnectionIssue { DNS, TIMEOUT, TLS, AUTH, ADDRESS, SERVER, UNKNOWN }
class ConnectionProblem(val issue: ConnectionIssue) : IllegalStateException(ConnectionDiagnostics.explain(issue))
data class ConnectionCheck(val name: String,val ok: Boolean,val detail: String)

object ConnectionDiagnostics {
    fun classify(error: Throwable): ConnectionIssue = when(error) {
        is ConnectionProblem -> error.issue
        is UnknownHostException -> ConnectionIssue.DNS
        is SocketTimeoutException,is ConnectException,is TimeoutCancellationException -> ConnectionIssue.TIMEOUT
        is SSLException -> ConnectionIssue.TLS
        is HttpException -> if(error.code()==401 || error.code()==403) ConnectionIssue.AUTH else ConnectionIssue.SERVER
        is IllegalArgumentException -> ConnectionIssue.ADDRESS
        else -> ConnectionIssue.UNKNOWN
    }
    fun explain(issue: ConnectionIssue): String = when(issue) {
        ConnectionIssue.DNS -> "The server name could not be resolved. Check the spelling and Tailscale DNS. If your phone is connected, the server may need to sign back into Tailscale."
        ConnectionIssue.TIMEOUT -> "The server did not respond. Check Tailscale on both devices, then try the address in your phone browser. The server, port or access rule may be unavailable."
        ConnectionIssue.TLS -> "The HTTPS certificate could not be verified. Check the server address and your phone’s date/time. Do not bypass certificate warnings."
        ConnectionIssue.AUTH -> "The server is reachable, but your sign-in was rejected or expired. Reconnect with the correct account details."
        ConnectionIssue.ADDRESS -> "Check the server’s HTTPS address, including the port. Enter the address without embedded credentials or query parameters."
        ConnectionIssue.SERVER -> "The server responded with an error. Its service or HTTPS forwarding may need attention."
        ConnectionIssue.UNKNOWN -> "The connection check failed. Try the server in your phone browser, then check Tailscale and your sign-in."
    }
    suspend fun check(context: Context, nav: Navidrome, repo: Repository, requestSignedIn: Boolean): List<ConnectionCheck> = supervisorScope {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.getNetworkCapabilities(manager.activeNetwork)
        val vpn=network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
        val local=ConnectionCheck("Phone connection",network!=null,if(network==null) "No active network detected. Connect Wi-Fi or mobile data." else if(vpn) "A VPN is active. Android cannot confirm whether it is Tailscale or whether the server is signed in." else "Network available; no VPN detected for Music. Private .ts.net servers normally need Tailscale. If it already says Connected, check that Music is not excluded from its VPN routing.")
        val library=async {
            if(nav.connection==null) ConnectionCheck("Navidrome",false,"Not configured. Open Connections to sign in.")
            else try { nav.ping();ConnectionCheck("Navidrome",true,"Server reachable and saved sign-in accepted.") }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { ConnectionCheck("Navidrome",false,explain(classify(e))) }
        }
        val requests=async {
            val server=repo.currentServerUrl()
            if(server.isBlank()) ConnectionCheck("Requests",false,"Not configured. Open Connections to set the Lidseeker address.")
            else try {
                withContext(Dispatchers.IO) {
                    val base=server.toHttpUrlOrNull() ?: throw ConnectionProblem(ConnectionIssue.ADDRESS)
                    if(!base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty()) throw ConnectionProblem(ConnectionIssue.ADDRESS)
                    val client=OkHttpClient.Builder().callTimeout(15,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                    client.newCall(Request.Builder().url(base.newBuilder().addPathSegments("api/health").build()).build()).execute().use { response ->
                        if(!response.isSuccessful) throw ConnectionProblem(ConnectionIssue.SERVER)
                        val json=runCatching { Json.parseToJsonElement(response.body.string()).jsonObject }.getOrNull()
                        if(json?.get("status")?.jsonPrimitive?.contentOrNull!="ok" || json?.get("db")?.jsonPrimitive?.booleanOrNull!=true) throw ConnectionProblem(ConnectionIssue.ADDRESS)
                    }
                }
                if(requestSignedIn) repo.services()
                ConnectionCheck("Requests",true,if(requestSignedIn) "Service reachable and saved sign-in accepted." else "Service reachable. Sign in through Connections to submit requests.")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { ConnectionCheck("Requests",false,explain(classify(e))) }
        }
        listOf(local,library.await(),requests.await())
    }
}
