package dev.secondsun.sfxstudio.core.ops

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque

/**
 * Policy boundary governing filesystem path resolution and workspace sandbox enforcement
 * for [StudioOperations].
 *
 * Two concrete adapters exist:
 * - [UNRESTRICTED]: used by the local interactive CLI and desktop application.
 * - [sandboxed]: used by `StudioMcpServer`, enforcing both lexical normalization and
 *   `toRealPath()` symlink canonicalization on existing path prefixes so neither `../`
 *   traversal, crafted `doc.atlasPath` fields, nor filesystem symlinks can escape `workspaceRoot`.
 */
interface PathPolicy {
    fun resolve(rawPath: String): Path

    fun resolveRelativeTo(
        baseFile: Path,
        relativeOrAbsolutePath: String,
    ): Path

    fun formatForOutput(path: Path): String

    companion object {
        private const val MAX_SYMLINK_HOPS = 16

        val UNRESTRICTED: PathPolicy =
            object : PathPolicy {
                override fun resolve(rawPath: String): Path = Path.of(rawPath).toAbsolutePath().normalize()

                override fun resolveRelativeTo(
                    baseFile: Path,
                    relativeOrAbsolutePath: String,
                ): Path {
                    val candidate = Path.of(relativeOrAbsolutePath)
                    if (candidate.isAbsolute) {
                        return candidate.normalize()
                    }
                    val baseDir = baseFile.toAbsolutePath().normalize().parent ?: Path.of("").toAbsolutePath()
                    return baseDir.resolve(candidate).normalize()
                }

                override fun formatForOutput(path: Path): String = path.toAbsolutePath().normalize().toString()
            }

        fun sandboxed(workspaceRoot: Path): PathPolicy = SandboxedPathPolicy(workspaceRoot)

        internal fun canonicalizeExistingPrefix(
            target: Path,
            hopCount: Int = 0,
        ): Path {
            if (hopCount > MAX_SYMLINK_HOPS) {
                throw SecurityException("Access denied: too many symbolic link levels resolving '$target'")
            }
            val normalized = target.toAbsolutePath().normalize()
            val tailSegments = ArrayDeque<Path>()
            var current: Path? = normalized

            while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                current.fileName?.let { tailSegments.addFirst(it) }
                current = current.parent
            }

            val canonicalBase =
                when {
                    current == null -> normalized
                    Files.exists(current) -> current.toRealPath()
                    Files.isSymbolicLink(current) -> {
                        val linkTarget = Files.readSymbolicLink(current)
                        val parentDir = current.parent ?: normalized.root ?: Path.of("/")
                        val resolvedLink = parentDir.resolve(linkTarget).normalize()
                        canonicalizeExistingPrefix(resolvedLink, hopCount + 1)
                    }
                    else -> current.toAbsolutePath().normalize()
                }

            var rebuilt = canonicalBase
            for (segment in tailSegments) {
                rebuilt = rebuilt.resolve(segment)
            }
            return rebuilt.normalize()
        }
    }
}

private class SandboxedPathPolicy(
    workspaceRoot: Path,
) : PathPolicy {
    private val lexicalRoot: Path = workspaceRoot.toAbsolutePath().normalize()
    private val canonicalRoot: Path = PathPolicy.canonicalizeExistingPrefix(lexicalRoot)

    override fun resolve(rawPath: String): Path {
        val raw = Path.of(rawPath)
        val lexicalResolved =
            if (raw.isAbsolute) {
                raw.normalize()
            } else {
                lexicalRoot.resolve(raw).normalize()
            }
        return verifyInsideSandbox(rawPath, lexicalResolved)
    }

    override fun resolveRelativeTo(
        baseFile: Path,
        relativeOrAbsolutePath: String,
    ): Path {
        val checkedBase = verifyInsideSandbox(baseFile.toString(), baseFile.toAbsolutePath().normalize())
        val raw = Path.of(relativeOrAbsolutePath)
        val lexicalResolved =
            if (raw.isAbsolute) {
                raw.normalize()
            } else {
                val baseDir = checkedBase.parent ?: lexicalRoot
                baseDir.resolve(raw).normalize()
            }
        return verifyInsideSandbox(relativeOrAbsolutePath, lexicalResolved)
    }

    override fun formatForOutput(path: Path): String {
        val canonicalTarget = PathPolicy.canonicalizeExistingPrefix(path)
        return if (canonicalTarget.startsWith(canonicalRoot)) {
            canonicalRoot.relativize(canonicalTarget).toString().replace('\\', '/')
        } else {
            canonicalTarget.toString()
        }
    }

    private fun verifyInsideSandbox(
        displayPath: String,
        lexicalResolved: Path,
    ): Path {
        val canonicalTarget = PathPolicy.canonicalizeExistingPrefix(lexicalResolved)
        if (!canonicalTarget.startsWith(canonicalRoot)) {
            throw SecurityException(
                "Access denied: path '$displayPath' resolved to '$canonicalTarget' " +
                    "which is outside workspace root '$canonicalRoot'",
            )
        }
        return canonicalTarget
    }
}
