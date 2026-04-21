package agentdock.acp

import java.io.ByteArrayOutputStream

/**
 * OutputStream wrapper that intercepts line-by-line output for logging
 * while passing data through to the delegate stream.
 */
internal class LineLoggingOutputStream(
    private val delegate: java.io.OutputStream,
    private val onLine: (String) -> Unit
) : java.io.OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        delegate.write(b)
        appendInternal(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        for (i in off until (off + len).coerceAtMost(b.size)) {
            appendInternal(b[i].toInt() and 0xff)
        }
    }

    private fun appendInternal(b: Int) {
        if (b == '\n'.code) {
            flushLine()
        } else {
            buffer.write(b)
        }
    }

    private fun flushLine() {
        val line = buffer.toString(Charsets.UTF_8).removeSuffix("\r")
        buffer.reset()
        if (line.isNotBlank()) {
            onLine(line)
        }
    }

    override fun flush() = delegate.flush()

    override fun close() {
        flushLine()
        delegate.close()
    }
}

/**
 * InputStream wrapper that intercepts line-by-line input for logging
 * while providing data to the consumer.
 */
internal class LineLoggingInputStream(
    delegate: java.io.InputStream,
    private val transform: ((String) -> String)? = null,
    private val onLine: (String) -> Unit
) : java.io.InputStream() {
    private val input = delegate
    private var currentChunk = ByteArray(0)
    private var currentIndex = 0

    override fun read(): Int {
        if (!ensureChunk()) return -1
        return currentChunk[currentIndex++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!ensureChunk()) return -1
        val available = currentChunk.size - currentIndex
        val count = minOf(len, available)
        System.arraycopy(currentChunk, currentIndex, b, off, count)
        currentIndex += count
        return count
    }

    override fun close() {
        input.close()
    }

    private fun ensureChunk(): Boolean {
        if (currentIndex < currentChunk.size) return true

        while (true) {
            val rawLine = readRawLine() ?: return false
            var line = rawLine.removeSuffix("\r")
            if (transform != null) {
                line = transform(line)
            }
            if (line.isNotBlank()) {
                onLine(line)
            }
            currentChunk = (line + "\n").toByteArray(Charsets.UTF_8)
            currentIndex = 0
            return true
        }
    }

    private fun readRawLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8)
            }
            if (next == '\n'.code) {
                return buffer.toString(Charsets.UTF_8)
            }
            buffer.write(next)
        }
    }
}
