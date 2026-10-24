package dev.aerove.lite.ui

/** Evidence failure must not replace an exception already being propagated. */
internal inline fun preserveMatrixEvidenceFailure(primary: Throwable?, write: () -> Unit) {
    try {
        write()
    } catch (failure: Throwable) {
        if (primary == null) throw failure
        if (failure !== primary) primary.addSuppressed(failure)
    }
}
