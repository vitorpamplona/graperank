package com.vitorpamplona.graperank.v3Recursive

import com.vitorpamplona.graperank.utils.BaseStressTest
import com.vitorpamplona.graperank.utils.assertClose
import com.vitorpamplona.graperank.utils.printMemory
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.MuteListEvent
import com.vitorpamplona.quartz.nip51Lists.muteList.tags.UserTag
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import kotlin.test.Test
import kotlin.time.measureTime

class BrainstormStressTest: BaseStressTest() {
    val vitorHex = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    val odellHex = "04c915daefee38317fa734444acee390a8269fe5810b2241e5e6dd343dfbecc9"
    val jackHex = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"
    val davidHex= "e5272de914bd301755c439b88e6959a43c9d2664831f093c51e9c799a16a102f"
    val aviHex = "b83a28b7e4e5d20bd960c5faeb6625f95529166b8bdb045d42634a2f35919450"
    val corndalorianHex = "f8e6c64342f1e052480630e27e1016dce35fc3a614e60434fef4aa2503328ca9"

    @Test
    fun test() = with(Graph()) {
        val input = loadFile("wot_reference.jsonl")

        memoryCheck()

        val time = measureTime {
            input.forEachLine {
                val event = Event.fromJson(it)
                when (event) {
                    is ContactListEvent -> resetFollows(event)
                    is MuteListEvent -> resetMutes(event)
                    is ReportEvent -> resetReport(event)
                }
            }
        }

        println("Imported ${users.size} users in $time")
        printMemory()

        val vitor = user(vitorHex)
        val odell = user(odellHex)
        val jack = user(jackHex)
        val david = user(davidHex)
        val avi = user(aviHex)
        val corndalorian = user(corndalorianHex)

        val elapsed = measureTime {
            makeObserver(vitor)
        }
        println("Calculated Vitor's grapevine in $elapsed")

        printMemory()

        assertClose(1.0, vitor.scores[vitor])
        assertClose(0.960112, vitor.scores[odell])
        assertClose(0.978706, vitor.scores[jack])
        assertClose(0.999964, vitor.scores[david])
        assertClose(0.991724, vitor.scores[avi])
        assertClose(0.876057, vitor.scores[corndalorian])

        val elapsed2 = measureTime {
            david follows corndalorian
        }
        println("Calculated Vitor's grapevine in $elapsed2 when david follows corndalorian")

        assertClose(1.0, vitor.scores[vitor])
        assertClose(0.960112, vitor.scores[odell])
        assertClose(0.978706, vitor.scores[jack])
        assertClose(0.999964, vitor.scores[david])
        assertClose(0.991721, vitor.scores[avi])
        assertClose(0.876083, vitor.scores[corndalorian])

        val newPleb = newUser()
        val elapsed3 = measureTime {
            david follows newPleb
        }
        println("Calculated Vitor's grapevine in $elapsed3 when david follows newPleb")

        assertClose(1.0, vitor.scores[vitor])
        assertClose(0.960112, vitor.scores[odell])
        assertClose(0.978706, vitor.scores[jack])
        assertClose(0.999964, vitor.scores[david])
        assertClose(0.991721, vitor.scores[avi])
        assertClose(0.876083, vitor.scores[corndalorian])
        assertClose(0.027344, vitor.scores[newPleb])

        val elapsed4 = measureTime {
            vitor follows newPleb
        }
        println("Calculated Vitor's grapevine in $elapsed4 when vitor follows newPleb")

        assertClose(1.0, vitor.scores[vitor])
        assertClose(0.960112, vitor.scores[odell])
        assertClose(0.978706, vitor.scores[jack])
        assertClose(0.999964, vitor.scores[david])
        assertClose(0.991721, vitor.scores[avi])
        assertClose(0.876083, vitor.scores[corndalorian])
        assertClose(0.079811, vitor.scores[newPleb])
    }

    val users = mutableMapOf<HexKey, User>()

    context(graph: Graph)
    fun user(key: HexKey) = users.getOrPut(key) {
        graph.newUser()
    }

    context(graph: Graph)
    fun resetFollows(ev: ContactListEvent) {
        val author = user(ev.pubKey)

        ev.verifiedFollowKeySet().forEach { key ->
            author follows user(key)
        }
    }

    context(graph: Graph)
    fun resetMutes(ev: MuteListEvent) {
        val author = user(ev.pubKey)

        ev.publicMutes().forEach { tag ->
            if (tag is UserTag) {
                author mutes user(tag.pubKey)
            }
        }
    }

    context(graph: Graph)
    fun resetReport(ev: ReportEvent) {
        val author = user(ev.pubKey)

        ev.reportedAuthor().forEach { tag ->
            author reports user(tag.pubkey)
        }
    }
}