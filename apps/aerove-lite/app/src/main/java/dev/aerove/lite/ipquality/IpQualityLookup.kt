package dev.aerove.lite.ipquality

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal suspend fun lookupIpQuality(
    ip: String,
    timeoutMillis: Long = 8000,
    hedgeMillis: Long = 1000,
    query: suspend (IpQualitySource, String) -> IpQualityAnswer,
): IpQuality = coroutineScope {
    require(validQualityIp(ip))
    val waves = listOf(listOf(IpQualitySource.IdentMe, IpQualitySource.IpApiCom, IpQualitySource.IpQuery),
        listOf(IpQualitySource.IpLocate), listOf(IpQualitySource.IpApiIs), listOf(IpQualitySource.ProxyCheck))
    data class Answer(val source: IpQualitySource, val result: Result<IpQualityAnswer>?, val wave: Int = 0)
    val events = Channel<Answer>(Channel.UNLIMITED)
    val jobs = mutableListOf<kotlinx.coroutines.Job>()
    val failures = mutableListOf<IpQualityFailure>()
    var fallback: IpQuality? = null
    var wave = 0
    var inFlight = 0
    var timer: kotlinx.coroutines.Job? = null
    fun launchWave() {
        timer?.cancel()
        if (wave == waves.size) return
        for (source in waves[wave++]) {
            inFlight++
            jobs += launch {
                val result = try { Result.success(withTimeout(timeoutMillis) { query(source, ip) }) }
                catch (failure: TimeoutCancellationException) { Result.failure(failure) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                events.send(Answer(source, result))
            }
        }
        val scheduledWave = wave
        if (wave < waves.size) timer = launch { delay(hedgeMillis); events.send(Answer(IpQualitySource.IdentMe, null, scheduledWave)) }
    }
    try {
        launchWave()
        while (inFlight > 0 || wave < waves.size) {
            val answer = events.receive()
            if (answer.result == null) { if (answer.wave == wave) launchWave(); continue }
            inFlight--
            val value = answer.result.getOrNull()
            if (value != null) {
                if (!value.inferred) return@coroutineScope value.quality
                if (fallback == null) fallback = value.quality
            } else {
                val status = when (val failure = answer.result.exceptionOrNull()) {
                    is IpQualitySourceException -> failure.status
                    is TimeoutCancellationException, is SocketTimeoutException, is InterruptedIOException -> IpQualitySourceStatus.Timeout
                    else -> IpQualitySourceStatus.Failed
                }
                failures += IpQualityFailure(answer.source, status)
            }
            if (inFlight == 0) launchWave()
        }
        fallback ?: throw IpQualityLookupException(failures.sortedBy { it.source.ordinal })
    } finally {
        timer?.cancel()
        jobs.forEach { it.cancel() }
    }
}
