package net.jetnet.jetbox.engine

import android.content.Context

/** Точка входа к туннелю: экраны и сценарий берут движок здесь, а не создают сами. */
object JetBoxEngine {
    /** Движок процесса (синглтон [AndroidEngine]). */
    fun get(context: Context): EngineApi = AndroidEngine.get(context)
}
