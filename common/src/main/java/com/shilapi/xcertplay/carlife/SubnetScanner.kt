package com.shilapi.xcertplay.carlife

import android.util.Log
import com.diplay.carlife.core.CarLife
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Finds the CarLife phone on the local network by probing its CMD port (7240).
 *
 * Wireless CarLife has no publicly documented discovery protocol (CarLife <=
 * 6.7.2 / CarWith open their six TCP ports after the user taps 无线连接), so we
 * scan the /24 of each active IPv4 interface. Ports appear only while the phone
 * waits for a head unit, so scans are cheap and user-triggered.
 */
object SubnetScanner {

    private const val TAG = "CarLifeScan"
    private const val PROBE_PORT = CarLife.MD_PORT_CMD
    private const val CONNECT_TIMEOUT_MS = 150

    /** Returns the first address whose port 7240 accepts a connection. */
    fun findPhone(onFound: (InetAddress) -> Unit, onDone: () -> Unit): Thread {
        val candidates = subnetAddresses()
        var done = false
        val lock = Any()
        val threads = candidates.map { addr ->
            thread(name = "carlife-probe-${addr.hostAddress}") {
                if (probe(addr)) {
                    synchronized(lock) {
                        if (!done) {
                            done = true
                            Log.i(TAG, "CarLife phone found at ${addr.hostAddress}")
                            onFound(addr)
                        }
                    }
                }
            }
        }
        return thread(name = "carlife-scan-join") {
            threads.forEach { it.join() }
            synchronized(lock) {
                if (!done) {
                    done = true
                    onDone()
                }
            }
        }.apply { start() }
    }

    private fun probe(address: InetAddress): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(address, PROBE_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** All local /24 addresses across non-loopback IPv4 interfaces. */
    fun subnetAddresses(): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in Collections.list(nis)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in Collections.list(ni.inetAddresses)) {
                    if (ia !is Inet4Address || ia.isLoopbackAddress) continue
                    val ip = ia.address
                    if (ip.size != 4) continue
                    val base = ip.copyOf()
                    base[3] = 0
                    for (host in 1..254) {
                        val candidate = base.copyOf()
                        candidate[3] = host.toByte()
                        if (!candidate.contentEquals(ip)) {
                            result.add(InetAddress.getByAddress(candidate))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "subnet enumeration failed", e)
        }
        return result
    }
}
