@file:Suppress("MatchingDeclarationName")

package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshot
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.countKeptNodes
import com.danielealbano.androidremotecontrolmcp.services.accessibility.formatMultiWindowPage
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// get_screen_state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MCP tool handler for `get_screen_state`.
 *
 * Returns consolidated screen state: app metadata, screen info, and a compact
 * flat TSV-formatted list of UI elements. Optionally includes a low-resolution screenshot.
 *
 * Replaces: get_accessibility_tree, capture_screenshot, get_current_app, get_screen_info.
 */
class GetScreenStateHandler
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val screenCaptureProvider: ScreenCaptureProvider,
        private val compactTreeFormatter: CompactTreeFormatter,
        private val screenshotAnnotator: ScreenshotAnnotator,
        private val screenshotEncoder: ScreenshotEncoder,
        private val nodeCache: AccessibilityNodeCache,
        private val screenStateSnapshotCache: ScreenStateSnapshotCache,
        private val webViewNodeMerger: WebViewNodeMerger,
    ) {
        @Volatile private var includeScreenshotEnabled: Boolean = true

        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val includeScreenshot = parseIncludeScreenshot(arguments)
            val annotateElements = parseAnnotateElements(arguments)
            val cursorElement = arguments?.get("cursor")
            // Absent, JSON null, or a blank string ⇒ fresh cursorless capture (settled behavior 1).
            // A present, non-blank value (INCLUDING a non-primitive object/array) ⇒ paged path,
            // where an unusable value yields INVALID_CURSOR_MESSAGE guidance rather than throwing.
            val isFresh =
                cursorElement == null ||
                    cursorElement is JsonNull ||
                    ((cursorElement as? JsonPrimitive)?.contentOrNull?.isBlank() == true)
            return if (isFresh) {
                handleFreshRequest(includeScreenshot, annotateElements)
            } else {
                McpToolUtils.untrustedTextResult(buildPagedText(cursorElement, includeScreenshot))
            }
        }

        private fun parseIncludeScreenshot(arguments: JsonObject?): Boolean =
            if (includeScreenshotEnabled) {
                arguments?.get("include_screenshot")?.jsonPrimitive?.booleanOrNull ?: false
            } else {
                false
            }

        /**
         * Whether to draw the Set-of-Mark node boxes on the returned screenshot.
         *
         * DEFAULTS TO TRUE, and that is the whole compatibility story: the annotations exist for a
         * vision model that has to point at an element by id, so every existing agent caller keeps
         * the pixels it was built against without changing a line. A HUMAN viewer — the console's
         * screenshot and its drive frames — passes `false` and gets the screen as a person would
         * see it. The node ids in the accompanying TEXT are unaffected either way, so the agent's
         * addressing survives even when the image is clean.
         */
        private fun parseAnnotateElements(arguments: JsonObject?): Boolean =
            arguments?.get("annotate_elements")?.jsonPrimitive?.booleanOrNull ?: true

        private suspend fun handleFreshRequest(
            includeScreenshot: Boolean,
            annotateElements: Boolean,
        ): CallToolResult {
            // getFreshWindows clears the framework accessibility cache before reading (see there),
            // so this fresh capture — and the node cache it populates for element/action tools —
            // round-trips live even for stale-prone WebView content.
            // The node cache (used by element/action tools) is populated by getFreshWindows from the
            // original tree; the merge only collapses the tree shown to the LLM. Merged anchors keep
            // their original ids, so taps still resolve.
            val result = webViewNodeMerger.merge(getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache))
            val screenInfo = accessibilityServiceProvider.getScreenInfo()
            val totalKept = compactTreeFormatter.countKeptNodes(result)
            val totalPages = ceilDiv(totalKept, CompactTreeFormatter.PAGE_SIZE)
            val compactOutput = buildFreshPageText(result, screenInfo, totalKept, totalPages)
            Log.d(
                TAG,
                "get_screen_state: includeScreenshot=$includeScreenshot annotate=$annotateElements pages=$totalPages",
            )
            return if (includeScreenshot) {
                buildScreenshotResult(result, screenInfo, compactOutput, annotateElements)
            } else {
                McpToolUtils.untrustedTextResult(compactOutput)
            }
        }

        private fun buildFreshPageText(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            totalKept: Int,
            totalPages: Int,
        ): String =
            if (totalPages <= 1) {
                screenStateSnapshotCache.clear()
                compactTreeFormatter.formatMultiWindow(result, screenInfo)
            } else {
                val snapshot =
                    ScreenStateSnapshot(
                        System.currentTimeMillis().toString(CURSOR_RADIX),
                        result,
                        screenInfo,
                        totalKept,
                        totalPages,
                    )
                screenStateSnapshotCache.store(snapshot)
                compactTreeFormatter.formatMultiWindowPage(snapshot, 1)
            }

        private fun buildPagedText(
            cursorElement: JsonElement?,
            includeScreenshot: Boolean,
        ): String {
            // Non-primitive (object/array) ⇒ contentOrNull is null ⇒ parsed is null ⇒ invalid-cursor guidance.
            val parsed = (cursorElement as? JsonPrimitive)?.contentOrNull?.let { parseCursor(it) }
            val snapshot = parsed?.let { screenStateSnapshotCache.get(it.first) }
            val body =
                when {
                    parsed == null -> {
                        INVALID_CURSOR_MESSAGE
                    }

                    snapshot == null -> {
                        SNAPSHOT_GONE_MESSAGE
                    }

                    parsed.second < 1 || parsed.second > snapshot.totalPages -> {
                        noSuchPageMessage(parsed.first, parsed.second, snapshot.totalPages)
                    }

                    else -> {
                        compactTreeFormatter.formatMultiWindowPage(snapshot, parsed.second)
                    }
                }
            // include_screenshot is ignored on ANY cursor call; when it was requested, append the
            // note to EVERY cursor response — valid page OR guidance — per agreed design point 9.
            return if (includeScreenshot) "$body\n$SCREENSHOT_ONLY_PAGE1_NOTE" else body
        }

        /**
         * Captures, annotates, and encodes the screenshot, returning a text+image result.
         *
         * NOTE: There is an inherent timing gap between tree parsing and screenshot capture.
         * If the UI changes in between, bounding boxes may reference stale element positions.
         * Atomic capture is not possible with the current Android accessibility APIs.
         */
        @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
        private suspend fun buildScreenshotResult(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            compactOutput: String,
            annotateElements: Boolean,
        ): CallToolResult {
            if (!screenCaptureProvider.isScreenCaptureAvailable()) {
                throw McpToolException.PermissionDenied(
                    "Screen capture not available. Please enable the accessibility " +
                        "service in Android Settings.",
                )
            }

            val bitmapResult =
                screenCaptureProvider.captureScreenshotBitmap(
                    maxWidth = SCREENSHOT_MAX_SIZE,
                    maxHeight = SCREENSHOT_MAX_SIZE,
                )
            val resizedBitmap =
                bitmapResult.getOrElse { exception ->
                    Log.e(TAG, "Screenshot capture failed", exception)
                    throw McpToolException.ActionFailed(
                        "Screenshot capture failed",
                    )
                }

            var annotatedBitmap: Bitmap? = null
            try {
                // A clean capture skips the annotator ENTIRELY rather than drawing and discarding:
                // annotate() allocates a full mutable ARGB_8888 copy of the bitmap, so not calling
                // it is also the cheaper path for the viewer that asks for it most often.
                val bitmapToEncode =
                    if (annotateElements) {
                        val onScreenElements = collectOnScreenElements(result.windows)
                        screenshotAnnotator
                            .annotate(
                                resizedBitmap,
                                onScreenElements,
                                screenInfo.width,
                                screenInfo.height,
                            ).also { annotatedBitmap = it }
                    } else {
                        resizedBitmap
                    }

                val screenshotData =
                    screenshotEncoder.bitmapToScreenshotData(
                        bitmapToEncode,
                        ScreenCaptureProvider.DEFAULT_QUALITY,
                    )

                return McpToolUtils.untrustedTextAndImageResult(
                    compactOutput,
                    screenshotData.data,
                    "image/jpeg",
                )
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot annotation failed", e)
                throw McpToolException.ActionFailed(
                    "Screenshot annotation failed",
                )
            } finally {
                annotatedBitmap?.recycle()
                resizedBitmap.recycle()
            }
        }

        /**
         * Collects elements from all windows that should be annotated on the screenshot:
         * nodes that pass the formatter's keep filter AND are visible (on-screen).
         */
        private fun collectOnScreenElements(windows: List<WindowData>): List<AccessibilityNodeData> {
            val result = mutableListOf<AccessibilityNodeData>()
            for (windowData in windows) {
                collectOnScreenElementsFromTree(windowData.tree, result)
            }
            return result
        }

        private fun collectOnScreenElementsFromTree(
            node: AccessibilityNodeData,
            result: MutableList<AccessibilityNodeData>,
        ) {
            if (compactTreeFormatter.shouldKeepNode(node) && node.visible) {
                result.add(node)
            }
            for (child in node.children) {
                collectOnScreenElementsFromTree(child, result)
            }
        }

        fun register(
            server: Server,
            toolNamePrefix: String,
            includeScreenshotParamEnabled: Boolean = true,
        ) {
            includeScreenshotEnabled = includeScreenshotParamEnabled
            server.addTool(
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Returns the current screen state: app info, screen dimensions, " +
                        "and a compact UI node list (text/desc truncated to 100 chars, use " +
                        "${toolNamePrefix}get_node_details to retrieve full values). Optionally includes a " +
                        "low-resolution screenshot (only request the screenshot when the node " +
                        "list alone is not sufficient to understand the screen layout). " +
                        "Includes a hierarchy section showing node nesting via indentation. " +
                        "Large screens are split into pages of 200 nodes: the response includes a " +
                        "'page:N/total' line and a cursor; call again with that cursor to fetch the " +
                        "next page. You do NOT need to fetch every page — stop once you have found " +
                        "what you need. A screenshot can only be requested on page 1 (without a cursor).",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                if (includeScreenshotParamEnabled) {
                                    putJsonObject("include_screenshot") {
                                        put("type", "boolean")
                                        put(
                                            "description",
                                            "Include a low-resolution screenshot. " +
                                                "Only request when the UI node list is not sufficient.",
                                        )
                                        put("default", false)
                                    }
                                    putJsonObject("annotate_elements") {
                                        put("type", "boolean")
                                        put(
                                            "description",
                                            "Draw numbered bounding boxes over the screenshot so " +
                                                "elements can be referenced visually. Set false for a " +
                                                "clean screenshot of the screen as a person sees it; " +
                                                "node ids in the text output are unaffected.",
                                        )
                                        put("default", true)
                                    }
                                }
                                putJsonObject("cursor") {
                                    put("type", "string")
                                    put(
                                        "description",
                                        "Pagination cursor from a previous response (format " +
                                            "\"<id>.<page>\"). Omit to capture a fresh screen state " +
                                            "starting at page 1. A cursor is tied to one screen " +
                                            "snapshot; if the screen changed you will be told to " +
                                            "request a fresh one.",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            const val TOOL_NAME = "get_screen_state"
            internal const val SCREENSHOT_MAX_SIZE = 700
            private const val TAG = "MCP:ScreenIntrospection"
            internal const val CURSOR_RADIX = 36
            internal const val INVALID_CURSOR_MESSAGE =
                "note:invalid cursor. Call get_screen_state without a cursor to get a fresh " +
                    "screen-state snapshot starting at page 1."
            internal const val SNAPSHOT_GONE_MESSAGE =
                "note:this screen-state snapshot is no longer available (the screen state was " +
                    "refreshed since this cursor was issued). Call get_screen_state without a " +
                    "cursor to get a fresh snapshot."
            internal const val SCREENSHOT_ONLY_PAGE1_NOTE =
                "note:a screenshot can only be requested on page 1 (call get_screen_state without " +
                    "a cursor / without specifying a page)."

            internal fun noSuchPageMessage(
                id: String,
                page: Int,
                totalPages: Int,
            ): String =
                "note:page $page does not exist — snapshot $id has $totalPages page(s). Call " +
                    "get_screen_state without a cursor for a fresh snapshot, or request a valid page."
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Registration function
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Registers all screen introspection tools with the given [Server].
 *
 * Called from [McpServerService.startServer] during server startup.
 */
@Suppress("LongParameterList")
fun registerScreenIntrospectionTools(
    server: Server,
    treeParser: AccessibilityTreeParser,
    accessibilityServiceProvider: AccessibilityServiceProvider,
    screenCaptureProvider: ScreenCaptureProvider,
    compactTreeFormatter: CompactTreeFormatter,
    screenshotAnnotator: ScreenshotAnnotator,
    screenshotEncoder: ScreenshotEncoder,
    nodeCache: AccessibilityNodeCache,
    screenStateSnapshotCache: ScreenStateSnapshotCache,
    webViewNodeMerger: WebViewNodeMerger,
    toolNamePrefix: String,
    perms: ToolPermissionsConfig,
) {
    if (perms.isToolEnabled(GetScreenStateHandler.TOOL_NAME)) {
        GetScreenStateHandler(
            treeParser,
            accessibilityServiceProvider,
            screenCaptureProvider,
            compactTreeFormatter,
            screenshotAnnotator,
            screenshotEncoder,
            nodeCache,
            screenStateSnapshotCache,
            webViewNodeMerger,
        ).register(
            server,
            toolNamePrefix,
            includeScreenshotParamEnabled = perms.isParamEnabled(GetScreenStateHandler.TOOL_NAME, "include_screenshot"),
        )
    }
}

/**
 * Parses a pagination cursor "<id>.<page>" into (id, page). Validates FORMAT only (id present, page
 * parses as an integer); the page RANGE (1..totalPages) is validated by the caller, so that page < 1
 * yields the no-such-page guidance — NOT the invalid-cursor guidance. Returns null if malformed.
 */
private fun parseCursor(cursor: String): Pair<String, Int>? {
    val dot = cursor.lastIndexOf('.')
    val page = cursor.substringAfterLast('.', "").toIntOrNull()
    return if (dot <= 0 || dot == cursor.length - 1 || page == null) {
        null
    } else {
        cursor.substring(0, dot) to page
    }
}

private fun ceilDiv(
    a: Int,
    b: Int,
): Int = if (a <= 0) 1 else (a + b - 1) / b
