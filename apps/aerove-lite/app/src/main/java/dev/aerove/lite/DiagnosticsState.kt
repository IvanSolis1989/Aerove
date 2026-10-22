package dev.aerove.lite

data class ServiceCheckView(
    val id: String,
    val status: String,
    val region: String? = null,
    val delayMillis: Long? = null,
    val chains: List<String> = emptyList(),
    val detail: String? = null,
    val checkedAt: Long,
    val coreEpoch: ULong? = null,
    val picksVersion: ULong? = null,
)

data class DiagnosticsState(
    val services: List<ServiceCheckView> = emptyList(),
    val serviceLoading: Boolean = false,
    val serviceError: String? = null,
    val refreshingServices: Set<String> = emptySet(),
    val memoryInUseBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
    val memoryScope: String? = null,
    val memoryLoading: Boolean = false,
    val memoryError: String? = null,
)
