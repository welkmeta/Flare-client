package flare.client.app.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object LocalResolver : LocalDNSTransport {
    private const val TAG = "LocalResolver"
    private const val RCODE_NXDOMAIN = 3
    
    private var connectivityManager: ConnectivityManager? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private fun getActiveNetwork(): Network? {
        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)
        
        Log.d(TAG, "getActiveNetwork: active=$active, caps=$caps")
        
        if (caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
            Log.d(TAG, "getActiveNetwork: using active non-VPN network")
            return active
        }
        
        for (network in cm.allNetworks) {
            val netCaps = cm.getNetworkCapabilities(network)
            Log.d(TAG, "getActiveNetwork: checking network=$network, caps=$netCaps")
            if (netCaps != null && netCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                // Return any non-VPN network, even if it's not marked as having INTERNET
                // (Android might remove INTERNET cap when VPN is primary)
                if (netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    Log.d(TAG, "getActiveNetwork: found underlying network=$network")
                    return network
                }
            }
        }
        Log.w(TAG, "getActiveNetwork: no underlying non-VPN network found, falling back to active ($active)")
        return active
    }

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = getActiveNetwork()
        if (network == null) {
            ctx.errnoCode(114514) // EREMOTERELEASE or similar fallback
            return
        }

        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel() }

        val executor = Executor { runBlocking { withContext(Dispatchers.IO) { it.run() } } }

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                if (rcode == 0) {
                    ctx.rawSuccess(answer)
                } else {
                    ctx.errorCode(rcode)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) {
                    ctx.errnoCode(cause.errno)
                } else {
                    Log.w(TAG, "DnsResolver.exchange error", error)
                    ctx.errnoCode(114514)
                }
            }
        }

        try {
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                executor,
                signal,
                callback
            )
        } catch (e: Exception) {
            Log.e(TAG, "rawQuery failed", e)
            ctx.errnoCode(114514)
        }
    }

    override fun lookup(ctx: ExchangeContext, networkStr: String, domain: String) {
        val network = getActiveNetwork()
        if (network == null) {
            fallbackLookup(ctx, domain)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            ctx.onCancel { signal.cancel() }

            val executor = Executor { runBlocking { withContext(Dispatchers.IO) { it.run() } } }

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (rcode == 0) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errorCode(rcode)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        Log.w(TAG, "DnsResolver.lookup error", error)
                        ctx.errnoCode(114514)
                    }
                }
            }

            val type = when {
                networkStr.contains("4") -> DnsResolver.TYPE_A
                networkStr.contains("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }

            try {
                if (type != null) {
                    DnsResolver.getInstance().query(network, domain, type, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
                } else {
                    DnsResolver.getInstance().query(network, domain, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "query failed", e)
                fallbackLookup(ctx, domain)
            }
        } else {
            fallbackLookup(ctx, domain, network)
        }
    }

    private fun fallbackLookup(ctx: ExchangeContext, domain: String, network: Network? = null) {
        scope.launch {
            try {
                val addresses = network?.getAllByName(domain) ?: InetAddress.getAllByName(domain)
                ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
            } catch (e: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
            } catch (e: Exception) {
                Log.e(TAG, "fallbackLookup failed", e)
                ctx.errnoCode(114514)
            }
        }
    }
}
