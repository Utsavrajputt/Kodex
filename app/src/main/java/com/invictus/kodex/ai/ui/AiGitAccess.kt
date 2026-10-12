package com.invictus.kodex.ai.ui

import com.invictus.kodex.core.git.GitSession
import com.invictus.kodex.core.git.GitSessionRegistry
import java.io.File

/**
 * Borrows the shared, ref-counted [GitSession] for [projectPath] for the duration of [block]
 * and always releases it, so AI entry points never leak a JGit repository handle.
 */
internal suspend fun <T> withGitSession(projectPath: String, block: suspend (GitSession) -> T): T {
    val dir = File(projectPath)
    val session = GitSessionRegistry.acquire(dir)
    try {
        return block(session)
    } finally {
        GitSessionRegistry.release(dir)
    }
}
