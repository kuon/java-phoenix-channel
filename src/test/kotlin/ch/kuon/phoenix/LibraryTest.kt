package ch.kuon.phoenix

import kotlin.test.*
import com.github.openjson.JSONObject
import com.github.openjson.JSONArray
import net.jodah.concurrentunit.Waiter

val url = "ws://localhost:4444/socket"

class LibraryTest {

    @Test fun testURL() {
        val sd = Socket(url)

        assertEquals(
            "ws://localhost:4444/socket/websocket?vsn=2.0.0",
            sd.endPointURL().toString(),
            "URL do not match"
        )
    }

    @Test fun testURLWithParams() {
        val opts = Socket.Options()
        opts.params= hashMapOf(
            "foo" to "bar",
            "éèàé" to "ｕｎｉｃｏｄｅ",
            "spaces" to "    ",
            "empty" to ""
        )

        val sd = Socket(url, opts)

        assertEquals(
            "ws://localhost:4444/socket/websocket?vsn=2.0.0&spaces=%20%20%20%20&éèàé=ｕｎｉｃｏｄｅ&foo=bar&empty=",
            sd.endPointURL().toString(),
            "URL do not match"
        )
    }

    @Test fun testConnect() {
        val waiter = Waiter()

        val sd = Socket(url)

        sd.onOpen {
            waiter.resume()
        }
        sd.onError {
            waiter.fail("Socket error")
        }
        sd.connect()

        waiter.await(10000)

        sd.onClose { code, _ ->
            waiter.assertEquals(code, 1000)
            waiter.resume()
        }
        sd.disconnect()
        waiter.await(10000)
    }

    @Test fun testJoinError() {
        val waiter = Waiter()

        val sd = Socket(url)
        sd.connect()

        val chan = sd.channel(
            "mock:lobby"
        )

        chan
        .join()
        .receive("ok") { _ ->
            waiter.fail("Channel should fail join without auth")
        }
        .receive("error") { _ ->
            waiter.resume()
        }
        waiter.await(10000)
        sd.disconnect()
    }

    @Test fun testEcho() {
        val waiter = Waiter()

        val sd = Socket(url)
        sd.connect()

        val chan = sd.channel(
            "mock:lobby",
            JSONObject(hashMapOf("auth" to "secret"))
        )

        val obj = JSONObject(
            hashMapOf(
                "data1" to "payload1",
                "data2" to "payload2",
                "data3" to hashMapOf(
                    "data4" to "payload3",
                    "data5" to "payload4",
                    "data6" to listOf(
                        "a1", "a2", "a3"
                    )
                )
            )
        )

        chan
        .join()
        .receive("ok") { msg ->
            waiter.assertEquals("mockdata", msg.getString("mock"))
            waiter.resume()
        }
        .receive("error") { _ ->
            waiter.fail("Cannot join channel")
        }
        waiter.await(10000)

        chan
        .push("echo", obj)
        .receive("ok") { msg ->
            waiter.assertEquals(obj.getString("data1"), msg.getString("data1"))
            waiter.assertEquals(obj.getString("data2"), msg.getString("data2"))
            val a = obj.getJSONObject("data3")
            val b = msg.getJSONObject("data3")
            waiter.assertEquals(a.getString("data4"), b.getString("data4"))
            waiter.assertEquals(a.getString("data5"), b.getString("data5"))
            waiter.assertEquals(
                a.getJSONArray("data6").toString(),
                b.getJSONArray("data6").toString()
            )
            waiter.resume()
        }
        waiter.await(10000)
        sd.disconnect()
        Thread.sleep(500)
    }

    @Test fun testBroadcast() {
        val waiter = Waiter()

        val sd1 = Socket(url)
        val sd2 = Socket(url)
        val sd3 = Socket(url)
        sd1.connect()
        sd2.connect()
        sd3.connect()

        val auth = JSONObject(hashMapOf("auth" to "secret"))
        val chan1 = sd1.channel("mock:lobby", auth)
        val chan2 = sd2.channel("mock:lobby", auth)
        val chan3 = sd3.channel("mock:lobby", auth)

        chan1
        .join()
        .receive("ok") { _ ->
            waiter.resume()
        }

        chan2
        .join()
        .receive("ok") { _ ->
            waiter.resume()
        }

        chan3
        .join()
        .receive("ok") { _ ->
            waiter.resume()
        }

        waiter.await(10000, 3)

        chan1.on("broadcast") { msg ->
            waiter.assertEquals("ok", msg.status)
            waiter.assertEquals("br-data", msg.response.getString("br"))
            waiter.resume()
        }

        chan2.on("broadcast") { msg ->
            waiter.assertEquals("ok", msg.status)
            waiter.assertEquals("br-data", msg.response.getString("br"))
            waiter.resume()
        }

        chan3.on("broadcast") { msg ->
            waiter.assertEquals("ok", msg.status)
            waiter.assertEquals("br-data", msg.response.getString("br"))
            waiter.resume()
        }

        chan3.push("trigger", JSONObject(hashMapOf("br" to "br-data")))

        waiter.await(10000, 3)
        sd1.disconnect()
        sd2.disconnect()
        sd3.disconnect()
        Thread.sleep(500)
    }

    @Test fun testPresence() {
        val waiterOnJoin1 = Waiter()
        val waiterOnJoin2 = Waiter()
        val waiterOnLeave1 = Waiter()
        val waiterOnLeave2 = Waiter()
        val waiterOnSync1 = Waiter()
        val waiterOnSync2 = Waiter()
        val sd1 = Socket(url)
        val sd2 = Socket(url)

        sd1.connect()
        sd2.connect()

        val ch1 = sd1.channel("mock:presence")
        val ch2 = sd2.channel("mock:presence")


        val pr1 = Presence(ch1)
        val pr2 = Presence(ch2)

        pr1.onJoin { _, _, _ ->
            waiterOnJoin1.resume()
        }
        pr1.onLeave { _, _, _ ->
            waiterOnLeave1.resume()
        }
        pr1.onSync {
            waiterOnSync1.resume()
        }
        pr2.onJoin { _, _, _ ->
            waiterOnJoin2.resume()
        }
        pr2.onLeave { _, _, _ ->
            waiterOnLeave2.resume()
        }
        pr2.onSync {
            waiterOnSync2.resume()
        }

        waiterOnJoin1.assertEquals(listOf<String>(), pr1.list())
        waiterOnJoin1.assertEquals(listOf<String>(), pr2.list())

        ch1.join()
        waiterOnJoin1.await(10000, 1)
        waiterOnSync1.await(10000, 2)

        ch2.join()
        waiterOnJoin2.await(10000, 1)
        waiterOnSync2.await(10000, 2)

        waiterOnJoin1.await(10000, 1)
        waiterOnSync1.await(10000, 1)

        ch2.leave()
        waiterOnLeave1.await(10000, 1)

        sd2.disconnect()
        sd1.disconnect()
        Thread.sleep(500)
    }

    @Test fun testTimer() {
        val waiter = Waiter()

        var counter = 0
        val timer = ExTimer({
            counter++
            waiter.resume()
        }, { tries ->
            tries * 1000
        })
        // This is for the example, if you call
        // scheduleTimeout() in a row, the last call "wins"
        // This example will call the callback only once after
        // 4 seconds then once again after 1 second
        timer.scheduleTimeout() // fires after 1000
        timer.scheduleTimeout() // fires after 2000
        timer.scheduleTimeout() // fires after 3000
        timer.reset()
        timer.scheduleTimeout() // fires after 1000
        timer.scheduleTimeout() // fires after 2000
        timer.scheduleTimeout() // fires after 3000
        timer.scheduleTimeout() // fires after 4000
        waiter.await(5000, 1)
        waiter.assertEquals(1, counter)

        timer.reset()
        timer.scheduleTimeout() // fires after 1000
        waiter.await(2000, 1)
        waiter.assertEquals(2, counter)
    }

}
