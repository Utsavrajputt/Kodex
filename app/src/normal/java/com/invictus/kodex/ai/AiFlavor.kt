package com.invictus.kodex.ai

import android.app.Application

/** Normal flavor: no AI feature, nothing to initialize, no AI classes beyond the inert seam. */
object AiFlavor {
    val gate: AiGateImpl? = null
    fun init(app: Application) = Unit
}
