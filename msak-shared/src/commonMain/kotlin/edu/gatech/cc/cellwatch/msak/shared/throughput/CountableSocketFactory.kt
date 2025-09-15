//package edu.gatech.cc.cellwatch.msak.shared.throughput
//
//import java.net.InetAddress
//import java.net.Socket
//import javax.net.SocketFactory
//
///**
// * A factory to create sockets that keep track of the number of bytes sent and received at the
// * transport layer. Does not work reliably without Conscrypt.
// */
//class CountableSocketFactory: SocketFactory() {
//    /**
//     * The sockets that have been created by this factory.
//     */
//    val sockets = ArrayList<CountableSocket>()
//
//    override fun createSocket(): Socket {
//        val sock = CountableSocket()
//        sockets.add(sock)
//        return sock
//    }
//
//    // OkHttp docs claim to only use the parameter-less constructor above -- leave the rest
//    // un-implemented
//
//    override fun createSocket(host: String?, port: Int): Socket {
//        TODO("Not yet implemented")
//    }
//
//    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
//        TODO("Not yet implemented")
//    }
//
//    override fun createSocket(host: InetAddress?, port: Int): Socket {
//        TODO("Not yet implemented")
//    }
//
//    override fun createSocket( address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
//        TODO("Not yet implemented")
//    }
//}
