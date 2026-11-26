package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import maestro.FailurePrestate
import maestro.Point
import maestro.TreeNode
import maestro.ViewHierarchy
import java.awt.image.BufferedImage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString


class TestDebugReporterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `will delete old files`() {
        // Create directory structure, and an old test directory
        val oldDir = Files.createDirectories(tempDir.resolve(".maestro/tests/old"))
        Files.setLastModifiedTime(oldDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 15))

        // Initialise a new test reporter, which will create ./maestro/tests/<datestamp>
        TestDebugReporter.install(tempDir.pathString, false,false)

        // Run the deleteOldFiles method, which happens at the end of each test run
        // This should delete the 'old' directory created above
        TestDebugReporter.deleteOldFiles()
        assertThat(Files.exists(oldDir)).isFalse() // Verify that the old directory was deleted
        assertThat(TestDebugReporter.getDebugOutputPath().exists()).isTrue() // Verify that the logs from this run still exist
    }

    // norn#486
    private fun prestate(): FailurePrestate {
        val prestate = FailurePrestate()
        prestate.beginCommand("Scrolling DOWN until \"Aspirin\" is visible", "\"Aspirin\"", search = true)
        prestate.onSearchRead(ViewHierarchy(TreeNode(attributes = mutableMapOf("text" to "top"))))
        prestate.onSearchRead(ViewHierarchy(TreeNode(attributes = mutableMapOf("text" to "bottom"))))
        prestate.onPreTapScreenshot(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), Point(2, 2), null)
        return prestate
    }

    @Test
    fun `a failed flow writes its pre-failure state next to the failure screenshot`() {
        val out = Files.createDirectories(tempDir.resolve("failed"))

        TestDebugReporter.saveFlow("flow.yaml", FlowDebugOutput(prestate = prestate().snapshot()), out, shardIndex = 1)

        val names = out.toFile().list()!!.sorted()
        assertThat(names).hasSize(4)
        assertThat(names.all { it.startsWith("prestate-shard-2-") && it.contains("-(flow.yaml).") }).isTrue()
        assertThat(names.count { it.startsWith("prestate-shard-2-hierarchy-initial-") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("prestate-shard-2-hierarchy-last-") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("prestate-shard-2-tap-") && it.endsWith(".png") }).isEqualTo(1)
        assertThat(names.count { it.startsWith("prestate-shard-2-tap-") && it.endsWith(".json") }).isEqualTo(1)
    }

    @Test
    fun `a passing flow writes no pre-failure state`() {
        val out = Files.createDirectories(tempDir.resolve("passed"))
        prestate()   // samples were taken, but no command failed, so nothing was handed over

        TestDebugReporter.saveFlow("flow.yaml", FlowDebugOutput(), out)

        assertThat(out.toFile().list()!!.filter { it.startsWith("prestate-") }).isEmpty()
    }

}
