/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose

import android.app.Activity
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.rule.ActivityTestRule
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("PLUGIN_WARNING")
class EffectsTests {

    @get:Rule
    val activityRule = ActivityTestRule(TestActivity::class.java)

    @Test
    fun testMemoization1() {
        var inc = 0

        compose {
            +memo { ++inc }
        }.then { _ ->
            assertEquals(1, inc)
        }.then { _ ->
            assertEquals(1, inc)
        }
    }

    @Test
    fun testMemoization2() {
        var calculations = 0
        var compositions = 0
        var calculation = 0
        var key = 0

        compose {
            compositions++
            calculation = +memo(key) { 100 * ++calculations }
        }.then { _ ->
            assertEquals(1, calculations)
            assertEquals(100, calculation)
            assertEquals(1, compositions)
        }.then { _ ->
            assertEquals(1, calculations)
            assertEquals(100, calculation)
            assertEquals(2, compositions)
            key++
        }.then { _ ->
            assertEquals(2, calculations)
            assertEquals(200, calculation)
            assertEquals(3, compositions)
        }.then { _ ->
            assertEquals(2, calculations)
            assertEquals(200, calculation)
            assertEquals(4, compositions)
            key++
        }.then { _ ->
            assertEquals(3, calculations)
            assertEquals(300, calculation)
            assertEquals(5, compositions)
        }
    }

    @Test
    fun testState1() {
        val tv1Id = 100
        var inc = 0
        var local = State("invalid")

        compose {
            local = +state { "Hello world! ${inc++}" }
            composer.emit(
                168,
                { context -> TextView(context).apply { id = tv1Id } },
                { set(local.value) { text = it } }
            )
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals("Hello world! 0", helloText.text)
            assertEquals(local.value, helloText.text)
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals("Hello world! 0", helloText.text)
            assertEquals(local.value, helloText.text)

            local.value = "New string"
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals("New string", helloText.text)
            assertEquals(local.value, helloText.text)
        }
    }

    @Test
    fun testState2() {
        val tv1Id = 100
        val tv2Id = 200
        var local1 = State("invalid")
        var local2 = State("invalid")

        compose {
            local1 = +state { "First" }
            local2 = +state { "Second" }
            composer.emit(
                168,
                { context -> TextView(context).apply { id = tv1Id } },
                { set(local1.value) { text = it } }
            )
            composer.emit(
                169,
                { context -> TextView(context).apply { id = tv2Id } },
                { set(local2.value) { text = it } }
            )
        }.then { activity ->
            val tv1 = activity.findViewById(tv1Id) as TextView
            val tv2 = activity.findViewById(tv2Id) as TextView
            assertEquals("First", tv1.text)
            assertEquals("Second", tv2.text)
            assertEquals(local1.value, tv1.text)
            assertEquals(local2.value, tv2.text)
        }.then { activity ->
            val tv1 = activity.findViewById(tv1Id) as TextView
            val tv2 = activity.findViewById(tv2Id) as TextView
            assertEquals("First", tv1.text)
            assertEquals("Second", tv2.text)
            assertEquals(local1.value, tv1.text)
            assertEquals(local2.value, tv2.text)

            local1.value = "New First"
        }.then { activity ->
            val tv1 = activity.findViewById(tv1Id) as TextView
            val tv2 = activity.findViewById(tv2Id) as TextView
            assertEquals("New First", tv1.text)
            assertEquals("Second", tv2.text)
            assertEquals(local1.value, tv1.text)
            assertEquals(local2.value, tv2.text)
        }
    }

    @Test
    fun testPreCommit1() {
        var mount = true

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        @Composable
        fun Unmountable() {
            log("Unmountable:start")
            +onPreCommit {
                log("onPreCommit")
                onDispose {
                    log("onDispose")
                }
            }
            log("Unmountable:end")
        }

        compose {
            with(composer) {
                log("compose:start")
                if (mount) {
                    call(
                        168,
                        { true },
                        { @Suppress("PLUGIN_ERROR") Unmountable() }
                    )
                }
                log("compose:end")
            }
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "compose:start",
                    "Unmountable:start",
                    "Unmountable:end",
                    "compose:end",
                    "onPreCommit"
                ),
                logHistory
            )
            mount = false
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "compose:start",
                    "Unmountable:start",
                    "Unmountable:end",
                    "compose:end",
                    "onPreCommit",
                    "compose:start",
                    "compose:end",
                    "onDispose"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testPreCommit2() {
        var mount = true

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        @Composable
        fun Unmountable() {
            +onPreCommit {
                log("onPreCommit:a2")
                onDispose {
                    log("onDispose:a2")
                }
            }
            +onPreCommit {
                log("onPreCommit:b2")
                onDispose {
                    log("onDispose:b2")
                }
            }
        }

        compose {
            +onPreCommit {
                log("onPreCommit:a1")
                onDispose {
                    log("onDispose:a1")
                }
            }
            if (mount) {
                composer.call(
                    168,
                    { true },
                    { @Suppress("PLUGIN_ERROR") Unmountable() }
                )
            }
            +onPreCommit {
                log("onPreCommit:b1")
                onDispose {
                    log("onDispose:b1")
                }
            }
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit:a1",
                    "onPreCommit:a2",
                    "onPreCommit:b2",
                    "onPreCommit:b1"
                ),
                logHistory
            )
            mount = false
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit:a1",
                    "onPreCommit:a2",
                    "onPreCommit:b2",
                    "onPreCommit:b1",
                    "recompose",
                    "onDispose:b2",
                    "onDispose:a2",
                    "onDispose:b1",
                    "onDispose:a1",
                    "onPreCommit:a1",
                    "onPreCommit:b1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testPreCommit3() {
        var x = 0

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        compose {
            +onPreCommit {
                val y = x++
                log("onPreCommit:$y")
                onDispose {
                    log("dispose:$y")
                }
            }
        }.then { _ ->
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit:0",
                    "recompose",
                    "dispose:0",
                    "onPreCommit:1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testPreCommit31() {
        var a = 0
        var b = 0

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        compose {
            +onPreCommit {
                val y = a++
                log("onPreCommit a:$y")
                onDispose {
                    log("dispose a:$y")
                }
            }
            +onPreCommit {
                val y = b++
                log("onPreCommit b:$y")
                onDispose {
                    log("dispose b:$y")
                }
            }
        }.then { _ ->
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit a:0",
                    "onPreCommit b:0",
                    "recompose",
                    "dispose b:0",
                    "dispose a:0",
                    "onPreCommit a:1",
                    "onPreCommit b:1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testPreCommit4() {
        var x = 0
        var key = 123

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        compose {
            +onPreCommit(key) {
                val y = x++
                log("onPreCommit:$y")
                onDispose {
                    log("dispose:$y")
                }
            }
        }.then { _ ->
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit:0",
                    "recompose"
                ),
                logHistory
            )
            log("recompose (key -> 345)")
            key = 345
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit:0",
                    "recompose",
                    "recompose (key -> 345)",
                    "dispose:0",
                    "onPreCommit:1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testPreCommit5() {
        var a = 0
        var b = 0
        var c = 0

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        @Composable
        fun Sub() {
            +onPreCommit {
                val y = c++
                log("onPreCommit c:$y")
                onDispose {
                    log("dispose c:$y")
                }
            }
        }

        compose {
            +onPreCommit {
                val y = a++
                log("onPreCommit a:$y")
                onDispose {
                    log("dispose a:$y")
                }
            }

            +onPreCommit {
                val y = b++
                log("onPreCommit b:$y")
                onDispose {
                    log("dispose b:$y")
                }
            }

            composer.call(
                1234,
                { true },
                { @Suppress("PLUGIN_ERROR") Sub() }
            )
        }.then { _ ->
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "onPreCommit a:0",
                    "onPreCommit b:0",
                    "onPreCommit c:0",
                    "recompose",
                    "dispose c:0",
                    "dispose b:0",
                    "dispose a:0",
                    "onPreCommit a:1",
                    "onPreCommit b:1",
                    "onPreCommit c:1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testOnDispose1() {
        var mount = true

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        fun DisposeLogger(msg: String) {
            +onDispose { log(msg) }
        }

        compose {
            with(composer) {
                call(
                    168,
                    { true },
                    { @Suppress("PLUGIN_ERROR") DisposeLogger(msg = "onDispose:1") }
                )
                if (mount) {
                    call(
                        169,
                        { true },
                        { @Suppress("PLUGIN_ERROR") DisposeLogger(msg = "onDispose:2") }
                    )
                }
            }
        }.then { _ ->
            assertArrayEquals(
                emptyList<String>(),
                logHistory
            )
            mount = false
            log("recompose")
        }.then { _ ->
            assertArrayEquals(
                listOf("recompose", "onDispose:2"),
                logHistory
            )
        }
    }

    @Test
    fun testOnCommit1() {
        var mount = true

        val logHistory = mutableListOf<String>()
        fun log(x: String) = logHistory.add(x)

        @Composable
        fun Unmountable() {
            log("Unmountable:start")
            +onCommit {
                log("onCommit 1")
                onDispose {
                    log("onDispose 1")
                }
            }
            +onPreCommit {
                log("onPreCommit 2")
                onDispose {
                    log("onDispose 2")
                }
            }
            +onCommit {
                log("onCommit 3")
                onDispose {
                    log("onDispose 3")
                }
            }
            log("Unmountable:end")
        }

        compose {
            with(composer) {
                log("compose:start")
                if (mount) {
                    call(
                        168,
                        { true },
                        { @Suppress("PLUGIN_ERROR") Unmountable() }
                    )
                }
                log("compose:end")
            }
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "compose:start",
                    "Unmountable:start",
                    "Unmountable:end",
                    "compose:end",
                    "onPreCommit 2",
                    "onCommit 1",
                    "onCommit 3"
                ),
                logHistory
            )
            mount = false
        }.then { _ ->
            assertArrayEquals(
                listOf(
                    "compose:start",
                    "Unmountable:start",
                    "Unmountable:end",
                    "compose:end",
                    "onPreCommit 2",
                    "onCommit 1",
                    "onCommit 3",
                    "compose:start",
                    "compose:end",
                    "onDispose 3",
                    "onDispose 2",
                    "onDispose 1"
                ),
                logHistory
            )
        }
    }

    @Test
    fun testAmbient1() {
        val tv1Id = 100

        val Foo = Ambient.of<String>()
        var current = "Hello World"

        @Composable
        fun Bar() {
            composer.call(
                21323,
                { true },
                {
                    @Suppress("PLUGIN_ERROR")
                    (Observe {
                        val foo = +ambient(Foo)
                        composer.emit(
                            168,
                            { context -> TextView(context).apply { id = tv1Id } },
                            { set(foo) { text = it } }
                        )
                    })
                }
            )
        }

        compose {
            with(composer) {
                provideAmbient(Foo, current) {
                    call(
                        123,
                        { false },
                        { @Suppress("PLUGIN_ERROR") Bar() }
                    )
                }
            }
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals(current, helloText.text)
            current = "abcd"
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals(current, helloText.text)
        }
    }

    @Test
    @MediumTest
    fun testAmbient2() {
        val MyAmbient = Ambient.of<Int> { throw Exception("not set") }

        var requestRecompose: (() -> Unit)? = null
        var buttonCreated = false
        var ambientValue = 1

        fun SimpleComposable2() {
            Observe {
                with(composer) {
                    val value = +ambient(MyAmbient)
                    emit(534, { context -> TextView(context) }, {
                        set("$value") { text = it }
                    })
                }
            }
        }

        fun SimpleComposable() {
            composer.call(531, { true }) {
                Recompose {
                    requestRecompose = it
                    composer.provideAmbient(MyAmbient, ambientValue++) {
                        composer.call(523, { false }) { SimpleComposable2() }
                        composer.emitView(525) { context ->
                            Button(context).also {
                                buttonCreated = true
                            }
                        }
                    }
                }
            }
        }

        fun Root() {
            with(composer) {
                call(547, { false }) {
                    SimpleComposable()
                }
            }
        }

        compose(manualRecompose = true) {
            with(composer) {
                call(556, { false }) {
                    Root()
                }
            }
        }.then {
            assertTrue("Expected button to be created", buttonCreated)
            buttonCreated = false
            requestRecompose?.invoke()
        }.then {
            assertFalse("Expected button to not be recreated", buttonCreated)
        }
    }

    @Test
    @MediumTest
    fun testAmbient_RecomposeScope() {
        val MyAmbient = Ambient.of<Int> { throw Exception("not set") }

        var requestRecompose: (() -> Unit)? = null
        var buttonCreated = false
        var componentComposed = false
        var ambientValue = 1

        fun SimpleComposable2() {
            with(composer) {
                composer.startRestartGroup(712)
                componentComposed = true
                val value = +ambient(MyAmbient)
                emit(534, { context -> TextView(context) }, {
                    set("$value") { text = it }
                })
                composer.endRestartGroup()?.updateScope { SimpleComposable2() }
            }
        }

        fun SimpleComposable() {
            composer.call(531, { true }) {
                Recompose {
                    requestRecompose = it
                    composer.provideAmbient(MyAmbient, ambientValue++) {
                        composer.call(523, { false }) { SimpleComposable2() }
                        composer.emitView(525) { context ->
                            Button(context).also {
                                buttonCreated = true
                            }
                        }
                    }
                }
            }
        }

        fun Root() {
            with(composer) {
                call(547, { false }) {
                    SimpleComposable()
                }
            }
        }

        compose(manualRecompose = true) {
            with(composer) {
                call(556, { false }) {
                    Root()
                }
            }
        }.then {
            assertTrue("Expected component to be composed", componentComposed)
            assertTrue("Expected button to be created", buttonCreated)
            buttonCreated = false
            componentComposed = false
            requestRecompose?.invoke()
        }.then {
            assertTrue("Expected component to be composed", componentComposed)
            assertFalse("Expected button to not be recreated", buttonCreated)
        }
    }

    @Test
    fun testUpdatedComposition() {
        val tv1Id = 100
        var inc = 0

        compose {
            val local = +state { "Hello world! ${inc++}" }
            composer.emit(
                168,
                { context -> TextView(context).apply { id = tv1Id } },
                { set(local.value) { text = it } }
            )
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals("Hello world! 0", helloText.text)
        }.then { activity ->
            val helloText = activity.findViewById(tv1Id) as TextView
            assertEquals("Hello world! 0", helloText.text)
        }
    }

    class CompositionTest(
        val composable: () -> Unit,
        private val manualRecompose: Boolean,
        private val activity: Activity
    ) {
        lateinit var doRecompose: () -> Unit
        inner class ActiveTest(private val activity: Activity) {
            fun then(block: (activity: Activity) -> Unit): ActiveTest {
                if (!manualRecompose) {
                    activity.uiThread {
                        doRecompose()
                    }
                }
                activity.waitForAFrame()
                activity.uiThread {
                    block(activity)
                }
                return this
            }
        }

        fun then(block: (activity: Activity) -> Unit): ActiveTest {
            activity.show {
                Recompose {
                    doRecompose = it
                    composable()
                }
            }
            activity.waitForAFrame()
            activity.uiThread {
                block(activity)
            }
            return ActiveTest(activity)
        }
    }

    fun compose(manualRecompose: Boolean = false, composable: () -> Unit) =
        CompositionTest(composable, manualRecompose, activityRule.activity)
}

fun <T> assertArrayEquals(
    expected: Collection<T>,
    actual: Collection<T>,
    transform: (T) -> String = { "$it" }
) {
    assertEquals(
        expected.joinToString("\n", transform = transform),
        actual.joinToString("\n", transform = transform)
    )
}