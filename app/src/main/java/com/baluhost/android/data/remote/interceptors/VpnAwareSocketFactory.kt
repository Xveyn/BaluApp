package com.baluhost.android.data.remote.interceptors

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * SocketFactory that binds sockets to the VPN network when available.
 *
 * WireGuard's GoBackend excludes our app from VPN routing via
 * addDisallowedApplication to prevent routing loops. This means API
 * requests to the NAS's local IP (e.g. 192.168.178.21) bypass the
 * VPN tunnel and fail when the device is outside the home network.
 *
 * This factory explicitly binds sockets to the VPN network interface,
 * overriding the UID-based routing exclusion so API traffic reaches
 * the NAS through the tunnel.
 */
class VpnAwareSocketFactory(
    private val connectivityManager: ConnectivityManager
) : SocketFactory() {

    @Suppress("DEPRECATION")
    private fun findVpnNetwork() = try {
        connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (e: Exception) {
        null
    }

    override fun createSocket(): Socket {
        val socket = Socket()
        val vpnNetwork = findVpnNetwork()
        if (vpnNetwork != null) {
            vpnNetwork.bindSocket(socket)
            Log.d(TAG, "Bound socket to VPN network")
        }
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = createSocket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(
        host: String, port: Int,
        localHost: InetAddress, localPort: Int
    ): Socket {
        val socket = createSocket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = createSocket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(
        address: InetAddress, port: Int,
        localAddress: InetAddress, localPort: Int
    ): Socket {
        val socket = createSocket()
        socket.bind(InetSocketAddress(localAddress, localPort))
        socket.connect(InetSocketAddress(address, port))
        return socket
    }

    companion object {
        private const val TAG = "VpnAwareSocketFactory"
    }
}
