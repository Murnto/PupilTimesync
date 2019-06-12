package me.rotol.pupil

import org.zeromq.ZMQ
import org.zeromq.czmq.Zmsg
import org.zeromq.zyre.Zyre
import org.zeromq.zyre.ZyreEvent
import java.util.*
import java.util.logging.Level.FINE
import java.util.logging.Level.SEVERE
import java.util.logging.Logger

val __LOG = Logger.getLogger("Utils")

fun Zyre.recentEvents() = sequence {
    while (this@recentEvents.socket().events() and ZMQ.Poller.POLLIN != 0) {
        yield(ZyreEvent(this@recentEvents))
    }
}

fun ZMQ.Socket.recentEvents() = sequence {
    while (this@recentEvents.events and ZMQ.Poller.POLLIN != 0) {
        val multipart = this@recentEvents.recvMultipart()
        __LOG.log(FINE, "Socket.recentEvents()", "multipart=$multipart")
        yield(multipart)
    }
}

fun ZMQ.Socket.recvMultipart(): ArrayList<String> {
    val data = arrayListOf(recvStr())

    while (this@recvMultipart.hasReceiveMore()) {
        data.add(recvStr())
    }

    return data
}

fun ZMQ.Socket.sendMultiPart(vararg data: Any) {
    data.forEachIndexed { i, s ->
        if (i == data.size - 1) {
            when (s) {
                is String -> this@sendMultiPart.send(s)
                is ByteArray -> this@sendMultiPart.send(s)
                else -> {
                    __LOG.log(SEVERE, "ZMQ.Socket.sendMultiPart", "Bad argument type ${s.javaClass}")
                    throw IllegalArgumentException(s.javaClass.canonicalName)
                }
            }
        } else {
            when (s) {
                is String -> this@sendMultiPart.sendMore(s)
                is ByteArray -> this@sendMultiPart.sendMore(s)
                else -> {
                    __LOG.log(SEVERE, "ZMQ.Socket.sendMultiPart", "Bad argument type ${s.javaClass}")
                    throw IllegalArgumentException(s.javaClass.canonicalName)
                }
            }
        }
    }
}

fun ZyreEvent.multipart(): Sequence<String> = sequence {
    val msg = this@multipart.msg
    var frameString: String? = msg.popstr()
    while (frameString != null) {
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        yield(frameString!!)

        frameString = msg.popstr()
    }
}

fun Zyre.shoutMultipart(group: String, vararg data: Any) {
    this.shout(group, constructMultipart(*data))
}

fun constructMultipart(vararg data: Any): Zmsg {
    val msg = Zmsg()
    data.forEach {
        when (it) {
            is String -> msg.addstr(it)
            is ByteArray -> msg.addmem(it, it.size.toLong())
            else -> {
                __LOG.log(SEVERE, "constructMultipart", "Bad argument type ${it.javaClass}")
                throw IllegalArgumentException(it.javaClass.canonicalName)
            }
        }
    }
    return msg
}

inline fun <reified T> timeExec(tag: String, mark: String, block: () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        val diff = System.nanoTime() - start

        __LOG.log(FINE, tag, "$mark took ${diff / 1000} micros, ${diff / 1000000} millis")
    }
}

fun getIpv4(address: String): String {
    return address.split("//", limit = 2)[1].split(":")[0]
}
