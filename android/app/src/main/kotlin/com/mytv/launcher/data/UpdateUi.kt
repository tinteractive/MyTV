// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

/** Why an update attempt stopped (shown on the Updates page). */
enum class UpdateProblem { NONE, OFFLINE, CORRUPT, FOREIGN_KEY, NEEDS_PERMISSION, CANCELLED, FAILED, NO_SPACE, NOT_NEWER }

/** Everything the Updates page shows. */
data class UpdateUi(
    val supported: Boolean = false,
    val latest: String = "",
    val available: Boolean = false,
    /** Release notes of the versions newer than this one (raw JSON array, read by the update screen). */
    val notes: String = "[]",
    val checkedAt: Long = 0L,
    val auto: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val percent: Int = 0,
    val problem: UpdateProblem = UpdateProblem.NONE,
    /** This try installs without Android's question: MyTV closes and opens again by itself. */
    val quiet: Boolean = false,
    /** The version an update just put on, until "Updated to …" was shown once; else "". */
    val justUpdated: String = "",
) {
    enum class Phase { IDLE, CHECKING, DOWNLOADING, INSTALLING }
}
