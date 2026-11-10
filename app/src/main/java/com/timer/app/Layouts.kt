package com.timer.app

/**
 * Predefined arrangements. They are only starting points: every number can be moved/scaled afterwards.
 * Coordinates are expressed in units of U (the shorter side of the screen), measured from the screen centre,
 * then stored as fractions of the visible frame so they survive orientation changes.
 */
object Presets {
    val NAMES: List<String> = listOf(
        "A  Single line", "B  Two rows", "C  Vertical stack", "D  Circular",
        "E  Two columns", "F  Asymmetrical", "G  Staircase", "H  Seconds focus"
    )

    private fun p(x: Float, y: Float, s: Float): FloatArray = floatArrayOf(x, y, s)

    private val DATA: Array<Array<FloatArray>> = arrayOf(
        arrayOf(p(0f, 0f, 1f), p(0f, 0f, 1f), p(0f, 0f, 1f), p(0f, 0f, 1f)), // A: flow row (positions computed live)
        arrayOf(p(-0.17f, -0.12f, 1f), p(0.17f, -0.12f, 1f), p(-0.22f, 0.12f, 1f), p(0.22f, 0.12f, 1f)),
        arrayOf(p(0f, -0.36f, 1f), p(0f, -0.12f, 1f), p(0f, 0.12f, 1f), p(0f, 0.36f, 1f)),
        arrayOf(p(0f, -0.34f, 0.9f), p(0.30f, 0f, 0.9f), p(0f, 0.34f, 0.9f), p(-0.30f, 0f, 0.9f)),
        arrayOf(p(-0.20f, -0.14f, 0.9f), p(-0.20f, 0.14f, 0.9f), p(0.20f, -0.14f, 0.9f), p(0.20f, 0.14f, 0.9f)),
        arrayOf(p(-0.10f, -0.30f, 1.6f), p(0.20f, -0.02f, 1.1f), p(-0.18f, 0.24f, 0.9f), p(0.20f, 0.32f, 0.6f)),
        arrayOf(p(-0.24f, -0.30f, 1f), p(-0.08f, -0.10f, 1f), p(0.08f, 0.10f, 1f), p(0.24f, 0.30f, 1f)),
        arrayOf(p(-0.22f, -0.30f, 0.6f), p(0.22f, -0.30f, 0.6f), p(0f, 0f, 2.0f), p(0f, 0.28f, 0.8f))
    )

    /** Which components keep their trailing separator (":" ":" "." ) in each preset. */
    private val SEPS: Array<BooleanArray> = arrayOf(
        booleanArrayOf(true, true, true, false),
        booleanArrayOf(true, false, true, false),
        booleanArrayOf(false, false, false, false),
        booleanArrayOf(false, false, false, false),
        booleanArrayOf(false, false, false, false),
        booleanArrayOf(false, false, false, false),
        booleanArrayOf(false, false, false, false),
        booleanArrayOf(false, false, false, false)
    )

    /**
     * Applies preset [id] to [set]. Sizes/rotation of the whole composition are reset to 1 / 0 so that
     * the preset looks as designed; the user can then change anything.
     */
    fun apply(id: Int, set: LayoutSet, lw: Float, lh: Float, u: Float) {
        val i = if (id < 0 || id >= DATA.size) 0 else id
        set.presetId = i
        set.flowRow = (i == 0)
        set.globalScale = 1f
        set.globalRotation = 0f
        for (c in 0 until 4) {
            val d = DATA[i][c]
            set.poses[c].x = d[0] * u / lw
            set.poses[c].y = d[1] * u / lh
            set.poses[c].scale = d[2]
            set.seps[c] = SEPS[i][c]
        }
    }

    /** Restores a saved Master Layout into the live layout. */
    fun restoreMaster(master: LayoutSet?, live: LayoutSet, includeGlobal: Boolean): Boolean {
        if (master == null) return false
        val keepScale = live.globalScale
        val keepRot = live.globalRotation
        live.copyFrom(master)
        if (!includeGlobal) {
            live.globalScale = keepScale
            live.globalRotation = keepRot
        }
        return true
    }
}
