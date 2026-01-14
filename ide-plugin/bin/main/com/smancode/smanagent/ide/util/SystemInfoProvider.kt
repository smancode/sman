package com.smancode.smanagent.ide.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Enumeration

/**
 * 系统信息提供者
 * <p>
 * 跨平台获取用户IP地址和主机名称
 */
object SystemInfoProvider {

    private val logger: Logger = LoggerFactory.getLogger(SystemInfoProvider::class.java)

    /**
     * 获取用户内网IPv4地址
     * <p>
     * 优先返回以太网或无线网卡的IP，排除回环和虚拟网卡
     *
     * @return IPv4地址，如果获取失败返回 "unknown"
     */
    fun getLocalIpAddress(): String {
        return try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()

            // 按优先级排序网卡
            val priorityInterfaces = mutableListOf<NetworkInterface>()
            val fallbackInterfaces = mutableListOf<NetworkInterface>()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // 跳过回环接口、down掉的接口、虚拟接口
                if (networkInterface.isLoopback ||
                    !networkInterface.isUp ||
                    networkInterface.isVirtual) {
                    continue
                }

                // 跳过虚拟网卡（根据名称判断）
                val name = networkInterface.name.lowercase()
                if (name.contains("veth") ||
                    name.contains("docker") ||
                    name.contains("tun") ||
                    name.contains("tap") ||
                    name.contains("vbox")) {
                    continue
                }

                // 按优先级分组
                when {
                    name.matches(Regex("^(eth|en)\\d+")) -> {
                        // 以太网（Linux: eth*, macOS: en*）
                        priorityInterfaces.add(networkInterface)
                    }
                    name.matches(Regex("^(wlan|wl)\\d+")) -> {
                        // 无线网卡（Linux: wlan*）
                        fallbackInterfaces.add(networkInterface)
                    }
                    name.matches(Regex("^en\\d+")) -> {
                        // macOS en* 可能是以太网或WiFi
                        if (priorityInterfaces.size < fallbackInterfaces.size) {
                            priorityInterfaces.add(networkInterface)
                        } else {
                            fallbackInterfaces.add(networkInterface)
                        }
                    }
                    else -> {
                        fallbackInterfaces.add(networkInterface)
                    }
                }
            }

            // 合并列表：优先级高的在前
            val sortedInterfaces = priorityInterfaces + fallbackInterfaces

            // 遍历网卡，找到第一个合适的IPv4地址
            for (networkInterface in sortedInterfaces) {
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()

                    // 只要IPv4地址
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress

                        // 排除回环地址和链路本地地址
                        if (!ip.startsWith("127.") &&
                            !ip.startsWith("169.254.")) {
                            logger.debug("检测到本地IP地址: {} (网卡: {})", ip, networkInterface.name)
                            return ip
                        }
                    }
                }
            }

            logger.warn("未找到合适的IPv4地址")
            return "unknown"
        } catch (e: Exception) {
            logger.warn("获取本地IP地址失败", e)
            return "unknown"
        }
    }

    /**
     * 获取机器名称（hostname）
     *
     * @return hostname，如果获取失败返回 "unknown"
     */
    fun getHostName(): String {
        return try {
            val hostName = InetAddress.getLocalHost().hostName
            logger.debug("检测到主机名称: {}", hostName)
            hostName
        } catch (e: Exception) {
            // 降级方案：使用环境变量
            val envHostname = System.getenv("HOSTNAME") ?: System.getenv("COMPUTERNAME")
            if (envHostname != null) {
                logger.debug("从环境变量获取主机名称: {}", envHostname)
                return envHostname
            }
            logger.warn("获取主机名称失败", e)
            "unknown"
        }
    }
}
