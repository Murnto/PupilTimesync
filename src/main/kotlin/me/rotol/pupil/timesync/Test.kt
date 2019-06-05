package me.rotol.pupil.timesync

object Test {
    @JvmStatic
    fun main(args: Array<String>) {
//        val t = TimeEchoServer(::getTime)
//        t.start()

        val ts = TimeSync("TestNode")
        while (true) {
            println("Time: ${ts.getTime()} | ${System.currentTimeMillis()}")
            ts.pollNetwork()
            Thread.sleep(1000)
        }
    }
}
