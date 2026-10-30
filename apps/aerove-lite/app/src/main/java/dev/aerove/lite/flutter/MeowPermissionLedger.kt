package dev.aerove.lite.flutter

class MeowPermissionCodeExhausted(val firstCode: Int, val lastCode: Int) :
    IllegalStateException("System permission request-code range is exhausted: $firstCode..$lastCode")

class MeowPermissionLedgerFull(val capacity: Int) :
    IllegalStateException("System permission ledger capacity is exhausted: $capacity")

class MeowPermissionEntry<out T> internal constructor(val requestCode: Int, val value: T)

data class MeowPermissionResolution<out T>(val entry: MeowPermissionEntry<T>, val cancelled: Boolean)

internal class MeowPermissionRequestCodes(private val first: Int = 0x6000, private val last: Int = 0x7ffe) {
    private var next = first
    init { require(first in 0..0xffff && last in first..0xffff) }

    @Synchronized fun allocate(): Int {
        if (next > last) throw MeowPermissionCodeExhausted(first, last)
        return next++
    }
}

class MeowPermissionLedger<T> internal constructor(private val codes: MeowPermissionRequestCodes,
    private val capacity: Int) {
    constructor(capacity: Int = 64) : this(processCodes, capacity)

    private data class Pending<T>(val entry: MeowPermissionEntry<T>, var cancelled: Boolean = false)
    private val entries = linkedMapOf<Int, Pending<T>>()
    init { require(capacity > 0) }

    @Synchronized fun issue(value: T): MeowPermissionEntry<T> {
        if (entries.size >= capacity) throw MeowPermissionLedgerFull(capacity)
        return MeowPermissionEntry(codes.allocate(), value).also { entries[it.requestCode] = Pending(it) }
    }

    @Synchronized fun cancel(entry: MeowPermissionEntry<T>): Boolean {
        val pending = entries[entry.requestCode]?.takeIf { it.entry === entry && !it.cancelled } ?: return false
        pending.cancelled = true
        return true
    }

    @Synchronized fun cancelAll(): List<MeowPermissionEntry<T>> = entries.values.filter { !it.cancelled }
        .map { it.cancelled = true; it.entry }

    @Synchronized fun activeEntries(): List<MeowPermissionEntry<T>> = entries.values.filter { !it.cancelled }.map { it.entry }

    @Synchronized fun resolve(requestCode: Int): MeowPermissionResolution<T>? = entries.remove(requestCode)?.let {
        MeowPermissionResolution(it.entry, it.cancelled)
    }

    private companion object {
        val processCodes = MeowPermissionRequestCodes()
    }
}
