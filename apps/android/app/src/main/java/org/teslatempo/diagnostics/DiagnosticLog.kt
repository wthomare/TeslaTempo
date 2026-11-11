package org.teslatempo.diagnostics

import android.app.Application
import android.content.Context
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.Response
import org.teslatempo.BuildConfig
import org.teslatempo.network.RelayCredentials
import org.teslatempo.security.CredentialStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// Fixed vocabulary shared with relay/diagnostics.py. No strings from requests or exceptions.
data class DiagnosticEvent(val id: String, val request_id: String, val report_id: String, val at: Long,
    val source: String, val operation: String, val outcome: String, val code: Int, val duration_ms: Long, val version: String, val site: String = "unknown", val line: Int = 0, val error_kind: String = "none")
data class DiagnosticReport(val report_id: String, val consent: Boolean, val events: List<DiagnosticEvent>)
private data class PendingReport(val destination: String, val report: DiagnosticReport)

object DiagnosticLog {
    private val executor = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64),ThreadPoolExecutor.DiscardPolicy())
    private val gson = Gson()
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    private var context: Context? = null
    private val lock = Any()
    private val operations = setOf("status","vehicles","charge_state","observations","assistant_status","assistant_models",
        "assistant_explain","assistant_disconnect","tesla_connect","tesla_register","tesla_disconnect","oauth_launch","oauth_callback",
        "diagnostics_upload","diagnostics_export","startup","health","app_crash","unknown")
    private val outcomes = setOf("ok","http_error","network_error","invalid_response","crash","started","stopped","unavailable","recovered")
    private val sites=setOf("unknown","RelayGateway.kt","AssistantGateway.kt","MainActivity.kt","ChargingOptimizer.kt","TempoGateway.kt","PersonalViewModel.kt","DiagnosticLog.kt")
    private val kinds=setOf("none","unknown","IllegalStateException","IllegalArgumentException","NullPointerException","IOException")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private fun prefs() = checkNotNull(context).getSharedPreferences("diagnostics_v1", Context.MODE_PRIVATE)
    fun valid(e: DiagnosticEvent): Boolean = runCatching {
        e.site in sites && e.error_kind in kinds && e.line in 0..10000 && e.source == "android" && e.operation in operations && e.outcome in outcomes &&
            listOf(e.id,e.request_id,e.report_id).all { uuid.matches(it) } &&
            e.version.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?:-dev)?")) &&
            e.at in (System.currentTimeMillis()-7*86400_000L)..(System.currentTimeMillis()+120_000) &&
            e.code in 0..599 && e.duration_ms in 0..120_000
    }.getOrDefault(false)
    fun initialize(app: Context) {
        context = app.applicationContext
        record("startup","started")
        executor.execute { runCatching { retryPending(false) } }
    }
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) {
        runCatching {
            val raw=prefs().getString("events","[]")!!
            if(raw.length>262144) emptyList() else gson.fromJson(raw,Array<DiagnosticEvent>::class.java).filter(::valid).takeLast(500)
        }.getOrDefault(emptyList())
    }
    fun record(operation: String, outcome: String, requestId: String = UUID.randomUUID().toString(), code: Int = 0, duration: Long = 0, immediate: Boolean = false, failure: Throwable? = null) {
        val id=UUID.randomUUID().toString()
        val frame=failure?.stackTrace?.firstOrNull { it.className.startsWith("org.teslatempo.") && it.fileName in sites }
        val kind=if(failure==null) "none" else failure.javaClass.simpleName.takeIf { it in kinds } ?: "unknown"
        val row=DiagnosticEvent(id,requestId,id,System.currentTimeMillis(),"android",operation,outcome,code,duration.coerceIn(0,120000),BuildConfig.VERSION_NAME,frame?.fileName ?: "unknown",frame?.lineNumber?.coerceIn(0,10000) ?: 0,kind)
        if(!valid(row) || context==null)return
        val revision=generation.get()
        val write = Runnable { runCatching { synchronized(lock) {
            if (generation.get()!=revision) return@synchronized
            val rows=(snapshot()+row).takeLast(500)
            prefs().edit().putString("events",gson.toJson(rows)).commit()
        } } }
        if(immediate)write.run() else executor.execute(write)
    }
    fun export(): String = gson.toJson(mapOf("schema" to 1,"events" to snapshot()))
    fun clear() = synchronized(lock) { generation.incrementAndGet(); prefs().edit().clear().commit(); Unit }
    fun pending(): Boolean = context!=null && prefs().contains("pending")
    private fun destination(c: RelayCredentials): String = MessageDigest.getInstance("SHA-256")
        .digest((c.url+"\u0000"+c.token).toByteArray()).joinToString("") { "%02x".format(it) }
    fun consent(rows: List<DiagnosticEvent>): String = synchronized(lock) {
        require(rows.isNotEmpty() && rows.size<=100 && rows.all(::valid))
        val credentials=CredentialStore(checkNotNull(context)).load() ?: error("Configurez le relais avant l’envoi.")
        val id=UUID.randomUUID().toString()
        val report=DiagnosticReport(id,true,rows.map { it.copy(id=UUID.randomUUID().toString(),report_id=id) })
        check(prefs().edit().putString("pending",gson.toJson(PendingReport(destination(credentials),report))).commit())
        id
    }
    /** Explicit consent survives a network outage. Retry only on next app opening or button. */
    fun retryPending(force: Boolean): Boolean {
        if(!inFlight.compareAndSet(false,true)) return false
        try {
            val p=prefs();val raw=p.getString("pending",null) ?: return true
            if(!force && System.currentTimeMillis()-p.getLong("last_attempt",0)<60_000)return false
            p.edit().putLong("last_attempt",System.currentTimeMillis()).commit()
            val pending=runCatching {
                gson.fromJson(raw,PendingReport::class.java).also { value ->
                    require(value.report.consent && uuid.matches(value.report.report_id))
                    require(value.report.events.size in 1..100 && value.report.events.all { valid(it) && it.report_id==value.report.report_id })
                }
            }.getOrNull()
            val c=runCatching { CredentialStore(checkNotNull(context)).load() }.getOrNull()
            if(pending==null || c==null || destination(c)!=pending.destination || pending.report.events.any { !valid(it) }) {
                synchronized(lock) { if(p.getString("pending",null)==raw)p.edit().remove("pending").commit() }
                return false
            }
            val ok=runCatching { DiagnosticGateway(c,BuildConfig.DEBUG).send(pending.report) }.getOrDefault(false)
            if(ok)synchronized(lock) { if(p.getString("pending",null)==raw)p.edit().remove("pending").commit() }
            return ok
        } finally { inFlight.set(false) }
    }
    fun operation(path: String): String = when {
        path=="/v1/status" -> "status"
        path=="/v1/vehicles" -> "vehicles"
        path.matches(Regex("/v1/vehicles/[^/]+/charge-state")) -> "charge_state"
        path.matches(Regex("/v1/vehicles/[^/]+/charge-observations")) -> "observations"
        path=="/v1/chatgpt/status" -> "assistant_status"
        path=="/v1/chatgpt/models" -> "assistant_models"
        path=="/v1/chatgpt/explain" -> "assistant_explain"
        path=="/v1/chatgpt/connection" -> "assistant_disconnect"
        else -> "unknown"
    }
}

class DiagnosticInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val id=chain.request().header("X-Request-ID")?.takeIf { runCatching { UUID.fromString(it).version()==4 && it.length==36 }.getOrDefault(false) } ?: UUID.randomUUID().toString()
        val start=System.nanoTime()
        val request=chain.request().newBuilder().header("X-Request-ID",id).build()
        val operation=DiagnosticLog.operation(request.url.encodedPath)
        try {
            val response=chain.proceed(request)
            DiagnosticLog.record(operation,if(response.isSuccessful) "ok" else "http_error",id,response.code,(System.nanoTime()-start)/1_000_000)
            return response
        } catch(e: java.io.IOException) {
            DiagnosticLog.record(operation,"network_error",id,0,(System.nanoTime()-start)/1_000_000)
            throw e
        }
    }
}

class TempoApplication : Application() {
    override fun onCreate() {
        super.onCreate();DiagnosticLog.initialize(this)
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            DiagnosticLog.record("app_crash","crash",immediate=true,failure=error)
            previous?.uncaughtException(thread,error)
        }
    }
}
