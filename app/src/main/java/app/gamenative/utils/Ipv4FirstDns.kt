package app.gamenative.utils

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Отдаёт IPv4-адреса раньше IPv6, порядок внутри групп сохраняется.
 * Зачем: на сетях с анонсированным, но нерабочим IPv6 первым из DNS приходит IPv6,
 * и OkHttp тратит на него весь connectTimeout, так и не попробовав рабочий IPv4.
 */
class Ipv4FirstDns(private val delegate: Dns) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.size < 2) return addresses
        // sortedBy стабильна: адреса внутри IPv4 и внутри IPv6 сохраняют исходный порядок.
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
