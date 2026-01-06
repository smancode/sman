package ai.smancode.sman.ide.service

import java.net.*

object NetworkUtils {
    
    /**
     * 获取内网IP地址（10.x.x.x格式）
     * 优先返回10.x.x.x网段的IP地址
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            
            // 首先尝试找到10.x.x.x网段的IP
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress.startsWith("10.")) {
                            return hostAddress
                        }
                    }
                }
            }
            
            // 如果没有找到10.x.x.x网段，返回第一个可用的IPv4地址
            val fallbackInterfaces = NetworkInterface.getNetworkInterfaces()
            while (fallbackInterfaces.hasMoreElements()) {
                val networkInterface = fallbackInterfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            
            // 最后 fallback 到本地回环地址
            return "127.0.0.1"
            
        } catch (e: Exception) {
            // 异常时返回默认值
            return "127.0.0.1"
        }
    }
    
    /**
     * 获取用户ID（基于IP地址）
     * 格式：ip_10_x_x_x（将点替换为下划线）
     */
    fun getUserId(): String {
        val ipAddress = getLocalIpAddress()
        return "ip_${ipAddress.replace('.', '_')}"
    }
}