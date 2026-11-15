package app.gamenative.utils

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv4FirstDnsTest {

    private fun address(host: String): InetAddress = InetAddress.getByName(host)

    private fun dnsReturning(vararg hosts: String) = Ipv4FirstDns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = hosts.map(::address)
        },
    )

    @Test
    fun putsIpv4BeforeIpv6() {
        val ipv6First = address("2a06:98c1:3120::1")
        val ipv4 = address("104.16.0.1")
        val ipv6Second = address("2a06:98c1:3120::2")

        assertEquals(
            listOf(ipv4, ipv6First, ipv6Second),
            Ipv4FirstDns(
                object : Dns {
                    override fun lookup(hostname: String) = listOf(ipv6First, ipv4, ipv6Second)
                },
            ).lookup("downloads.gamenative.app"),
        )
    }

    @Test
    fun keepsOrderInsideEachFamily() {
        val firstV4 = address("1.1.1.1")
        val secondV4 = address("2.2.2.2")
        val firstV6 = address("2001:db8::1")
        val secondV6 = address("2001:db8::2")

        assertEquals(
            listOf(firstV4, secondV4, firstV6, secondV6),
            Ipv4FirstDns(
                object : Dns {
                    override fun lookup(hostname: String) = listOf(firstV6, firstV4, secondV6, secondV4)
                },
            ).lookup("example.com"),
        )
    }

    @Test
    fun returnsSingleAddressAsIs() {
        assertEquals(
            listOf(address("2001:db8::1")),
            dnsReturning("2001:db8::1").lookup("example.com"),
        )
    }

    @Test
    fun keepsIpv4OnlyListUntouched() {
        assertEquals(
            listOf(address("1.1.1.1"), address("2.2.2.2")),
            dnsReturning("1.1.1.1", "2.2.2.2").lookup("example.com"),
        )
    }
}
