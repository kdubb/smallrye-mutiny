package io.smallrye.mutiny.coroutines

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.subscription.MultiEmitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

@ExperimentalCoroutinesApi
class MultiAsFlowTest {

    @Test
    fun `test immediate items`() {
        runBlocking {
            // Given
            val items = arrayOf(1, 2, 3)
            val multi = Multi.createFrom().items(*items)

            // When
            val eventItems = multi.asFlow().toList()

            // Then
            assertThat(eventItems).containsExactly(*items)
        }
    }

    @Test
    fun `test immediate failure`() {
        runBlocking {
            // Given
            val multi = Multi.createFrom().failure<Any>(Exception("kaboom"))

            // When & Then
            val flow = multi.asFlow()
            assertFailsWith<Exception>("kaboom") {
                flow.toList()
            }
        }
    }

    @Test
    fun `test delayed item emits`() {
        // Given
        val items = arrayOf(5, 23, 42)
        val delayMillis = 50L
        val multi = Multi.createFrom().emitter { em: MultiEmitter<in Int> ->
            GlobalScope.launch {
                items.forEach {
                    delay(delayMillis)
                    em.emit(it)
                }
                em.complete()
            }
        }

        // When
        val (beforeSubscribe, eventItems, afterCompletion) = runBlocking {
            Triple(Instant.now(), multi.asFlow().toList(), Instant.now())
        }

        // Then
        assertThat(eventItems).containsExactly(*items)
        assertTrue(Duration.between(beforeSubscribe, afterCompletion) >= Duration.ofMillis(items.size * delayMillis))
    }

    @Test
    fun `test delayed failure`() {
        // Given
        val delayMillis = 100L
        val multi = Multi.createFrom().emitter { em: MultiEmitter<in Int> ->
            GlobalScope.launch {
                delay(delayMillis)
                em.fail(Exception("boom"))
            }
        }

        // When & Then
        runBlocking {
            val flow = multi.asFlow()
            val durationMillis = measureTimeMillis {
                assertFailsWith<Exception>("boom") {
                    flow.toList()
                }
            }
            assertThat(durationMillis).isGreaterThanOrEqualTo(delayMillis)
        }
    }

    @Test
    fun `test immediate completion`() {
        runBlocking {
            // Given
            val multi = Multi.createFrom().empty<Any>()

            // When
            val eventItems = multi.asFlow().toList()

            // Then
            assertThat(eventItems).isEmpty()
        }
    }

    @Test
    fun `test failure after emitting an item`() {
        // Given
        val emitter = AtomicReference<MultiEmitter<in Int>>()
        fun emit() = emitter.get().emit(5)
        fun fail() = emitter.get().fail(IllegalStateException("boom"))

        val multi = Multi.createFrom().emitter { em: MultiEmitter<in Int> ->
            emitter.set(em)
        }

        runBlocking {
            val flow = multi.asFlow()
            val eventItems = mutableListOf<Int>()

            // When
            val assertJob = async {
                assertFails {
                    flow.collect { eventItems.add(it) }
                }
            }
            GlobalScope.launch {
                // yield thread and wait shortly for ensuring emitter got set
                delay(100)
                emit()
                emit()
                fail()
            }

            // Then
            assertThat(assertJob.await()).hasMessage("boom").isInstanceOf(IllegalStateException::class.java)
            assertThat(eventItems).containsExactly(5, 5)

        }
    }

    @Test
    fun `test coroutine cancellation before first item`() {
        // Given
        val multi = Multi.createFrom().ticks()
            .startingAfter(Duration.ofMillis(150))
            .every(Duration.ofMillis(50))

        // When
        var retrieveError: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    multi.asFlow().toList()
                    fail()
                } catch (th: Throwable) {
                    retrieveError = th
                }
            }
            delay(50)
            job.cancel(CancellationException("abort"))
        }
        Thread.sleep(350)

        // Then
        assertThat(retrieveError).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `test coroutine cancellation during item delivery`() {
        // Given
        val multi = Multi.createFrom().ticks().every(Duration.ofMillis(1))
        val counter = AtomicInteger()

        // When
        var retrieveError: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    multi.asFlow().collect {
                        counter.incrementAndGet()
                    }
                    fail()
                } catch (th: Throwable) {
                    retrieveError = th
                }
            }
            delay(100)
            job.cancel(CancellationException("abort"))
        }
        Thread.sleep(100)

        // Then
        assertThat(counter.get()).isGreaterThan(0)
        assertThat(retrieveError).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `test coroutine timeout when nothing happens`() {
        // Given
        val multi = Multi.createFrom().nothing<Any>()

        // When & Then
        assertFailsWith<TimeoutCancellationException> {
            runBlocking {
                withTimeout(100) {
                    multi.asFlow().toList()
                }
            }
        }
    }

    @Test
    fun `test empty Multi`() {
        runBlocking {
            // Given
            val multi = Multi.createFrom().empty<Any>()

            // When
            val items = multi.asFlow().toList()

            // Then
            assertThat(items).isEmpty()
        }
    }

    @Test
    fun `test null item`() {
        runBlocking {
            // Given
            val multi = Multi.createFrom().item { null }

            // When
            val items = multi.asFlow().toList()

            /// Then
            assertThat(items).hasSize(0)
        }
    }

    @Test
    fun `test buffered flow`() {
        runBlocking {
            // Given
            val items = (1..10_000).toList()
            val multi = Multi.createFrom().iterable(items)

            // When
            val flow = multi.asFlow()
            val eventItems = flow.buffer(5).toList()

            // Then
            assertThat(eventItems).containsExactlyElementsOf((1..10_000).toList())
        }
    }

    @Test
    fun `verify that buffer overflow with slow consumer works well`() {
        // Given
        val multi = Multi.createFrom().ticks().every(Duration.ofMillis(1))

        // When
        val ticks = AtomicInteger()
        assertFailsWith<TimeoutCancellationException> {
            runBlocking {
                withTimeout(300) {
                    multi.asFlow().buffer(5).collect {
                        ticks.incrementAndGet()
                        delay(50)
                    }
                }
            }
        }

        // Then
        assertThat(ticks.get()).isBetween(4, 6)
    }

    @Test
    fun `verify that Flow abortion cancels the subscription on a ticking Multi`() {
        // Given
        val multi = Multi.createFrom().ticks().every(Duration.ofMillis(1))

        // When
        val tick = runBlocking {
            multi.asFlow().first()
        }

        // Then
        assertThat(tick).isEqualTo(0)
    }

    @Test
    fun `verify that Flow abortion cancels the subscription on an emitting Multi`() {
        // Given
        val multi = Multi.createFrom().emitter<Int> { em ->
            (1..100).forEach {
                if (!em.isCancelled) {
                    Thread.sleep(500)
                    em.emit(it)
                }
            }
        }

        // When
        val start = System.currentTimeMillis()
        val tick = runBlocking {
            multi.asFlow().first()
        }

        // Then
        assertThat(tick).isEqualTo(1)
        assertThat(System.currentTimeMillis() - start).isLessThan(1500)
    }

    @Test
    fun `verify that Multi emission is not blocked by the caller`() {
        // Given
        val multi = Multi.createFrom().items(3, 2, 1)

        // When
        val list = runBlocking(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
            multi.asFlow().toList()
        }

        // Then
        assertThat(list).containsExactly(3, 2, 1)
    }
}
