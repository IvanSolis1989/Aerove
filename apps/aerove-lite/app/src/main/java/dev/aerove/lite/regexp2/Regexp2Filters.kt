package dev.aerove.lite.regexp2

import android.content.*
import android.os.*
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One short-lived isolated lease per operation. install only records Context; it loads no native library. */
object IsolatedClientHelper {
    @Volatile private var context: Context? = null
    @Volatile private var active: Lease? = null
    private val mutex = Mutex()
    private val ids = AtomicLong(0)
    fun install(context: Context) {
        this.context = context.applicationContext
        ExactFilterRuntime.install(ExactFilterExecutor { include, exclude, names, validate -> indices(include, exclude, names, validate) })
    }
    fun cancelActive() { active?.close() }

    suspend fun decryptAge(ciphertext: ByteArray, identities: ByteArray): ByteArray = mutex.withLock {
        require(ciphertext.size <= 8 * 1024 * 1024 && identities.size <= 64 * 1024)
        active?.awaitStopped(); active = null
        val lease = Lease(requireNotNull(context) { "Client helper is not initialized" })
        active = lease
        try {
            withTimeout(FilterProtocol.DEADLINE_MS + FilterProtocol.GRACE_MS + 2000) {
                lease.connect()
                val result = lease.decrypt(ids.incrementAndGet(), ciphertext, identities)
                check(result.isNotEmpty()) { "Invalid age helper response" }
                check(result[0] == 0.toByte()) { "age: " + result.copyOfRange(1, result.size).toString(Charsets.UTF_8) }
                result.copyOfRange(1, result.size)
            }
        } finally {
            lease.close(); lease.awaitStopped()
            if (active === lease) active = null
        }
    }

    suspend fun indices(include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean = false): List<Int> =
        mutex.withLock {
            active?.awaitStopped()
            active = null
            val lease = Lease(requireNotNull(context) { "Exact filter service is not initialized" })
            active = lease
            try {
                withTimeout(FilterProtocol.DEADLINE_MS + FilterProtocol.GRACE_MS + 2000) {
                    lease.connect()
                    suspend fun batch(parts: List<String>, candidates: List<String>, validate: Boolean): List<Int> {
                        val id = ids.incrementAndGet()
                        val request = FilterProtocol.request(id, parts, exclude, candidates, validate)
                        return FilterProtocol.indices(id, lease.batch(id, request), candidates.size)
                    }
                    if (validateOnly) return@withTimeout batch(include, emptyList(), true)
                    val chunks = FilterChunks.names(names)
                    if (chunks.size == 1) return@withTimeout batch(include, names, false)
                    // Validate the whole expression before any matching; do not publish partial chunks.
                    batch(include, emptyList(), true)
                    val parts = if (include.isEmpty()) listOf<List<String>>(emptyList())
                        else include.flatMap { it.split('`') }.map { listOf(it) }
                    val seen = mutableSetOf<String>()
                    val output = mutableListOf<Int>()
                    for (part in parts) for (range in chunks) {
                        val candidates = names.subList(range.first, range.last + 1)
                        for (index in batch(part, candidates, false)) {
                            val original = range.first + index
                            if (include.isEmpty() || seen.add(names[original])) output += original
                        }
                    }
                    output
                }
            } finally {
                lease.close()
                lease.awaitStopped()
                if (active === lease) active = null
            }
        }

    private class Lease(private val context: Context) : ServiceConnection, IBinder.DeathRecipient {
        private val stopped = CompletableDeferred<Unit>()
        private var bound = false
        private var closed = false
        private var service: Messenger? = null
        private var connecting: CancellableContinuation<Unit>? = null
        private var pending: CancellableContinuation<ByteArray>? = null
        private var requestId = 0L
        private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == FilterProtocol.RESULT || message.what == FilterProtocol.AGE_RESULT) {
                val response = message.data
                CoroutineScope(Dispatchers.IO).launch { try {
                    val data = response
                    check(data.getInt("version") == FilterProtocol.VERSION && data.getLong("id") == requestId) { "Filter response identity mismatch" }
                    val length = data.getInt("length")
                    require(length in 1..(FilterProtocol.MAX_BYTES + 256))
                    val pipe = data.getParcelable("pipe", ParcelFileDescriptor::class.java)
                    val memory = data.getParcelable("memory", SharedMemory::class.java)
                    val bytes = if (pipe != null) ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                        ByteArray(length).also { bytes -> java.io.DataInputStream(input).readFully(bytes); check(input.read() == -1) }
                    } else if (memory == null) requireNotNull(data.getByteArray("bytes")).also { require(it.size == length) }
                    else memory.use {
                        require(it.size == length)
                        val mapped = it.mapReadOnly()
                        try { ByteArray(length).also(mapped::get) } finally { SharedMemory.unmap(mapped) }
                    }
                    synchronized(this) { pending?.let { pending = null; it.resume(bytes) } }
                } catch (failure: Exception) { fail(failure) } }
            }
            true
        })
        suspend fun connect() = suspendCancellableCoroutine<Unit> { continuation ->
            synchronized(this) {
                check(!closed)
                connecting = continuation
                continuation.invokeOnCancellation { close() }
                if (closed) return@synchronized
                try {
                    bound = context.bindService(Intent(context, Regexp2Service::class.java), this, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
                    if (!bound) fail(IllegalStateException("Cannot bind exact filter service"))
                } catch (failure: Exception) { fail(failure) }
            }
        }
        suspend fun batch(id: Long, bytes: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
            synchronized(this) {
                if (closed) { continuation.resumeWithException(CancellationException("Filter lease closed")); return@suspendCancellableCoroutine }
                check(pending == null)
                requestId = id; pending = continuation
                continuation.invokeOnCancellation { synchronized(this) { send(FilterProtocol.CANCEL, Bundle().apply { putLong("id", id) }) }; close() }
                try {
                    val data = Bundle().apply { putLong("id", id); putInt("version", FilterProtocol.VERSION); putInt("length", bytes.size) }
                    if (bytes.size <= FilterProtocol.INLINE_BYTES) {
                        data.putByteArray("bytes", bytes); send(FilterProtocol.BATCH, data, throwing = true)
                    } else SharedMemory.create("aerove-filter-request", bytes.size).use { memory ->
                        val mapped = memory.mapReadWrite()
                        try { mapped.put(bytes) } finally { SharedMemory.unmap(mapped) }
                        check(memory.setProtect(OsConstants.PROT_READ))
                        data.putParcelable("memory", memory); send(FilterProtocol.BATCH, data, throwing = true)
                    }
                } catch (failure: Exception) { fail(failure) }
            }
        }
        suspend fun decrypt(id: Long, ciphertext: ByteArray, identities: ByteArray): ByteArray = coroutineScope {
            val pipes = ParcelFileDescriptor.createPipe()
            val writer = launch(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(ciphertext) }
            }
            try {
                suspendCancellableCoroutine { continuation -> synchronized(this@Lease) {
                    if (closed) { continuation.cancel(); return@suspendCancellableCoroutine }
                    requestId = id; pending = continuation
                    continuation.invokeOnCancellation { close() }
                    try {
                        val data = Bundle().apply {
                            putLong("id", id); putInt("version", FilterProtocol.VERSION)
                            putInt("length", ciphertext.size); putParcelable("pipe", pipes[0])
                            putByteArray("identities", identities)
                        }
                        send(FilterProtocol.AGE, data, throwing = true)
                    } catch (failure: Exception) { fail(failure) }
                } }
            } finally {
                pipes.forEach { runCatching { it.close() } }
                writer.cancel()
            }
        }
        @Synchronized override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                binder.linkToDeath(this, 0); service = Messenger(binder)
                if (closed) { stopBoundService(); return }
                connecting?.let { connecting = null; it.resume(Unit) }
            }
            catch (failure: Exception) { fail(failure) }
        }
        override fun onServiceDisconnected(name: ComponentName) { fail(IllegalStateException("Exact filter process exited")) }
        override fun onNullBinding(name: ComponentName) { stopped.complete(Unit); fail(IllegalStateException("Exact filter service refused binding")) }
        override fun onBindingDied(name: ComponentName) { stopped.complete(Unit); fail(IllegalStateException("Exact filter binding died")) }
        override fun binderDied() { stopped.complete(Unit); fail(IllegalStateException("Exact filter process died")) }
        @Synchronized private fun send(what: Int, data: Bundle = Bundle(), throwing: Boolean = false) {
            try { (service ?: error("Exact filter service disconnected")).send(Message.obtain(null, what).apply { this.data = data; replyTo = replies }) }
            catch (failure: Exception) { if (throwing) throw failure }
        }
        @Synchronized private fun fail(failure: Exception) {
            connecting?.let { connecting = null; it.resumeWithException(failure) }
            pending?.let { pending = null; it.resumeWithException(failure) }
            close()
        }
        @Synchronized fun close() {
            if (closed) return
            closed = true
            if (service != null) stopBoundService()
            else if (!bound) stopped.complete(Unit)
            connecting?.let { connecting = null; it.cancel(CancellationException("Filter lease closed")) }
            pending?.let { pending = null; it.cancel(CancellationException("Filter lease closed")) }
        }
        @Synchronized private fun stopBoundService() {
            send(FilterProtocol.SHUTDOWN)
            // Keep the DeathRecipient until actual exit. A queued shutdown is not confirmed termination.
            if (bound) { bound = false; runCatching { context.unbindService(this) } }
        }
        suspend fun awaitStopped() = withContext(NonCancellable) {
            check(withTimeoutOrNull(2000) { stopped.await(); true } == true) { "Client helper exit could not be confirmed" }
        }
    }
}

/** Main application installation remains filter-compatible; native loading occurs only in the service. */
object Regexp2Filters {
    fun install(context: Context) = IsolatedClientHelper.install(context)
    fun cancelActive() = IsolatedClientHelper.cancelActive()
    suspend fun indices(include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean = false): List<Int> =
        IsolatedClientHelper.indices(include, exclude, names, validateOnly)
}
