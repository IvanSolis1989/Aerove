package dev.aerove.usage

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** One implementation, separate app-private non-backup namespace in each product. */
object LocalUsageLedgerStore {
    private val authority = LocalUsageLedgerAuthority()
    private val owners = mutableMapOf<String, LocalUsageLedgerFile>()
    fun bind(context: Context): LocalUsageLedgerBinding =
        LocalUsageLedgerBinding(context.applicationContext as Application, authority.attachEngine())

    internal fun handle(context: Context, arguments: Any?, activation: LocalUsageLedgerAuthority.Activation?): Map<String, Any?> {
        if (!authority.isCurrent(activation)) return mapOf("status" to "retired")
        val parent = context.noBackupFilesDir.canonicalFile
        val directory = File(parent, "aerove-local-usage")
        require(directory.canonicalFile == directory.absoluteFile && directory.parentFile == parent)
        val owner = synchronized(owners) {
            owners.getOrPut(directory.path) { LocalUsageLedgerFile(directory, authority) }
        }
        return owner.handle(arguments, activation)
    }
}

/** Only a real bound Activity resume/focus can choose a writer, never a claim request. */
class LocalUsageLedgerAuthority {
    internal val gate = Any()
    private var current: Activation? = null
    class Activation internal constructor(internal val engine: Engine) {
        val id: String = UUID.randomUUID().toString()
    }
    class Engine internal constructor(private val owner: LocalUsageLedgerAuthority) {
        internal var attached = true
        internal var last: Activation? = null
        fun resumed() = owner.resume(this)
        fun detach() = owner.detach(this)
        fun capture(): Activation? = synchronized(owner.gate) { if (attached) last else null }
        fun isAttached(): Boolean = synchronized(owner.gate) { attached }
    }
    fun attachEngine(): Engine = Engine(this)
    private fun resume(engine: Engine) = synchronized(gate) {
        if (engine.attached && current?.engine !== engine) {
            current = Activation(engine).also { engine.last = it }
        }
    }
    private fun detach(engine: Engine) = synchronized(gate) {
        engine.attached = false
        if (current?.engine === engine) current = null
    }
    internal fun currentLocked(value: Activation?): Boolean =
        value != null && current === value && value.engine.attached
    internal fun isCurrent(value: Activation?): Boolean = synchronized(gate) { currentLocked(value) }
}

/** Each engine attachment gets a new binding; old callbacks retain the closed old instance. */
class LocalUsageLedgerBinding internal constructor(
    private val application: Application, private val engine: LocalUsageLedgerAuthority.Engine,
) : AutoCloseable {
    private var activity: Activity? = null
    private var focusView: View? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var closed = false
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (!closed && activity === value && value.hasWindowFocus()) engine.resumed()
        }
        override fun onActivityCreated(value: Activity, state: Bundle?) {}
        override fun onActivityStarted(value: Activity) {}
        override fun onActivityPaused(value: Activity) {}
        override fun onActivityStopped(value: Activity) {}
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) {}
        override fun onActivityDestroyed(value: Activity) {}
    }
    init { application.registerActivityLifecycleCallbacks(lifecycle) }
    fun attachActivity(value: Activity) {
        check(!closed); detachActivity(); activity = value
        val view = value.window.decorView
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && !closed && activity === value && value.hasWindowFocus()) engine.resumed()
        }
        focusView = view; focusListener = listener
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        if (value.hasWindowFocus()) engine.resumed()
    }
    fun detachActivity() {
        focusListener?.let { listener ->
            focusView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
        }
        focusView = null; focusListener = null; activity = null
    }
    fun capture(): LocalUsageLedgerAuthority.Activation? = engine.capture()
    fun handle(arguments: Any?, captured: LocalUsageLedgerAuthority.Activation?): Map<String, Any?> {
        if (captured == null) return mapOf("activationVersion" to 2, "status" to if (engine.isAttached()) "inactive" else "retired")
        return LocalUsageLedgerStore.handle(application, arguments, captured)
    }
    override fun close() {
        if (closed) return
        closed = true; engine.detach(); detachActivity()
        application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}

/** Caller owns this fixed namespace; the mutex also fences detached-engine work. */
class LocalUsageLedgerFile(private val directory: File, private val authority: LocalUsageLedgerAuthority) {
    private val file = File(directory, "record.json")
    private val atomic = AtomicFile(file)
    private var lease: String? = null
    private var leaseActivation: LocalUsageLedgerAuthority.Activation? = null
    private data class Record(val revision: String, val payload: String)

    @Synchronized
    fun handle(arguments: Any?, activation: LocalUsageLedgerAuthority.Activation?): Map<String, Any?> {
        if (!authority.isCurrent(activation)) return mapOf("status" to "retired")
        val args = arguments as? Map<*, *> ?: error("Missing local usage operation")
        require(args["activationVersion"] == 2)
        val operation = args["operation"] as? String ?: error("Missing local usage operation")
        if (operation == "claim") {
            require(args.keys == setOf("operation", "activationVersion"))
            val current = readConfirmed()
            return synchronized(authority.gate) {
                if (!authority.currentLocked(activation)) return@synchronized mapOf("status" to "retired")
                if (leaseActivation !== activation) {
                    lease = UUID.randomUUID().toString(); leaseActivation = activation
                }
                view("confirmed", current)
            }
        }
        if (args["lease"] != lease || lease == null || leaseActivation !== activation) return mapOf("status" to "retired")
        if (operation == "read") {
            require(args.keys == setOf("operation", "lease", "activationVersion"))
            val current = readConfirmed()
            return if (authority.isCurrent(activation)) view("confirmed", current) else mapOf("status" to "retired")
        }
        require(operation == "write" && args.keys == setOf("operation", "lease", "expectedRevision", "payload", "activationVersion"))
        val payload = args["payload"] as? String ?: error("Missing local usage payload")
        require(payload.length <= MAX_BYTES)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES); JSONObject(payload)
        val current = readConfirmed()
        if (!authority.isCurrent(activation)) return mapOf("status" to "retired")
        if (args["expectedRevision"] != current?.revision) return view("conflict", current)
        val candidate = Record(UUID.randomUUID().toString(), payload)
        val encoded = JSONObject().put("schemaVersion", 1).put("revision", candidate.revision)
            .put("payload", payload).put("sha256", digest(bytes)).toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES)
        var output: FileOutputStream? = atomic.startWrite()
        try {
            val stream = checkNotNull(output)
            stream.write(encoded)
            stream.fd.sync()
            atomic.finishWrite(stream)
            output = null
            // AtomicFile logs some finish failures rather than throwing. Verify and sync the result explicitly.
            val actual = readConfirmed()
            check(actual == candidate) { "Local usage commit has no matching durable readback" }
            return view("committed", actual)
        } catch (error: Throwable) {
            output?.let { stream ->
                try { atomic.failWrite(stream) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            // Once finishWrite may have renamed, do not roll a possible commit back on a lost ack.
            throw error
        }
    }

    private fun view(status: String, record: Record?): Map<String, Any?> =
        mapOf("activationVersion" to 2, "status" to status, "ack" to (status == "confirmed" || status == "committed"), "activation" to leaseActivation?.id,
            "lease" to lease, "revision" to record?.revision, "payload" to record?.payload)

    private fun readConfirmed(): Record? {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create local usage namespace" }
        require(directory.canonicalFile == directory.absoluteFile)
        for (name in listOf("record.json", "record.json.new", "record.json.bak")) {
            val target = File(directory, name)
            require(target.canonicalFile == target.absoluteFile) { "Linked local usage file" }
        }
        val bytes = try {
            atomic.openRead().use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= MAX_BYTES) { "Local usage file exceeds its budget" }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        } catch (missing: java.io.FileNotFoundException) {
            if (!absent(file) || !absent(File(directory, "record.json.bak"))) throw missing
            syncDirectory(directory); syncDirectory(directory.parentFile!!)
            return null
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val row = JSONObject(text)
        require(row.keys().asSequence().toSet() == setOf("schemaVersion", "revision", "payload", "sha256") && row.get("schemaVersion") == 1)
        require(row.get("revision") is String && row.get("payload") is String && row.get("sha256") is String)
        val revision = row.getString("revision")
        require(UUID.fromString(revision).toString() == revision)
        val payload = row.getString("payload")
        require(digest(payload.toByteArray(Charsets.UTF_8)) == row.getString("sha256")) { "Local usage file checksum differs" }
        JSONObject(payload)
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        syncDirectory(directory); syncDirectory(directory.parentFile!!)
        return Record(revision, payload)
    }

    private fun syncDirectory(path: File) {
        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private fun absent(target: File): Boolean = try {
        Os.lstat(target.path); false
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) true else throw error
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
