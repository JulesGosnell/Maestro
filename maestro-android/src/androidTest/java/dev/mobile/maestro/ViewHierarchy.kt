package dev.mobile.maestro

import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.Xml
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ListView
import android.widget.TableLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mobile.maestro.AccessibilityNodeInfoExt.getHintOrFallback
import maestro.nearside.AndroidNodeMapping
import maestro.nearside.NearSideNode
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream

// Logic largely copied from AccessibilityNodeInfoDumper with some modifications
object ViewHierarchy {

    private const val LOGTAG = "Maestro"

    fun dump(
        device: UiDevice,
        uiAutomation: UiAutomation,
        out: OutputStream,
        toastNode: AccessibilityNodeInfo? = null
    ) {
        val displayRect = displayRect()

        val serializer = Xml.newSerializer()
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        serializer.attribute("", "rotation", Integer.toString(device.displayRotation))

        windowRoots(device, uiAutomation).forEach {
            dumpNodeRec(
                it,
                serializer,
                0,
                displayRect
            )
        }
        addToastNode(toastNode, serializer, displayRect)

        serializer.endTag("", "hierarchy")
        serializer.endDocument()
    }

    /**
     * The tree [dump] would produce, as the host would parse it, without serialising it: the
     * input to near-side queries (smithr#1184). Walks the same nodes with the same attributes.
     */
    fun snapshot(
        device: UiDevice,
        uiAutomation: UiAutomation,
        toastNode: AccessibilityNodeInfo? = null
    ): NearSideNode {
        val displayRect = displayRect()

        val children = windowRoots(device, uiAutomation).map {
            snapshotNodeRec(it, 0, displayRect)
        } + listOfNotNull(toastNode?.let { AndroidNodeMapping.element(toastAttributes(it, displayRect).toXmlMap(), emptyList()) })

        return AndroidNodeMapping.document(AndroidNodeMapping.hierarchyElement(children))
    }

    private fun displayRect(): Rect {
        val windowManager = InstrumentationRegistry.getInstrumentation()
            .context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    private fun windowRoots(device: UiDevice, uiAutomation: UiAutomation): List<AccessibilityNodeInfo> {
        return try {
            device.javaClass
                .getDeclaredMethod("getWindowRoots")
                .apply {
                    isAccessible = true
                }
                .let {
                    @Suppress("UNCHECKED_CAST")
                    it.invoke(device) as Array<AccessibilityNodeInfo>
                }
                .toList()
        } catch (e: Exception) {
            // Falling back to a public method if reflection fails
            Log.e(LOGTAG, "Unable to call getWindowRoots", e)
            listOf(uiAutomation.rootInActiveWindow)
        }
    }

    private fun addToastNode(
        toastNode: AccessibilityNodeInfo?,
        serializer: XmlSerializer,
        displayRect: Rect
    ) {
        if (toastNode != null) {
            serializer.apply {
                startTag("", "node")
                toastAttributes(toastNode, displayRect).forEach { (name, value) -> attribute("", name, value) }
                endTag("", "node")
            }
        }
    }

    private fun toastAttributes(toastNode: AccessibilityNodeInfo, displayRect: Rect): List<Pair<String, String?>> {
        return listOf(
            "index" to "0",
            "class" to toastNode.className.toString(),
            "text" to toastNode.text.toString(),
            "visible-to-user" to toastNode.isVisibleToUser.toString(),
            "checkable" to toastNode.isCheckable.toString(),
            "clickable" to toastNode.isClickable.toString(),
            "bounds" to getVisibleBoundsInScreen(toastNode, displayRect)?.toShortString(),
        )
    }

    private fun List<Pair<String, String?>>.toXmlMap(): Map<String, String> {
        return mapNotNull { (name, value) -> value?.let { name to it } }.toMap()
    }

    private val NAF_EXCLUDED_CLASSES = arrayOf(
        GridView::class.java.name, GridLayout::class.java.name,
        ListView::class.java.name, TableLayout::class.java.name
    )

    @Suppress("LongParameterList")
    @Throws(IOException::class)
    private fun dumpNodeRec(
        node: AccessibilityNodeInfo,
        serializer: XmlSerializer,
        index: Int,
        displayRect: Rect,
        insideWebView: Boolean = false,
    ) {
        serializer.startTag("", "node")
        nodeAttributes(node, index, displayRect).forEach { (name, value) ->
            serializer.attribute("", name, value)
        }
        forEachDumpedChild(node, insideWebView) { child, i, childInsideWebView ->
            dumpNodeRec(
                child,
                serializer, i,
                displayRect,
                childInsideWebView
            )
        }
        serializer.endTag("", "node")
    }

    private fun snapshotNodeRec(
        node: AccessibilityNodeInfo,
        index: Int,
        displayRect: Rect,
        insideWebView: Boolean = false,
    ): NearSideNode {
        val attributes = nodeAttributes(node, index, displayRect).toXmlMap()
        val children = mutableListOf<NearSideNode>()
        forEachDumpedChild(node, insideWebView) { child, i, childInsideWebView ->
            children += snapshotNodeRec(child, i, displayRect, childInsideWebView)
        }
        return AndroidNodeMapping.element(attributes, children)
    }

    /** The attributes of one `<node>`, in the order [dump] writes them. */
    private fun nodeAttributes(
        node: AccessibilityNodeInfo,
        index: Int,
        displayRect: Rect,
    ): List<Pair<String, String?>> {
        val attributes = mutableListOf<Pair<String, String?>>()
        if (!nafExcludedClass(node) && !nafCheck(node)) {
            attributes += "NAF" to java.lang.Boolean.toString(true)
        }

        attributes += "index" to Integer.toString(index)
        attributes += "hintText" to safeCharSeqToString(node.getHintOrFallback())
        attributes += "text" to safeCharSeqToString(node.text)
        attributes += "resource-id" to safeCharSeqToString(node.viewIdResourceName)
        attributes += "class" to safeCharSeqToString(node.className)
        attributes += "package" to safeCharSeqToString(node.packageName)
        attributes += "content-desc" to safeCharSeqToString(node.contentDescription)
        attributes += "checkable" to java.lang.Boolean.toString(node.isCheckable)
        attributes += "checked" to java.lang.Boolean.toString(node.isChecked)
        attributes += "clickable" to java.lang.Boolean.toString(node.isClickable)
        attributes += "enabled" to java.lang.Boolean.toString(node.isEnabled)
        attributes += "focusable" to java.lang.Boolean.toString(node.isFocusable)
        attributes += "focused" to java.lang.Boolean.toString(node.isFocused)
        attributes += "scrollable" to java.lang.Boolean.toString(node.isScrollable)
        attributes += "long-clickable" to java.lang.Boolean.toString(node.isLongClickable)
        attributes += "password" to java.lang.Boolean.toString(node.isPassword)
        attributes += "selected" to java.lang.Boolean.toString(node.isSelected)
        attributes += "visible-to-user" to java.lang.Boolean.toString(node.isVisibleToUser)
        attributes += "important-for-accessibility" to java.lang.Boolean.toString(node.isImportantForAccessibility)
        attributes += "error" to safeCharSeqToString(node.error)
        attributes += "bounds" to getVisibleBoundsInScreen(node, displayRect)?.toShortString()
        return attributes
    }

    /** The children [dump] descends into, with whether each is inside a WebView. */
    private fun forEachDumpedChild(
        node: AccessibilityNodeInfo,
        insideWebView: Boolean,
        block: (child: AccessibilityNodeInfo, index: Int, insideWebView: Boolean) -> Unit,
    ) {
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                // This condition is different from the original.
                // Original implementation has a bug where contents of a WebView sometimes reported as invisible.
                // This is a workaround for that bug.
                if (child.isVisibleToUser || insideWebView) {
                    block(child, i, insideWebView || child.className == "android.webkit.WebView")
                    child.recycle()
                } else {
                    Log.i(LOGTAG, "Skipping invisible child: $child")
                }
            } else {
                Log.i(LOGTAG, "Null child $i/$count, parent: $node")
            }
        }
    }

    /**
     * The list of classes to exclude my not be complete. We're attempting to
     * only reduce noise from standard layout classes that may be falsely
     * configured to accept clicks and are also enabled.
     *
     * @param node
     * @return true if node is excluded.
     */
    private fun nafExcludedClass(node: AccessibilityNodeInfo): Boolean {
        val className = safeCharSeqToString(node.className)
        for (excludedClassName in NAF_EXCLUDED_CLASSES) {
            if (className.endsWith(excludedClassName)) return true
        }
        return false
    }

    /**
     * We're looking for UI controls that are enabled, clickable but have no
     * text nor content-description. Such controls configuration indicate an
     * interactive control is present in the UI and is most likely not
     * accessibility friendly. We refer to such controls here as NAF controls
     * (Not Accessibility Friendly)
     *
     * @param node
     * @return false if a node fails the check, true if all is OK
     */
    private fun nafCheck(node: AccessibilityNodeInfo): Boolean {
        val isNaf = (node.isClickable && node.isEnabled
            && safeCharSeqToString(node.contentDescription).isEmpty()
            && safeCharSeqToString(node.text).isEmpty())
        return if (!isNaf) true else childNafCheck(node)

        // check children since sometimes the containing element is clickable
        // and NAF but a child's text or description is available. Will assume
        // such layout as fine.
    }

    /**
     * This should be used when it's already determined that the node is NAF and
     * a further check of its children is in order. A node maybe a container
     * such as LinearLayout and may be set to be clickable but have no text or
     * content description but it is counting on one of its children to fulfill
     * the requirement for being accessibility friendly by having one or more of
     * its children fill the text or content-description. Such a combination is
     * considered by this dumper as acceptable for accessibility.
     *
     * @param node
     * @return false if node fails the check.
     */
    @Suppress("ReturnCount")
    private fun childNafCheck(node: AccessibilityNodeInfo): Boolean {
        val childCount = node.childCount
        for (x in 0 until childCount) {
            val childNode = node.getChild(x)
            if (childNode == null) continue;
            if (!safeCharSeqToString(childNode.contentDescription).isEmpty()
                || !safeCharSeqToString(childNode.text).isEmpty()
            ) return true
            if (childNafCheck(childNode)) return true
        }
        return false
    }

    private fun safeCharSeqToString(cs: CharSequence?): String {
        return cs?.let { stripInvalidXMLChars(it) } ?: ""
    }

    @Suppress("ComplexCondition")
    private fun stripInvalidXMLChars(cs: CharSequence): String {
        val ret = StringBuffer()
        var ch: Char
        /* http://www.w3.org/TR/xml11/#charsets
        [#x1-#x8], [#xB-#xC], [#xE-#x1F], [#x7F-#x84], [#x86-#x9F], [#xFDD0-#xFDDF],
        [#x1FFFE-#x1FFFF], [#x2FFFE-#x2FFFF], [#x3FFFE-#x3FFFF],
        [#x4FFFE-#x4FFFF], [#x5FFFE-#x5FFFF], [#x6FFFE-#x6FFFF],
        [#x7FFFE-#x7FFFF], [#x8FFFE-#x8FFFF], [#x9FFFE-#x9FFFF],
        [#xAFFFE-#xAFFFF], [#xBFFFE-#xBFFFF], [#xCFFFE-#xCFFFF],
        [#xDFFFE-#xDFFFF], [#xEFFFE-#xEFFFF], [#xFFFFE-#xFFFFF],
        [#x10FFFE-#x10FFFF].
         */for (i in 0 until cs.length) {
            ch = cs[i]
            if (ch.code >= 0x1 && ch.code <= 0x8 || ch.code >= 0xB && ch.code <= 0xC || ch.code >= 0xE && ch.code <= 0x1F ||
                ch.code >= 0x7F && ch.code <= 0x84 || ch.code >= 0x86 && ch.code <= 0x9f ||
                ch.code >= 0xFDD0 && ch.code <= 0xFDDF || ch.code >= 0x1FFFE && ch.code <= 0x1FFFF ||
                ch.code >= 0x2FFFE && ch.code <= 0x2FFFF || ch.code >= 0x3FFFE && ch.code <= 0x3FFFF ||
                ch.code >= 0x4FFFE && ch.code <= 0x4FFFF || ch.code >= 0x5FFFE && ch.code <= 0x5FFFF ||
                ch.code >= 0x6FFFE && ch.code <= 0x6FFFF || ch.code >= 0x7FFFE && ch.code <= 0x7FFFF ||
                ch.code >= 0x8FFFE && ch.code <= 0x8FFFF || ch.code >= 0x9FFFE && ch.code <= 0x9FFFF ||
                ch.code >= 0xAFFFE && ch.code <= 0xAFFFF || ch.code >= 0xBFFFE && ch.code <= 0xBFFFF ||
                ch.code >= 0xCFFFE && ch.code <= 0xCFFFF || ch.code >= 0xDFFFE && ch.code <= 0xDFFFF ||
                ch.code >= 0xEFFFE && ch.code <= 0xEFFFF || ch.code >= 0xFFFFE && ch.code <= 0xFFFFF ||
                ch.code >= 0x10FFFE && ch.code <= 0x10FFFF
            ) ret.append(".") else ret.append(ch)
        }
        return ret.toString()
    }

    // This method is copied from AccessibilityNodeInfoHelper as-is
    private fun getVisibleBoundsInScreen(node: AccessibilityNodeInfo?, displayRect: Rect): Rect? {
        if (node == null) {
            return null
        }
        // targeted node's bounds
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        return if (nodeRect.intersect(displayRect)) {
            nodeRect
        } else {
            Rect()
        }
    }

}
