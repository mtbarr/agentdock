package agentdock.utils

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes text to a file atomically by first writing to a temporary file
 * and then moving it to the destination, replacing the original.
 * This prevents data corruption if the process crashes during write.
 */
fun File.atomicWriteText(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8) {
    val parent = parentFile ?: File(".")
    parent.mkdirs()
    val tempPrefix = name.takeIf { it.length >= 3 } ?: "tmp"
    val tempPath = Files.createTempFile(parent.toPath(), "$tempPrefix.", ".tmp")
    var moved = false
    try {
        tempPath.toFile().writeText(text, charset)
        try {
            Files.move(
                tempPath,
                toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempPath,
                toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        moved = true
    } finally {
        if (!moved) {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }
}
