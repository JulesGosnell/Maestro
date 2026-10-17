package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.test.drivers.FakeTimer
import maestro.utils.MaestroTimer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths

/**
 * smithr#1184: condition lookups (assertVisible, assertNotVisible, runFlow when, repeat while)
 * take the near-side path when the driver advertises it and the full dump when it does not —
 * with the same outcome and the same interactions either way.
 */
class NearSideIntegrationTest {

    private val fakeTimer = FakeTimer()

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc(fakeTimer.timer())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `assertVisible by id`(nearSide: Boolean) {
        val driver = driver(nearSide) {
            element {
                id = "element_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        run(driver, "001_assert_visible_by_id")

        driver.assertNoInteraction()
        assertPath(driver, nearSide)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `assertVisible fails when no element matches`(nearSide: Boolean) {
        val driver = driver(nearSide) {
            element {
                id = "another_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        val failure = runCatching { run(driver, "004_assert_no_visible_element_with_id") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MaestroException.AssertionFailure::class.java)
        assertPath(driver, nearSide)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `runFlow when visible`(nearSide: Boolean) {
        val driver = driver(nearSide) {
            val indicator = element {
                text = "Not Clicked"
                bounds = Bounds(0, 100, 0, 200)
            }

            element {
                text = "button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    indicator.text = "Clicked"
                }
            }
        }

        run(driver, "049_run_flow_conditionally", mapOf("NOT_CLICKED" to "Not Clicked"))

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), 1)
        assertPath(driver, nearSide)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `repeat while notVisible`(nearSide: Boolean) {
        val driver = driver(nearSide) {
            var counter = 0

            val counterView = element {
                text = "Value 0"
                bounds = Bounds(0, 100, 100, 100)
            }

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    counter++
                    counterView.text = "Value $counter"
                }
            }
        }

        run(driver, "075_repeat_while")

        driver.assertEventCount(FakeDriver.Event.Tap(maestro.Point(50, 50)), expectedCount = 3)
        assertPath(driver, nearSide)
    }

    private fun assertPath(driver: FakeDriver, nearSide: Boolean) {
        if (nearSide) {
            assertThat(driver.nearSideQueries).isGreaterThan(0)
        } else {
            assertThat(driver.nearSideQueries).isEqualTo(0)
        }
    }

    private fun run(driver: FakeDriver, caseName: String, env: Map<String, String> = emptyMap()) {
        Maestro(driver).use {
            runBlocking {
                Orchestra(it, lookupTimeoutMs = 0L, optionalLookupTimeoutMs = 0L).runFlow(readCommands(caseName, env))
            }
        }
    }

    private fun driver(nearSide: Boolean, builder: FakeLayoutElement.() -> Unit): FakeDriver {
        val driver = FakeDriver()
        driver.nearSide = nearSide
        driver.setLayout(FakeLayoutElement().apply { builder() })
        driver.open()
        return driver
    }

    private fun readCommands(caseName: String, env: Map<String, String>): List<MaestroCommand> {
        val resource = javaClass.classLoader.getResource("$caseName.yaml")
            ?: throw IllegalArgumentException("File $caseName.yaml not found")
        val flowPath = Paths.get(resource.toURI())
        return YamlCommandReader.readCommands(flowPath)
            .withEnv(env.withDefaultEnvVars(flowPath.toFile()))
    }

}
