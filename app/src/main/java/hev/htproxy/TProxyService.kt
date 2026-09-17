package hev.htproxy

/**
 * JNI shim for the generic hev-socks5-tunnel Android binary.
 * The native release is exported against this package/class name.
 */
object TProxyService {
    external fun TProxyStartService(config_path: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
