package jp.juggler.tmvolume

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress

fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int = 0): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) {
        getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, flags)!!
    }

fun Context.getV4Addresses(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        val connectivityManager =
            ContextCompat.getSystemService(applicationContext, ConnectivityManager::class.java)
                ?: error("missing connectivityManager")
        val activeNetwork = connectivityManager.activeNetwork
            ?: error("missing activeNetwork")
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            ?: error("missing linkProperties")
        linkProperties.linkAddresses
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
    } else {
        val wifiManager = ContextCompat.getSystemService(this, WifiManager::class.java)
            ?: error("missing wifiManager")

        @Suppress("DEPRECATION")
        val connectionInfo = wifiManager.connectionInfo
            ?: error("missing connectionInfo")

        @Suppress("DEPRECATION")
        val ipAddress = connectionInfo.ipAddress

        val inetAddress = InetAddress.getByAddress(
            BigInteger.valueOf(ipAddress.toLong())
                .toByteArray()
                .reversedArray()
        )

        inetAddress.hostAddress?.let { listOf(it) }
            ?: error("hostAddress is null")
    }
