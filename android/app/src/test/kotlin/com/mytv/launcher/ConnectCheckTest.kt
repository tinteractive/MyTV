// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.ConnectCheck
import com.mytv.launcher.data.ConnectCheck.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectCheckTest {
    private fun f(dev: Boolean? = true, adb: Boolean? = true, wireless: Boolean? = false, port: Boolean = true, last: String? = null, ok: Boolean = false) =
        ConnectCheck.Facts(dev, adb, wireless, port, last, ok)

    @Test fun connected() = assertEquals(Reason.OK, ConnectCheck.diagnose(f(ok = true)))
    @Test fun portOpenNotTried() = assertEquals(Reason.UNCHECKED, ConnectCheck.diagnose(f()))
    @Test fun developerOptionsFirst() = assertEquals(Reason.DEV_OFF, ConnectCheck.diagnose(f(dev = false, adb = false, port = false)))
    @Test fun usbDebuggingOff() = assertEquals(Reason.ADB_OFF, ConnectCheck.diagnose(f(adb = false, port = false)))
    @Test fun onlyWireless() = assertEquals(Reason.WIRELESS_ONLY, ConnectCheck.diagnose(f(wireless = true, port = false)))
    @Test fun wirelessWentOff() = assertEquals(Reason.WIRELESS_OFF, ConnectCheck.diagnose(f(port = false, adb = false).copy(paired = true)))
    @Test fun pairedAndReachable() = assertEquals(Reason.OK, ConnectCheck.diagnose(f(adb = false, wireless = true, ok = true).copy(paired = true)))
    @Test fun portClosed() = assertEquals(Reason.PORT_CLOSED, ConnectCheck.diagnose(f(port = false)))
    @Test fun unknownSettingsStillJudgedByPort() = assertEquals(Reason.PORT_CLOSED, ConnectCheck.diagnose(f(dev = null, adb = null, wireless = null, port = false)))
    @Test fun noAnswer() = assertEquals(Reason.NO_ANSWER, ConnectCheck.diagnose(f(last = "noanswer")))
    @Test fun refused() = assertEquals(Reason.REFUSED, ConnectCheck.diagnose(f(last = "refused")))
    @Test fun failed() = assertEquals(Reason.FAILED, ConnectCheck.diagnose(f(last = "failed")))

}
