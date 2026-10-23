package dev.aerove.lite.regexp2

import android.app.Service
import android.content.Intent
import android.os.*
import android.system.OsConstants
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Text-only worker. The manifest must mark this service isolatedProcess and exported=false. */
class Regexp2Service : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val active = AtomicLong(0)
    private var owner: IBinder? = null
    private val ownerDeath = IBinder.DeathRecipient { terminate() }
    private val watchdog = Runnable { terminate() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            FilterProtocol.BATCH, FilterProtocol.AGE -> submit(message)
            FilterProtocol.CANCEL -> {
                val id = message.data.getLong("id")
                // Batch initialization happens before the VM is entered; if not loaded yet, hard shutdown still works.
                if (id == active.get() && id > 0) Thread({ runCatching { FilterNative.cancel(id) } }, "filter-cancel").start()
            }
            FilterProtocol.SHUTDOWN -> terminate()
        }
        true
    })
    override fun onBind(intent: Intent): IBinder {
        check(Process.isIsolated()) { "Client helper must run isolated" }
        // A bind without a request cannot leave an idle isolated runtime behind.
        main.postDelayed(watchdog, FilterProtocol.DEADLINE_MS + FilterProtocol.GRACE_MS)
        return messenger.binder
    }
    private fun submit(message: Message) {
        val reply = message.replyTo ?: return terminate()
        val data = message.data
        val id = data.getLong("id")
        if (data.getInt("version") != FilterProtocol.VERSION || id <= 0 || !active.compareAndSet(0, id)) {
            respond(reply, id, if (message.what == FilterProtocol.AGE) byteArrayOf(1) + "busy".toByteArray() else error(id, "busy", "Filter worker is unavailable"), message.what == FilterProtocol.AGE); return
        }
        if (owner == null) {
            owner = reply.binder
            try { reply.binder.linkToDeath(ownerDeath, 0) } catch (_: RemoteException) { return terminate() }
        } else if (owner != reply.binder) { return terminate() }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, FilterProtocol.DEADLINE_MS + FilterProtocol.GRACE_MS)
        worker.execute {
            val result = try {
                val length = data.getInt("length")
                if (message.what == FilterProtocol.AGE) {
                    require(length in 1..(8 * 1024 * 1024))
                    val identities = requireNotNull(data.getByteArray("identities"))
                    try {
                        require(identities.size <= 64 * 1024)
                        val pipe = requireNotNull(data.getParcelable("pipe", ParcelFileDescriptor::class.java))
                        val input = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { stream ->
                            ByteArray(length).also { java.io.DataInputStream(stream).readFully(it); require(stream.read() == -1) }
                        }
                        try { FilterNative.decrypt(input, identities, id) } finally { input.fill(0) }
                    } finally { identities.fill(0); data.remove("identities") }
                } else {
                    require(length in 1..FilterProtocol.MAX_BYTES)
                    val memory = data.getParcelable("memory", SharedMemory::class.java)
                    val input = if (memory == null) requireNotNull(data.getByteArray("bytes")).also { require(it.size == length) }
                    else memory.use {
                        require(it.size == length)
                        val mapped = it.mapReadOnly()
                        try { ByteArray(length).also(mapped::get) } finally { SharedMemory.unmap(mapped) }
                    }
                    FilterNative.batch(input)
                }
            } catch (failure: Exception) {
                if (message.what == FilterProtocol.AGE) byteArrayOf(1) + "transport_error".toByteArray()
                else error(id, "failed", failure.message ?: failure.javaClass.simpleName)
            } catch (failure: LinkageError) {
                if (message.what == FilterProtocol.AGE) byteArrayOf(1) + "engine_unavailable".toByteArray()
                else error(id, "failed", "Exact filter engine could not be loaded")
            }
            main.post {
                active.compareAndSet(id, 0)
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, FilterProtocol.DEADLINE_MS + FilterProtocol.GRACE_MS)
                respond(reply, id, result, message.what == FilterProtocol.AGE)
            }
        }
    }
    private fun respond(reply: Messenger, id: Long, bytes: ByteArray, age: Boolean = false) {
        try {
            require(bytes.size <= FilterProtocol.MAX_BYTES + if (age) 256 else 0)
            val bundle = Bundle().apply { putLong("id", id); putInt("version", FilterProtocol.VERSION); putInt("length", bytes.size) }
            if (age) {
                val pipes = ParcelFileDescriptor.createPipe()
                try {
                    bundle.putParcelable("pipe", pipes[0])
                    reply.send(Message.obtain(null, FilterProtocol.AGE_RESULT).apply { data = bundle })
                    worker.execute {
                        try { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) } }
                        catch (_: Exception) { terminate() }
                        finally { bytes.fill(0) }
                    }
                } finally { pipes[0].close() }
            } else if (bytes.size <= FilterProtocol.INLINE_BYTES) {
                bundle.putByteArray("bytes", bytes)
                reply.send(Message.obtain(null, FilterProtocol.RESULT).apply { data = bundle })
            } else SharedMemory.create("aerove-filter-result", bytes.size).use { memory ->
                val mapped = memory.mapReadWrite()
                try { mapped.put(bytes) } finally { SharedMemory.unmap(mapped) }
                check(memory.setProtect(OsConstants.PROT_READ))
                bundle.putParcelable("memory", memory)
                reply.send(Message.obtain(null, FilterProtocol.RESULT).apply { data = bundle })
            }
        } catch (_: Exception) { terminate() }
    }
    private fun error(id: Long, status: String, message: String) = JSONObject().put("id", id).put("status", status)
        .put("engine", "regexp2-v1.12.0+aerove-cancel1/None")
        .put("error", JSONObject().put("path", "$").put("kind", status).put("message", message)).toString().toByteArray(Charsets.UTF_8)
    private fun terminate() { Process.killProcess(Process.myPid()) }
    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }
    override fun onDestroy() { worker.shutdownNow(); owner?.let { runCatching { it.unlinkToDeath(ownerDeath, 0) } }; super.onDestroy(); terminate() }
}
