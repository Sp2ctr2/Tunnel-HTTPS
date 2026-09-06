package com.tunnelvpn.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeededLifecycleEpochTest {
    private data class Instance(
        val id: Long,
        val guard: ServiceLifecycleCommitGuard,
        var dead: Boolean = false,
        var handler: Any = Any(),
        var epoch: Long = 0L
    )

    private data class Callback(val instance: Instance, val handler: Any, val epoch: Long)

    @Test
    fun seededDelayedCallbacksCannotCrossServiceOrHandlerEpochs() {
        for (seed in listOf(0x51a7, 0x9e31, 0x236d)) {
            val random = Random(seed)
            val ownership = ServiceInstanceOwnership()
            val instances = ArrayDeque<Instance>()
            val pending = ArrayDeque<Callback>()
            var nextId = 0L
            var current: Instance? = null
            var rejected = 0
            var accepted = 0
            repeat(10_000) { step ->
                when (random.nextInt(5)) {
                    0 -> {
                        val id = ++nextId
                        val instance = Instance(id, ServiceLifecycleCommitGuard(id, ownership))
                        ownership.claim(id)
                        current = instance
                        instances.addLast(instance)
                        if (instances.size > 64) instances.removeFirst()
                    }
                    1 -> if (instances.isNotEmpty()) {
                        val target = instances[random.nextInt(instances.size)]
                        target.guard.destroy()
                        target.dead = true
                    }
                    2 -> current?.let {
                        it.handler = Any()
                        it.epoch++
                    }
                    3 -> current?.let {
                        pending.addLast(Callback(it, it.handler, it.epoch))
                        if (pending.size > 128) pending.removeFirst()
                    }
                    4 -> if (pending.isNotEmpty()) {
                        val callback = pending.removeAt(random.nextInt(pending.size))
                        val target = callback.instance
                        val expected = current === target && !target.dead &&
                            target.handler === callback.handler && target.epoch == callback.epoch
                        var committed = false
                        target.guard.runIfCurrent {
                            if (isCurrentLifecycleCallback(
                                    target.handler, target.epoch, callback.handler, callback.epoch,
                                    ownership.isOwner(target.id)
                                )) committed = true
                        }
                        assertEquals("seed=$seed step=$step", expected, committed)
                        if (committed) accepted++ else rejected++
                    }
                }
            }
            assertTrue("seed=$seed no accepted control", accepted > 0)
            assertTrue("seed=$seed insufficient stale cases", rejected > 100)
            println("LIFECYCLE_EPOCH seed=$seed events=10000 accepted=$accepted rejected=$rejected scope=jvm-guards")
        }
    }
}
