package dev.aerove.lite.profilebuilder

import dev.aerove.lite.regexp2.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Uses the actual pinned production Go service, not a test implementation of regexp matching. */
internal class RealGoFilterExecutor : ExactFilterExecutor {
    override suspend fun indices(include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean): List<Int> = withContext(Dispatchers.IO) {
        val executable = requireNotNull(System.getenv("AEROVE_FILTER_HELPER")) { "Set AEROVE_FILTER_HELPER to the built production cmd/hostverify" }
        val process = ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val request = FilterProtocol.request(1, include, exclude, names, validateOnly)
            val command = JSONObject().put("type", "batch").put("id", 1).put("batch", JSONObject(request.toString(Charsets.UTF_8))).toString().toByteArray()
            process.outputStream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command.size).array())
            process.outputStream.write(command); process.outputStream.flush()
            val input = DataInputStream(process.inputStream)
            val size = Integer.reverseBytes(input.readInt())
            require(size in 1..FilterProtocol.MAX_BYTES)
            FilterProtocol.indices(1, ByteArray(size).also(input::readFully), names.size)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
