package com.evanna.player.usb

import org.junit.Assert.*
import org.junit.Test

class UsbPermissionSessionTest {
    @Test fun grantedResultRequiresCurrentRequestAndAttachedPermittedDevice() {
        val session = UsbPermissionSession()
        val request = session.begin(7)
        assertEquals(UsbPermissionSession.Result.GRANTED, session.finish(request, attached = true, permitted = true))
        assertNull(session.pending)
    }

    @Test fun platformPermissionIsCheckedRatherThanTrustingCallback() {
        val session = UsbPermissionSession()
        assertEquals(UsbPermissionSession.Result.DENIED, session.finish(session.begin(7), true, false))
    }

    @Test fun unpluggedDeviceCannotProduceGrant() {
        val session = UsbPermissionSession()
        assertEquals(UsbPermissionSession.Result.DISCONNECTED, session.finish(session.begin(7), false, true))
    }

    @Test fun lateReplyCannotClearNewRequestEvenForSameDevice() {
        val session = UsbPermissionSession()
        val old = session.begin(7)
        val current = session.begin(7)
        assertNull(session.finish(old, true, true))
        assertEquals(current, session.pending)
    }

    @Test fun detachedRequestAndDuplicateReplyAreIgnored() {
        val session = UsbPermissionSession()
        val request = session.begin(7)
        session.detach(7)
        assertNull(session.finish(request, true, true))
        val next = session.begin(8)
        session.finish(next, true, true)
        assertNull(session.finish(next, true, true))
    }

    @Test fun unrelatedDetachDoesNotCancelPermissionDialog() {
        val session = UsbPermissionSession()
        val request = session.begin(7)
        session.detach(8)
        assertEquals(request, session.pending)
    }
}
