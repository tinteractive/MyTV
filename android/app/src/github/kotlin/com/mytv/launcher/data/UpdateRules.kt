// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

/**
 * The plain rules behind installing an update through the TV's own adb ([Updates]), with no Android in them, so
 * UpdateRulesTest checks them on a computer. The verified APK is copied to /data/local/tmp and `pm install -r` runs
 * there as adb's shell user, so Android does not ask "Do you want to update this app?".
 */
internal object UpdateRules {
    const val TMP_APK = "/data/local/tmp/mytv-update.apk"
    const val TMP_LOG = "/data/local/tmp/mytv-update.txt"

    /** Removes what an earlier try left. Also the first command of a try: it wakes the link before the copy. */
    const val CLEAN = "rm -f $TMP_APK $TMP_LOG"

    /** Free space needed before a try: the download, the copy on the TV and Android's own staged copy. */
    fun spaceNeeded(size: Long): Long = size * 5 / 2

    /**
     * What `pm install -r` wrote so far: null while it has not finished, [UpdateProblem.NONE] for Success, else why not.
     */
    fun installResult(out: String): UpdateProblem? {
        val o = out.trim()
        if (o.isEmpty()) return null
        if (o.lines().any { it.trim() == "Success" }) return UpdateProblem.NONE
        if (!o.contains("Failure") && !o.contains("Error") && !o.contains("Exception")) return null
        return failure(o)
    }

    /** Why an install or a copy to the TV failed, from what Android said. */
    fun failure(msg: String): UpdateProblem = when {
        msg.contains("INSUFFICIENT_STORAGE") || msg.contains("No space left", ignoreCase = true) || msg.contains("ENOSPC") -> UpdateProblem.NO_SPACE
        msg.contains("UPDATE_INCOMPATIBLE") || msg.contains("INCONSISTENT_CERTIFICATES") || msg.contains("NO_CERTIFICATES") ||
            msg.contains("SIGNATURE") -> UpdateProblem.FOREIGN_KEY
        msg.contains("VERSION_DOWNGRADE") -> UpdateProblem.NOT_NEWER
        else -> UpdateProblem.FAILED
    }

    /**
     * The shell line that installs the copied file. It runs on the TV apart from MyTV (setsid, nohup, nothing left
     * open), because Android stops MyTV while it replaces it, and that closes MyTV's adb link. It then deletes the copy,
     * and on Success opens [activity] again when [reopen] (MyTV was on screen: an update never takes over a programme).
     * The trailing sleep matters (seen on a TCL with Android 11): the plain "shell:" stream runs on a pty, and when
     * the outer sh exits, the pty hangs up its process group before the background child has called setsid, so the
     * install never ran. Two seconds let it leave first.
     */
    fun installScript(pkg: String, activity: String, reopen: Boolean): String {
        require(Regex("[A-Za-z0-9_.]+").matches(pkg) && Regex("[A-Za-z0-9_.]+").matches(activity))
        val back = if (reopen) "; grep -q Success $TMP_LOG && am start -n $pkg/$activity" else ""
        val body = "pm install -r $TMP_APK > $TMP_LOG 2>&1; rm -f $TMP_APK$back"
        return "rm -f $TMP_LOG; \$(command -v setsid) nohup sh -c '$body' > /dev/null 2>&1 < /dev/null & sleep 2"
    }
}
