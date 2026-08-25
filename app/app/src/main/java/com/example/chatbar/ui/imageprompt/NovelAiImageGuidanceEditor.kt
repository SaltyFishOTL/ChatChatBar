package com.example.chatbar.ui.imageprompt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chatbar.domain.image.NovelAiGenerationAction
import com.example.chatbar.domain.image.NovelAiAspectRatio
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiImageSize
import com.example.chatbar.domain.image.NovelAiSizeTier
import com.example.chatbar.domain.image.NovelAiImageGuidanceDraft
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageUseTarget
import com.example.chatbar.domain.image.NovelAiPreciseReferenceType
import com.example.chatbar.domain.image.NovelAiReferenceMode
import com.example.chatbar.domain.image.NovelAiStudioAssetRef
import com.example.chatbar.domain.image.NovelAiVibeReferenceDraft
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private enum class GuidanceEditorTab(val label: String, val target: NovelAiImageUseTarget) {
    I2I("图生图", NovelAiImageUseTarget.IMAGE_TO_IMAGE),
    INPAINT("局部重绘", NovelAiImageUseTarget.INPAINT),
    PRECISE("精确", NovelAiImageUseTarget.PRECISE_REFERENCE),
    VIBE("氛围", NovelAiImageUseTarget.VIBE_REFERENCE)
}

private enum class CanvasTool(val label: String) {
    HARD("画笔"), SOFT("软笔"), ERASER("橡皮"), PICKER("吸色"), CROP("裁切")
}

private data class CanvasPoint(val x: Float, val y: Float, val pressure: Float = 1f)
private data class CanvasStroke(
    val points: List<CanvasPoint>,
    val color: Color,
    val width: Float,
    val alpha: Float,
    val erase: Boolean,
    val soft: Boolean
)
private data class CanvasLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val visible: Boolean = true,
    val fill: Color? = null,
    val strokes: List<CanvasStroke> = emptyList()
)
private data class CanvasEditorSnapshot(
    val base: Bitmap?,
    val mask: Bitmap?,
    val layers: List<CanvasLayer>,
    val sourceTransformed: Boolean,
    val maskContainsPaint: Boolean,
    val guidance: NovelAiImageGuidanceDraft
)

private data class CanvasTabState(
    val assetPath: String?,
    val maskPath: String?,
    val snapshot: CanvasEditorSnapshot,
    val undo: List<CanvasEditorSnapshot>,
    val redo: List<CanvasEditorSnapshot>,
    val activeLayer: Int,
    val tool: CanvasTool,
    val brushSize: Float,
    val opacity: Float,
    val brushColor: Color,
    val zoom: Float,
    val pan: Offset
)

@Composable
fun NovelAiImageGuidanceEditor(
    initial: NovelAiImageGuidanceDraft,
    model: NovelAiImageModel,
    onDismiss: () -> Unit,
    onPickImage: (NovelAiImageUseTarget) -> Unit,
    stagedAsset: Pair<NovelAiImageUseTarget, Pair<NovelAiStudioAssetRef, NovelAiStudioAssetRef?>>?,
    onConsumeStagedAsset: () -> Unit,
    onSaveBitmap: (Bitmap, Boolean, (NovelAiStudioAssetRef) -> Unit) -> Unit,
    onCheckpoint: (NovelAiImageGuidanceDraft) -> Unit,
    onSave: (NovelAiImageGuidanceDraft) -> Unit
) {
    var guidance by remember(initial) { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(defaultTab(initial, model)) }
    val layers = remember { mutableStateListOf(CanvasLayer(name = "图层 1")) }
    var activeLayer by remember { mutableIntStateOf(0) }
    val undo = remember { mutableStateListOf<CanvasEditorSnapshot>() }
    val redo = remember { mutableStateListOf<CanvasEditorSnapshot>() }
    var tool by remember { mutableStateOf(CanvasTool.HARD) }
    val activeStroke = remember { mutableStateListOf<CanvasPoint>() }
    var brushSize by remember { mutableFloatStateOf(28f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var brushColor by remember { mutableStateOf(Color.White) }
    var source by remember { mutableStateOf<ImageBitmap?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskOverlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var sourceTransformed by remember { mutableStateOf(false) }
    var showCanvasTools by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var lastGuidanceHistoryKey by remember { mutableStateOf<String?>(null) }
    var lastGuidanceHistoryAt by remember { mutableLongStateOf(0L) }
    val tabStates = remember { mutableMapOf<GuidanceEditorTab, CanvasTabState>() }
    val targetAsset = assetFor(tab, guidance)
    fun currentSnapshot() = CanvasEditorSnapshot(
        base = sourceBitmap,
        mask = maskSourceBitmap,
        layers = layers.toList(),
        sourceTransformed = sourceTransformed,
        maskContainsPaint = guidance.maskImage?.containsPaint == true,
        guidance = guidance
    )
    fun restoreSnapshot(snapshot: CanvasEditorSnapshot, restoreGuidance: Boolean = true) {
        sourceBitmap = snapshot.base
        source = snapshot.base?.asImageBitmap()
        maskSourceBitmap = snapshot.mask
        maskOverlay = snapshot.mask?.let(::createMaskOverlay)?.asImageBitmap()
        layers.replaceAll(snapshot.layers)
        activeLayer = activeLayer.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        sourceTransformed = snapshot.sourceTransformed
        guidance = if (restoreGuidance) {
            snapshot.guidance
        } else {
            guidance.copy(maskImage = guidance.maskImage?.copy(containsPaint = snapshot.maskContainsPaint))
        }
        activeStroke.clear()
    }
    fun pushCurrentUndo(resetGuidanceCoalescing: Boolean = true) {
        undo += currentSnapshot()
        trimEditorHistory(undo)
        redo.clear()
        if (resetGuidanceCoalescing) {
            lastGuidanceHistoryKey = null
            lastGuidanceHistoryAt = 0L
        }
    }
    fun applyGuidanceChange(historyKey: String, next: NovelAiImageGuidanceDraft) {
        if (next == guidance) return
        val now = System.currentTimeMillis()
        val coalesced = historyKey == lastGuidanceHistoryKey &&
            now - lastGuidanceHistoryAt <= GUIDANCE_HISTORY_COALESCE_MS &&
            undo.isNotEmpty()
        if (!coalesced) pushCurrentUndo(resetGuidanceCoalescing = false) else redo.clear()
        guidance = next
        lastGuidanceHistoryKey = historyKey
        lastGuidanceHistoryAt = now
    }
    fun currentTabState() = CanvasTabState(
        assetPath = targetAsset?.path,
        maskPath = guidance.maskImage?.path.takeIf { tab == GuidanceEditorTab.INPAINT },
        snapshot = currentSnapshot(),
        undo = undo.toList(),
        redo = redo.toList(),
        activeLayer = activeLayer,
        tool = tool,
        brushSize = brushSize,
        opacity = opacity,
        brushColor = brushColor,
        zoom = zoom,
        pan = pan
    )
    fun restoreTabState(state: CanvasTabState) {
        restoreSnapshot(state.snapshot, restoreGuidance = false)
        undo.replaceAll(state.undo)
        redo.replaceAll(state.redo)
        activeLayer = state.activeLayer.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        tool = state.tool
        brushSize = state.brushSize
        opacity = state.opacity
        brushColor = state.brushColor
        zoom = state.zoom
        pan = state.pan
    }
    suspend fun persistBitmap(bitmap: Bitmap, isMask: Boolean): NovelAiStudioAssetRef =
        suspendCancellableCoroutine { continuation ->
            onSaveBitmap(bitmap, isMask) { asset ->
                if (continuation.isActive) continuation.resume(asset)
            }
        }

    LaunchedEffect(stagedAsset) {
        val staged = stagedAsset ?: return@LaunchedEffect
        val (asset, mask) = staged.second
        tabStates.remove(tabForTarget(staged.first))
        val nextGuidance = when (staged.first) {
            NovelAiImageUseTarget.IMAGE_TO_IMAGE -> guidance.copy(
                action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                baseImage = asset,
                maskImage = null
            )
            NovelAiImageUseTarget.INPAINT -> guidance.copy(
                action = NovelAiGenerationAction.INPAINT,
                baseImage = asset,
                maskImage = mask
            )
            NovelAiImageUseTarget.PRECISE_REFERENCE -> guidance.copy(
                referenceMode = NovelAiReferenceMode.PRECISE,
                preciseReference = guidance.preciseReference.copy(asset = asset)
            )
            NovelAiImageUseTarget.VIBE_REFERENCE -> guidance.copy(
                referenceMode = NovelAiReferenceMode.VIBE,
                vibes = if (guidance.vibes.size < NovelAiImageGuidanceDraft.MAX_VIBES) {
                    guidance.vibes + NovelAiVibeReferenceDraft(asset = asset)
                } else guidance.vibes
            )
        }
        if (nextGuidance != guidance) {
            pushCurrentUndo()
            guidance = nextGuidance
        }
        onConsumeStagedAsset()
    }

    LaunchedEffect(guidance) { onCheckpoint(guidance) }

    LaunchedEffect(tab) {
        if (tab == GuidanceEditorTab.INPAINT && tool !in setOf(CanvasTool.HARD, CanvasTool.ERASER)) {
            tool = CanvasTool.HARD
        }
    }

    LaunchedEffect(targetAsset?.path, guidance.maskImage?.path, tab) {
        tabStates[tab]?.takeIf { cached ->
            cached.assetPath == targetAsset?.path &&
                cached.maskPath == guidance.maskImage?.path.takeIf { tab == GuidanceEditorTab.INPAINT }
        }?.let { cached ->
            restoreTabState(cached)
            return@LaunchedEffect
        }
        tabStates.remove(tab)
        val decoded = withContext(Dispatchers.IO) {
            val bitmap = targetAsset?.path?.let(::File)?.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            val mask = if (tab == GuidanceEditorTab.INPAINT) {
                guidance.maskImage?.path?.let(::File)?.takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.absolutePath) }
            } else null
            Triple(bitmap, mask, mask?.let(::createMaskOverlay))
        }
        sourceBitmap = decoded.first
        source = decoded.first?.asImageBitmap()
        maskSourceBitmap = decoded.second
        maskOverlay = decoded.third?.asImageBitmap()
        layers.clear()
        layers += CanvasLayer(name = if (tab == GuidanceEditorTab.INPAINT) "蒙版" else "图层 1")
        undo.clear()
        redo.clear()
        activeLayer = 0
        activeStroke.clear()
        zoom = 1f
        pan = Offset.Zero
        sourceTransformed = false
        brushColor = if (tab == GuidanceEditorTab.INPAINT) Color.White else Color.White
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        CbSurface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                CbTopBar(
                    title = "图像引导",
                    navigation = { CbIconButton(AppIcons.ArrowBack, "取消并返回", onDismiss) },
                    actions = {
                        CbIconButton(AppIcons.Undo, "撤销", {
                            undoCanvasState(currentSnapshot(), undo, redo)?.let { next ->
                                lastGuidanceHistoryKey = null
                                lastGuidanceHistoryAt = 0L
                                restoreSnapshot(next)
                            }
                        }, enabled = undo.isNotEmpty())
                        CbIconButton(AppIcons.Redo, "重做", {
                            redoCanvasState(currentSnapshot(), undo, redo)?.let { next ->
                                trimEditorHistory(undo)
                                lastGuidanceHistoryKey = null
                                lastGuidanceHistoryAt = 0L
                                restoreSnapshot(next)
                            }
                        }, enabled = redo.isNotEmpty())
                        CbButton(if (saving) "保存中" else "完成", {
                            if (saving) return@CbButton
                            tabStates[tab] = currentTabState()
                            saving = true
                            scope.launch {
                                try {
                                    var updated = guidance
                                    GuidanceEditorTab.entries.forEach { mode ->
                                    val state = tabStates[mode] ?: return@forEach
                                    val original = state.snapshot.base ?: return@forEach
                                    val hasLayerEdits = state.snapshot.layers.any {
                                        it.fill != null || it.strokes.isNotEmpty()
                                    }
                                    if (!state.snapshot.sourceTransformed && !hasLayerEdits) return@forEach
                                    if (mode == GuidanceEditorTab.INPAINT) {
                                        val rendered = withContext(Dispatchers.Default) {
                                            renderCanvas(
                                                original,
                                                state.snapshot.layers,
                                                true,
                                                state.snapshot.mask
                                            )
                                        }
                                        val savedMask = try {
                                            persistBitmap(rendered, true)
                                        } finally {
                                            rendered.recycle()
                                        }
                                        val savedBase = if (state.snapshot.sourceTransformed) {
                                            persistBitmap(original, false)
                                        } else {
                                            updated.baseImage
                                        }
                                        updated = updated.copy(
                                            baseImage = savedBase,
                                            maskImage = savedMask.copy(containsPaint = true)
                                        )
                                    } else {
                                        val rendered = withContext(Dispatchers.Default) {
                                            renderCanvas(original, state.snapshot.layers, false)
                                        }
                                        val saved = try {
                                            persistBitmap(rendered, false)
                                        } finally {
                                            rendered.recycle()
                                        }
                                        updated = when (mode) {
                                            GuidanceEditorTab.I2I -> updated.copy(baseImage = saved)
                                            GuidanceEditorTab.PRECISE -> updated.copy(
                                                preciseReference = updated.preciseReference.copy(asset = saved)
                                            )
                                            GuidanceEditorTab.VIBE -> updated.copy(
                                                vibes = updated.vibes.mapIndexed { index, vibe ->
                                                    if (index == updated.vibes.lastIndex) {
                                                        vibe.copy(asset = saved, encodedVibe = null)
                                                    } else vibe
                                                }
                                            )
                                            GuidanceEditorTab.INPAINT -> updated
                                        }
                                    }
                                    }
                                    onSave(updated)
                                } finally {
                                    saving = false
                                }
                            }
                        }, size = ButtonSize.Xs, enabled = !saving)
                    }
                )

                GuidanceTabs(tab, model, onTab = { next ->
                    if (next != tab) {
                        tabStates[tab] = currentTabState()
                        tab = next
                    }
                })
                GuidanceSourceBar(
                    tab = tab,
                    guidance = guidance,
                    model = model,
                    onPickImage = onPickImage,
                    onGuidance = ::applyGuidanceChange
                )
                GuidanceParameterBar(
                    tab,
                    model,
                    guidance,
                    onClearMask = {
                        sourceBitmap?.let { base ->
                            pushCurrentUndo()
                            val empty = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(AndroidColor.BLACK)
                            }
                            maskSourceBitmap = empty
                            maskOverlay = createMaskOverlay(empty).asImageBitmap()
                            layers.clear()
                            layers += CanvasLayer(name = "蒙版")
                            activeLayer = 0
                        }
                        guidance = guidance.copy(maskImage = guidance.maskImage?.copy(containsPaint = false))
                    },
                    onGuidance = ::applyGuidanceChange
                )

                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111318)).onSizeChanged { canvasSize = it }
                ) {
                    if (source != null) {
                        Canvas(
                            Modifier.fillMaxSize()
                                .semantics { contentDescription = "图像编辑画布；单指绘制，双指缩放移动" }
                                .pointerInput(source, tool, tab, activeLayer) {
                                    awaitEachGesture {
                                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                                        activeStroke.clear()
                                        var drawing = true
                                        val firstPoint = screenToImage(firstDown.position, source!!, canvasSize, zoom, pan)
                                            .copy(pressure = firstDown.normalizedPressure())
                                        if (tool == CanvasTool.PICKER && tab != GuidanceEditorTab.INPAINT) {
                                            sourceBitmap?.let { bitmap ->
                                                val x = firstPoint.x.toInt().coerceIn(0, bitmap.width - 1)
                                                val y = firstPoint.y.toInt().coerceIn(0, bitmap.height - 1)
                                                brushColor = Color(bitmap.getPixel(x, y))
                                            }
                                            drawing = false
                                        } else {
                                            activeStroke += firstPoint
                                        }

                                        do {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.filter { it.pressed }
                                            if (pressed.size >= 2) {
                                                drawing = false
                                                activeStroke.clear()
                                                val zoomChange = event.calculateZoom()
                                                val panChange = event.calculatePan()
                                                if (zoomChange.isFinite() && zoomChange > 0f) {
                                                    zoom = (zoom * zoomChange).coerceIn(0.5f, 8f)
                                                }
                                                pan += panChange
                                                event.changes.forEach { it.consume() }
                                            } else if (drawing && pressed.size == 1) {
                                                val change = pressed.first()
                                                activeStroke += screenToImage(change.position, source!!, canvasSize, zoom, pan)
                                                    .copy(pressure = change.normalizedPressure())
                                                change.consume()
                                            }
                                        } while (event.changes.any { it.pressed })

                                        if (drawing && activeStroke.isNotEmpty()) {
                                            val current = activeStroke.toList().let { points ->
                                                if (points.size == 1) points + points.first().copy(x = points.first().x + 0.01f) else points
                                            }
                                            if (tool == CanvasTool.CROP && tab != GuidanceEditorTab.INPAINT &&
                                                kotlin.math.abs(current.last().x - current.first().x) >= 8f &&
                                                kotlin.math.abs(current.last().y - current.first().y) >= 8f
                                            ) {
                                                sourceBitmap?.let { bitmap ->
                                                    scope.launch {
                                                        val cropped = withContext(Dispatchers.Default) {
                                                            cropAndScale(bitmap, current.first(), current.last())
                                                        }
                                                        pushCurrentUndo()
                                                        sourceBitmap = cropped
                                                        source = cropped.asImageBitmap()
                                                        sourceTransformed = true
                                                    }
                                                }
                                            } else if (tool != CanvasTool.PICKER) {
                                                pushCurrentUndo()
                                                val layer = layers[activeLayer]
                                                layers[activeLayer] = layer.copy(
                                                    strokes = layer.strokes + CanvasStroke(
                                                        current,
                                                        if (tab == GuidanceEditorTab.INPAINT) Color.White else brushColor,
                                                        brushSize,
                                                        opacity,
                                                        tool == CanvasTool.ERASER,
                                                        tool == CanvasTool.SOFT
                                                    )
                                                )
                                                if (tab == GuidanceEditorTab.INPAINT) {
                                                    guidance = guidance.copy(
                                                        maskImage = guidance.maskImage?.copy(containsPaint = true)
                                                    )
                                                }
                                            }
                                        }
                                        activeStroke.clear()
                                    }
                                }
                        ) {
                            drawEditorCanvas(
                                source!!,
                                maskOverlay,
                                layers,
                                activeStroke,
                                tool,
                                brushColor,
                                brushSize,
                                opacity,
                                tab == GuidanceEditorTab.INPAINT,
                                zoom,
                                pan
                            )
                        }
                    } else {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            val encodedOnly = tab == GuidanceEditorTab.VIBE &&
                                guidance.vibes.lastOrNull()?.encodedVibe?.isNotBlank() == true
                            CbText(if (encodedOnly) "已载入内嵌氛围编码" else "尚未选择图片", color = Color.White)
                            Spacer(Modifier.height(ChatBarSpacing.sm))
                            if (!encodedOnly) CbButton("选择文件", { onPickImage(tab.target) })
                        }
                    }
                }

                if (source != null) {
                    CanvasToolBar(
                        tool = tool,
                        onTool = { tool = it },
                        brushSize = brushSize,
                        onBrushSize = { brushSize = it },
                        opacity = opacity,
                        onOpacity = { opacity = it },
                        maskMode = tab == GuidanceEditorTab.INPAINT,
                        brushColor = brushColor,
                        onBrushColor = { brushColor = it },
                        onFill = {
                            pushCurrentUndo()
                            layers[activeLayer] = layers[activeLayer].copy(
                                fill = if (tab == GuidanceEditorTab.INPAINT) Color.White else brushColor
                            )
                            if (tab == GuidanceEditorTab.INPAINT) {
                                guidance = guidance.copy(maskImage = guidance.maskImage?.copy(containsPaint = true))
                            }
                        },
                        onRotate = {
                            sourceBitmap?.let { current ->
                                scope.launch {
                                    val rotatedPair = withContext(Dispatchers.Default) {
                                        val rotated = Bitmap.createBitmap(
                                            current,
                                            0,
                                            0,
                                            current.width,
                                            current.height,
                                            Matrix().apply { postRotate(90f) },
                                            true
                                        )
                                        val rotatedMask = maskSourceBitmap?.let { currentMask -> Bitmap.createBitmap(
                                            currentMask,
                                            0,
                                            0,
                                            currentMask.width,
                                            currentMask.height,
                                            Matrix().apply { postRotate(90f) },
                                            true
                                        ) }
                                        rotated to rotatedMask
                                    }
                                    pushCurrentUndo()
                                    sourceBitmap = rotatedPair.first
                                    source = rotatedPair.first.asImageBitmap()
                                    sourceTransformed = true
                                    rotatedPair.second?.let { rotatedMask ->
                                        maskSourceBitmap = rotatedMask
                                        maskOverlay = withContext(Dispatchers.Default) { createMaskOverlay(rotatedMask) }.asImageBitmap()
                                    }
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            }
                        },
                        onCanvasSize = {
                            sourceBitmap?.let { current ->
                                legalSiblingSizes(current.width, current.height).let { sizes ->
                                    if (sizes.size > 1) {
                                        val currentIndex = sizes.indexOfFirst { it.width == current.width && it.height == current.height }
                                        val next = sizes[(currentIndex + 1).mod(sizes.size)]
                                        scope.launch {
                                            val resized = withContext(Dispatchers.Default) {
                                                val mask = maskSourceBitmap ?: if (tab == GuidanceEditorTab.INPAINT) {
                                                    Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888).apply {
                                                        eraseColor(AndroidColor.BLACK)
                                                    }
                                                } else null
                                                resizeCanvasForOutpaint(current, mask, next.width, next.height)
                                            }
                                            pushCurrentUndo()
                                            sourceBitmap = resized.first
                                            source = resized.first.asImageBitmap()
                                            maskSourceBitmap = resized.second
                                            maskOverlay = resized.second?.let(::createMaskOverlay)?.asImageBitmap()
                                            sourceTransformed = true
                                        }
                                    }
                                }
                            }
                        },
                        rotateEnabled = tab != GuidanceEditorTab.INPAINT,
                        canvasSizeEnabled = tab == GuidanceEditorTab.I2I || tab == GuidanceEditorTab.INPAINT,
                        showCanvasTools = showCanvasTools,
                        onToggleCanvasTools = { showCanvasTools = !showCanvasTools },
                        layers = layers,
                        activeLayer = activeLayer,
                        onActiveLayer = { activeLayer = it },
                        onLayersChanged = { changed ->
                            pushCurrentUndo()
                            layers.replaceAll(changed)
                            activeLayer = activeLayer.coerceIn(0, layers.lastIndex)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasToolBar(
    tool: CanvasTool,
    onTool: (CanvasTool) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    maskMode: Boolean,
    brushColor: Color,
    onBrushColor: (Color) -> Unit,
    onFill: () -> Unit,
    onRotate: () -> Unit,
    onCanvasSize: () -> Unit,
    rotateEnabled: Boolean,
    canvasSizeEnabled: Boolean,
    showCanvasTools: Boolean,
    onToggleCanvasTools: () -> Unit,
    layers: List<CanvasLayer>,
    activeLayer: Int,
    onActiveLayer: (Int) -> Unit,
    onLayersChanged: (List<CanvasLayer>) -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.sm)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
            ) {
                CanvasTool.entries
                    .filter { !maskMode || it in setOf(CanvasTool.HARD, CanvasTool.ERASER) }
                    .forEach { item -> CbChoiceChip(item.label, tool == item, { onTool(item) }) }
                CbButton("填充", onFill, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                CbButton(
                    if (showCanvasTools) "收起画布工具" else "画布 / 图层",
                    onToggleCanvasTools,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm
                )
            }
            EditorSlider("笔刷大小", brushSize.toInt().toString(), brushSize, 2f..160f, onBrushSize)
            if (!maskMode) {
                EditorSlider("不透明度", "${(opacity * 100).toInt()}%", opacity, 0.05f..1f, onOpacity)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CbText("颜色", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    listOf("白" to Color.White, "黑" to Color.Black, "红" to Color(0xFFE5484D), "蓝" to Color(0xFF3B82F6)).forEach { (label, color) ->
                        CbChoiceChip(label, brushColor == color, { onBrushColor(color) })
                    }
                }
            }
            if (showCanvasTools) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
                ) {
                    CbButton("旋转 90°", onRotate, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = rotateEnabled)
                    CbButton("切换画布比例", onCanvasSize, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = canvasSizeEnabled)
                    layers.forEachIndexed { index, layer ->
                        CbChoiceChip(if (layer.visible) layer.name else "${layer.name}（隐藏）", activeLayer == index, { onActiveLayer(index) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
                ) {
                    CbButton("新建图层", {
                        if (layers.size < 8) onLayersChanged(layers + CanvasLayer(name = "图层 ${layers.size + 1}"))
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Outline, enabled = layers.size < 8)
                    CbButton(if (layers[activeLayer].visible) "隐藏图层" else "显示图层", {
                        onLayersChanged(layers.mapIndexed { index, layer ->
                            if (index == activeLayer) layer.copy(visible = !layer.visible) else layer
                        })
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Ghost)
                    CbButton("上移", {
                        if (activeLayer < layers.lastIndex) {
                            val changed = layers.toMutableList()
                            val layer = changed.removeAt(activeLayer)
                            changed.add(activeLayer + 1, layer)
                            onLayersChanged(changed)
                            onActiveLayer(activeLayer + 1)
                        }
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Ghost, enabled = activeLayer < layers.lastIndex)
                    CbButton("下移", {
                        if (activeLayer > 0) {
                            val changed = layers.toMutableList()
                            val layer = changed.removeAt(activeLayer)
                            changed.add(activeLayer - 1, layer)
                            onLayersChanged(changed)
                            onActiveLayer(activeLayer - 1)
                        }
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Ghost, enabled = activeLayer > 0)
                    CbButton("合并下层", {
                        if (activeLayer > 0) {
                            val lower = layers[activeLayer - 1]
                            val upper = layers[activeLayer]
                            val merged = lower.copy(name = "合并图层", fill = upper.fill ?: lower.fill, strokes = lower.strokes + upper.strokes)
                            onLayersChanged(layers.filterIndexed { index, _ -> index != activeLayer }.toMutableList().also {
                                it[activeLayer - 1] = merged
                            })
                            onActiveLayer(activeLayer - 1)
                        }
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Ghost, enabled = activeLayer > 0)
                    CbButton("删除图层", {
                        if (layers.size > 1) {
                            onLayersChanged(layers.filterIndexed { index, _ -> index != activeLayer })
                            onActiveLayer((activeLayer - 1).coerceAtLeast(0))
                        }
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Destructive, enabled = layers.size > 1)
                }
            }
        }
    }
}

@Composable
private fun EditorSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        CbText(label, Modifier.size(width = 72.dp, height = 24.dp), style = ChatBarTheme.typography.caption)
        CbSlider(value, onValueChange, range, Modifier.weight(1f).height(44.dp), contentDescription = "$label $valueLabel")
        CbText(valueLabel, Modifier.size(width = 44.dp, height = 24.dp), style = ChatBarTheme.typography.caption)
    }
}

@Composable
private fun GuidanceSourceBar(
    tab: GuidanceEditorTab,
    guidance: NovelAiImageGuidanceDraft,
    model: NovelAiImageModel,
    onPickImage: (NovelAiImageUseTarget) -> Unit,
    onGuidance: (String, NovelAiImageGuidanceDraft) -> Unit
) {
    val active = when (tab) {
        GuidanceEditorTab.I2I -> guidance.action == NovelAiGenerationAction.IMAGE_TO_IMAGE
        GuidanceEditorTab.INPAINT -> guidance.action == NovelAiGenerationAction.INPAINT
        GuidanceEditorTab.PRECISE -> guidance.effectiveReferenceMode(model) == NovelAiReferenceMode.PRECISE
        GuidanceEditorTab.VIBE -> guidance.effectiveReferenceMode(model) == NovelAiReferenceMode.VIBE
    }
    val asset = assetFor(tab, guidance)
    val sourceAvailable = when (tab) {
        GuidanceEditorTab.VIBE -> guidance.vibes.any(NovelAiVibeReferenceDraft::isUsable)
        else -> asset?.isUsable == true
    }
    val detail = when {
        active -> "已启用"
        sourceAvailable -> "图片已加载 · 未启用"
        else -> "未选择图片"
    }
    CbSurface(
        Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.xs),
        color = if (active) ChatBarTheme.colors.accent else ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, if (active) ChatBarTheme.colors.primary else ChatBarTheme.colors.border)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            Column(Modifier.weight(1f)) {
                CbText(tab.label, style = ChatBarTheme.typography.label)
                CbText(
                    buildString {
                        append(detail)
                        asset?.takeIf(NovelAiStudioAssetRef::isUsable)?.let { append(" · ${it.width}×${it.height}") }
                        if (tab == GuidanceEditorTab.VIBE) append(" · ${guidance.vibes.count(NovelAiVibeReferenceDraft::isUsable)}/4 张")
                    },
                    color = if (active) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbText(if (active) "开" else "关", style = ChatBarTheme.typography.caption)
            CbSwitch(
                checked = active,
                onCheckedChange = { enabled ->
                    onGuidance("source:${tab.name}:active", when (tab) {
                        GuidanceEditorTab.I2I -> guidance.copy(action = if (enabled) NovelAiGenerationAction.IMAGE_TO_IMAGE else NovelAiGenerationAction.TEXT_TO_IMAGE)
                        GuidanceEditorTab.INPAINT -> guidance.copy(action = if (enabled) NovelAiGenerationAction.INPAINT else NovelAiGenerationAction.TEXT_TO_IMAGE)
                        GuidanceEditorTab.PRECISE -> guidance.copy(referenceMode = if (enabled) NovelAiReferenceMode.PRECISE else NovelAiReferenceMode.NONE)
                        GuidanceEditorTab.VIBE -> guidance.copy(referenceMode = if (enabled) NovelAiReferenceMode.VIBE else NovelAiReferenceMode.NONE)
                    })
                },
                enabled = sourceAvailable
            )
            CbButton(
                if (tab == GuidanceEditorTab.VIBE) "添加" else if (sourceAvailable) "替换" else "选择",
                { onPickImage(tab.target) },
                size = ButtonSize.Sm,
                variant = ButtonVariant.Outline,
                enabled = tab != GuidanceEditorTab.VIBE || guidance.vibes.size < NovelAiImageGuidanceDraft.MAX_VIBES
            )
            if (sourceAvailable) {
                CbButton("清空", {
                    onGuidance("source:${tab.name}:clear", when (tab) {
                        GuidanceEditorTab.I2I, GuidanceEditorTab.INPAINT -> guidance.copy(
                            action = NovelAiGenerationAction.TEXT_TO_IMAGE,
                            baseImage = null,
                            maskImage = null
                        )
                        GuidanceEditorTab.PRECISE -> guidance.copy(
                            referenceMode = if (guidance.referenceMode == NovelAiReferenceMode.PRECISE) NovelAiReferenceMode.NONE else guidance.referenceMode,
                            preciseReference = guidance.preciseReference.copy(asset = null)
                        )
                        GuidanceEditorTab.VIBE -> guidance.copy(
                            referenceMode = if (guidance.referenceMode == NovelAiReferenceMode.VIBE) NovelAiReferenceMode.NONE else guidance.referenceMode,
                            vibes = emptyList()
                        )
                    })
                }, size = ButtonSize.Sm, variant = ButtonVariant.Destructive)
            }
        }
    }
}

@Composable
private fun GuidanceParameterBar(
    tab: GuidanceEditorTab,
    model: NovelAiImageModel,
    guidance: NovelAiImageGuidanceDraft,
    onClearMask: () -> Unit,
    onGuidance: (String, NovelAiImageGuidanceDraft) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.md)) {
        when (tab) {
            GuidanceEditorTab.I2I -> {
                EditorSlider("重绘强度", "%.2f".format(guidance.imageToImageStrength), guidance.imageToImageStrength, 0f..1f) {
                    onGuidance("i2i:strength", guidance.copy(imageToImageStrength = it))
                }
                EditorSlider("噪声", "%.2f".format(guidance.imageToImageNoise), guidance.imageToImageNoise, 0f..1f) {
                    onGuidance("i2i:noise", guidance.copy(imageToImageNoise = it))
                }
            }
            GuidanceEditorTab.INPAINT -> {
                EditorSlider("重绘强度", "%.2f".format(guidance.inpaintStrength), guidance.inpaintStrength, 0f..1f) {
                    onGuidance("inpaint:strength", guidance.copy(inpaintStrength = it))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CbText(
                        if (guidance.hasMask) "蒙版已有重绘区域" else "蒙版为空；蓝色区域会被重绘",
                        Modifier.weight(1f),
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbButton("清空蒙版", onClearMask, size = ButtonSize.Sm, variant = ButtonVariant.Ghost)
                }
            }
            GuidanceEditorTab.PRECISE -> if (model == NovelAiImageModel.V4_5_FULL) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                    NovelAiPreciseReferenceType.entries.forEach { type ->
                        CbChoiceChip(type.displayName, guidance.preciseReference.type == type, {
                            onGuidance("precise:type", guidance.copy(preciseReference = guidance.preciseReference.copy(type = type)))
                        })
                    }
                }
                EditorSlider("参考强度", "%.2f".format(guidance.preciseReference.strength), guidance.preciseReference.strength, 0f..1f) {
                    onGuidance("precise:strength", guidance.copy(preciseReference = guidance.preciseReference.copy(strength = it)))
                }
                EditorSlider("保真度", "%.2f".format(guidance.preciseReference.fidelity), guidance.preciseReference.fidelity, 0f..1f) {
                    onGuidance("precise:fidelity", guidance.copy(preciseReference = guidance.preciseReference.copy(fidelity = it)))
                }
            }
            GuidanceEditorTab.VIBE -> if (model == NovelAiImageModel.V4_5_FULL) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CbText("自动归一化强度", Modifier.weight(1f), style = ChatBarTheme.typography.caption)
                    CbSwitch(guidance.normalizeVibeStrengths, {
                        onGuidance("vibe:normalize", guidance.copy(normalizeVibeStrengths = it))
                    })
                }
                guidance.vibes.lastOrNull()?.let { vibe ->
                    if (vibe.informationEditable) {
                        EditorSlider("信息提取", "%.2f".format(vibe.informationExtracted), vibe.informationExtracted, 0f..1f) { value ->
                            onGuidance("vibe:${vibe.id}:information", guidance.copy(vibes = guidance.vibes.map { if (it.id == vibe.id) it.copy(informationExtracted = value, encodedVibe = null) else it }))
                        }
                    } else {
                        CbText("内嵌编码缺少原图；信息提取已锁定", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    }
                    EditorSlider("参考强度", "%.2f".format(vibe.strength), vibe.strength, 0f..1f) { value ->
                        onGuidance("vibe:${vibe.id}:strength", guidance.copy(vibes = guidance.vibes.map { if (it.id == vibe.id) it.copy(strength = value) else it }))
                    }
                    CbButton("移除当前氛围参考", {
                        val remaining = guidance.vibes.filterNot { it.id == vibe.id }
                        onGuidance("vibe:${vibe.id}:remove", guidance.copy(
                            vibes = remaining,
                            referenceMode = if (remaining.isEmpty()) NovelAiReferenceMode.NONE else guidance.referenceMode
                        ))
                    }, size = ButtonSize.Sm, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun GuidanceTabs(tab: GuidanceEditorTab, model: NovelAiImageModel, onTab: (GuidanceEditorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChatBarSpacing.sm, vertical = ChatBarSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
    ) {
        GuidanceEditorTab.entries.filter { model == NovelAiImageModel.V4_5_FULL || it in setOf(GuidanceEditorTab.I2I, GuidanceEditorTab.INPAINT) }
            .forEach { item ->
                CbButton(
                    item.label,
                    { onTab(item) },
                    Modifier.weight(1f),
                    variant = if (item == tab) ButtonVariant.Default else ButtonVariant.Ghost,
                    size = ButtonSize.Sm
                )
            }
    }
}

private fun defaultTab(guidance: NovelAiImageGuidanceDraft, model: NovelAiImageModel): GuidanceEditorTab = when {
    guidance.action == NovelAiGenerationAction.INPAINT -> GuidanceEditorTab.INPAINT
    guidance.action == NovelAiGenerationAction.IMAGE_TO_IMAGE -> GuidanceEditorTab.I2I
    model == NovelAiImageModel.V5_FULL -> GuidanceEditorTab.I2I
    guidance.referenceMode == NovelAiReferenceMode.PRECISE -> GuidanceEditorTab.PRECISE
    guidance.referenceMode == NovelAiReferenceMode.VIBE -> GuidanceEditorTab.VIBE
    else -> GuidanceEditorTab.I2I
}

private fun tabForTarget(target: NovelAiImageUseTarget): GuidanceEditorTab = when (target) {
    NovelAiImageUseTarget.IMAGE_TO_IMAGE -> GuidanceEditorTab.I2I
    NovelAiImageUseTarget.INPAINT -> GuidanceEditorTab.INPAINT
    NovelAiImageUseTarget.PRECISE_REFERENCE -> GuidanceEditorTab.PRECISE
    NovelAiImageUseTarget.VIBE_REFERENCE -> GuidanceEditorTab.VIBE
}

private fun assetFor(tab: GuidanceEditorTab, guidance: NovelAiImageGuidanceDraft): NovelAiStudioAssetRef? = when (tab) {
    GuidanceEditorTab.I2I, GuidanceEditorTab.INPAINT -> guidance.baseImage
    GuidanceEditorTab.PRECISE -> guidance.preciseReference.asset
    GuidanceEditorTab.VIBE -> guidance.vibes.lastOrNull()?.asset
}

private fun <T> MutableList<T>.replaceAll(values: List<T>) {
    clear()
    addAll(values)
}

private fun screenToImage(
    point: Offset,
    image: ImageBitmap,
    canvas: IntSize,
    zoom: Float,
    pan: Offset
): CanvasPoint {
    val baseScale = min(canvas.width.toFloat() / image.width, canvas.height.toFloat() / image.height)
    val scale = baseScale * zoom
    val left = (canvas.width - image.width * scale) / 2f + pan.x
    val top = (canvas.height - image.height * scale) / 2f + pan.y
    return CanvasPoint(
        x = ((point.x - left) / scale).coerceIn(0f, image.width.toFloat()),
        y = ((point.y - top) / scale).coerceIn(0f, image.height.toFloat())
    )
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.normalizedPressure(): Float =
    pressure.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.2f, 1f) ?: 1f

private fun DrawScope.drawEditorCanvas(
    image: ImageBitmap,
    existingMask: ImageBitmap?,
    layers: List<CanvasLayer>,
    activeStroke: List<CanvasPoint>,
    activeTool: CanvasTool,
    brushColor: Color,
    brushSize: Float,
    opacity: Float,
    maskMode: Boolean,
    zoom: Float,
    pan: Offset
) {
    val baseScale = min(size.width / image.width, size.height / image.height)
    val scale = baseScale * zoom
    val left = (size.width - image.width * scale) / 2f + pan.x
    val top = (size.height - image.height * scale) / 2f + pan.y
    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize((image.width * scale).toInt(), (image.height * scale).toInt()))
    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        val checkpoint = canvas.saveLayer(
            left,
            top,
            left + image.width * scale,
            top + image.height * scale,
            null
        )
        if (maskMode && existingMask != null) {
            canvas.drawBitmap(
                existingMask.asAndroidBitmap(),
                Matrix().apply { setScale(scale, scale); postTranslate(left, top) },
                null
            )
        }
        layers.filter(CanvasLayer::visible).forEach { layer ->
            layer.fill?.let { fill ->
                canvas.drawRect(
                    left,
                    top,
                    left + image.width * scale,
                    top + image.height * scale,
                    AndroidPaint().apply {
                        color = if (maskMode) AndroidColor.rgb(59, 130, 246) else fill.toArgbCompat()
                        alpha = if (maskMode) 153 else (fill.alpha * 255).toInt().coerceIn(0, 255)
                    }
                )
            }
            layer.strokes.forEach { stroke ->
                canvas.drawPreviewStroke(stroke, left, top, scale, maskMode)
            }
        }
        if (activeStroke.size >= 2 && activeTool in setOf(CanvasTool.HARD, CanvasTool.SOFT, CanvasTool.ERASER)) {
            canvas.drawPreviewStroke(
                CanvasStroke(
                    points = activeStroke,
                    color = if (maskMode) Color.White else brushColor,
                    width = brushSize,
                    alpha = opacity,
                    erase = activeTool == CanvasTool.ERASER,
                    soft = activeTool == CanvasTool.SOFT
                ),
                left,
                top,
                scale,
                maskMode
            )
        }
        canvas.restoreToCount(checkpoint)
    }
}

private fun AndroidCanvas.drawPreviewStroke(
    stroke: CanvasStroke,
    left: Float,
    top: Float,
    scale: Float,
    maskMode: Boolean
) {
    if (stroke.points.size < 2) return
    val path = android.graphics.Path().apply {
        moveTo(left + stroke.points.first().x * scale, top + stroke.points.first().y * scale)
        stroke.points.drop(1).forEach { lineTo(left + it.x * scale, top + it.y * scale) }
    }
    val pressure = stroke.points.map(CanvasPoint::pressure).average().toFloat().coerceIn(0.2f, 1f)
    val flags = if (maskMode) 0 else AndroidPaint.ANTI_ALIAS_FLAG
    val paint = AndroidPaint(flags).apply {
        color = if (maskMode) AndroidColor.rgb(59, 130, 246) else stroke.color.toArgbCompat()
        alpha = if (maskMode) 153 else (stroke.alpha * 255).toInt().coerceIn(0, 255)
        strokeWidth = stroke.width * pressure * scale
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        if (stroke.soft && !maskMode) {
            maskFilter = BlurMaskFilter((stroke.width * scale * 0.25f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        if (stroke.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    drawPath(path, paint)
}

private fun renderCanvas(
    original: Bitmap,
    layers: List<CanvasLayer>,
    maskMode: Boolean,
    existingMask: Bitmap? = null
): Bitmap {
    val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(output)
    if (maskMode) {
        canvas.drawColor(AndroidColor.BLACK)
        existingMask?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    } else canvas.drawBitmap(original, 0f, 0f, null)
    layers.filter(CanvasLayer::visible).forEach { layer ->
        val checkpoint = if (maskMode) null else canvas.saveLayer(
            0f,
            0f,
            original.width.toFloat(),
            original.height.toFloat(),
            null
        )
        layer.fill?.let { fill ->
            canvas.drawColor(if (maskMode) AndroidColor.WHITE else fill.toArgbCompat())
        }
        layer.strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = if (maskMode) {
                    if (stroke.erase) AndroidColor.BLACK else AndroidColor.WHITE
                } else {
                    stroke.color.toArgbCompat()
                }
                alpha = if (maskMode) 255 else (stroke.alpha * 255).toInt().coerceIn(0, 255)
                strokeWidth = stroke.width * stroke.points.map(CanvasPoint::pressure).average().toFloat().coerceIn(0.1f, 1f)
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                strokeJoin = AndroidPaint.Join.ROUND
                if (stroke.soft) maskFilter = BlurMaskFilter((stroke.width * 0.35f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                if (stroke.erase && !maskMode) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            val path = android.graphics.Path().apply {
                moveTo(stroke.points.first().x, stroke.points.first().y)
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
        checkpoint?.let(canvas::restoreToCount)
    }
    if (maskMode) binarizeMask(output)
    return output
}

private fun binarizeMask(bitmap: Bitmap) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    pixels.indices.forEach { index ->
        val color = pixels[index]
        val intensity = (AndroidColor.red(color) + AndroidColor.green(color) + AndroidColor.blue(color)) / 3
        pixels[index] = if (intensity >= 128) AndroidColor.WHITE else AndroidColor.BLACK
    }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
}

private fun createMaskOverlay(mask: Bitmap): Bitmap {
    val width = mask.width
    val height = mask.height
    val pixels = IntArray(width * height)
    mask.getPixels(pixels, 0, width, 0, 0, width, height)
    pixels.indices.forEach { index ->
        val color = pixels[index]
        val intensity = (AndroidColor.red(color) + AndroidColor.green(color) + AndroidColor.blue(color)) / 3
        pixels[index] = if (intensity < 128) {
            AndroidColor.TRANSPARENT
        } else {
            AndroidColor.argb(148, 59, 130, 246)
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun cropAndScale(source: Bitmap, first: CanvasPoint, last: CanvasPoint): Bitmap {
    val left = minOf(first.x, last.x).toInt().coerceIn(0, source.width - 1)
    val top = minOf(first.y, last.y).toInt().coerceIn(0, source.height - 1)
    val right = maxOf(first.x, last.x).toInt().coerceIn(left + 1, source.width)
    val bottom = maxOf(first.y, last.y).toInt().coerceIn(top + 1, source.height)
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    return if (cropped.width == source.width && cropped.height == source.height) {
        cropped
    } else {
        Bitmap.createScaledBitmap(cropped, source.width, source.height, true).also { cropped.recycle() }
    }
}

private fun legalSiblingSizes(width: Int, height: Int): List<NovelAiImageSize> {
    val tier = NovelAiSizeTier.entries.firstOrNull { candidate ->
        NovelAiAspectRatio.entries.any { aspect ->
            val size = NovelAiGenerationSettings(sizeTier = candidate, aspectRatio = aspect).normalized().imageSize()
            size.width == width && size.height == height
        }
    } ?: return emptyList()
    return NovelAiAspectRatio.entries
        .filterNot { tier == NovelAiSizeTier.WALLPAPER && it == NovelAiAspectRatio.SQUARE }
        .map { NovelAiGenerationSettings(sizeTier = tier, aspectRatio = it).imageSize() }
        .distinctBy { it.width to it.height }
}

private fun resizeCanvasForOutpaint(
    source: Bitmap,
    existingMask: Bitmap?,
    targetWidth: Int,
    targetHeight: Int
): Pair<Bitmap, Bitmap?> {
    val scale = minOf(1f, targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
    val drawnWidth = source.width * scale
    val drawnHeight = source.height * scale
    val left = (targetWidth - drawnWidth) / 2f
    val top = (targetHeight - drawnHeight) / 2f
    val matrix = Matrix().apply { setScale(scale, scale); postTranslate(left, top) }
    val base = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
        AndroidCanvas(output).apply {
            drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawBitmap(source, matrix, null)
        }
    }
    val mask = existingMask?.let { oldMask ->
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
            AndroidCanvas(output).apply {
                drawColor(AndroidColor.WHITE)
                drawRect(left, top, left + drawnWidth, top + drawnHeight, AndroidPaint().apply { color = AndroidColor.BLACK })
                drawBitmap(oldMask, matrix, null)
            }
        }
    }
    return base to mask
}

private fun trimEditorHistory(history: MutableList<CanvasEditorSnapshot>) {
    fun snapshotBytes(snapshot: CanvasEditorSnapshot): Long =
        (snapshot.base?.allocationByteCount?.toLong() ?: 0L) +
            (snapshot.mask?.allocationByteCount?.toLong() ?: 0L) +
            snapshot.layers.sumOf { layer ->
                128L + layer.strokes.sumOf { stroke -> 96L + stroke.points.size * 12L }
            }
    var total = history.sumOf(::snapshotBytes)
    while (history.size > 50 || total > 64L * 1024 * 1024) {
        val removed = history.removeAt(0)
        total -= snapshotBytes(removed)
    }
}

private fun Color.toArgbCompat(): Int = AndroidColor.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)

private const val GUIDANCE_HISTORY_COALESCE_MS = 800L
