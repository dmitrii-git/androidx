/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui.platform

import android.annotation.TargetApi
import android.graphics.Matrix
import android.graphics.RenderNode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.DrawLayerModifier
import androidx.compose.ui.TransformOrigin
import androidx.compose.ui.node.OwnedLayer

/**
 * RenderNode implementation of OwnedLayer.
 */
@TargetApi(29)
internal class RenderNodeLayer(
    val ownerView: AndroidComposeView,
    drawLayerModifier: DrawLayerModifier,
    val drawBlock: (Canvas) -> Unit,
    val invalidateParentLayer: () -> Unit
) : OwnedLayer {
    /**
     * True when the RenderNodeLayer has been invalidated and not yet drawn.
     */
    private var isDirty = false
    private val outlineResolver = OutlineResolver(ownerView.density)
    private var isDestroyed = false
    private var cacheMatrix: Matrix? = null
    private var drawnWithZ = false

    private val canvasHolder = CanvasHolder()

    /**
     * Local copy of the transform origin as DrawLayerModifier can be implemented
     * as a model object. Update this field within [updateLayerProperties] and use it
     * in [resize] or other methods
     */
    private var transformOrigin: TransformOrigin = TransformOrigin.Center

    private val renderNode = RenderNode(null).apply {
        setHasOverlappingRendering(true)
    }

    override val layerId: Long
        get() = renderNode.uniqueId

    override var modifier: DrawLayerModifier = drawLayerModifier
        set(value) {
            if (field !== value) {
                field = value
                updateLayerProperties()
            }
        }

    override fun updateLayerProperties() {
        transformOrigin = modifier.transformOrigin
        val wasClippingManually = renderNode.clipToOutline && outlineResolver.clipPath != null
        renderNode.scaleX = modifier.scaleX
        renderNode.scaleY = modifier.scaleY
        renderNode.alpha = modifier.alpha
        renderNode.translationX = modifier.translationX
        renderNode.translationY = modifier.translationY
        renderNode.elevation = modifier.shadowElevation
        renderNode.rotationZ = modifier.rotationZ
        renderNode.rotationX = modifier.rotationX
        renderNode.rotationY = modifier.rotationY
        renderNode.pivotX = transformOrigin.pivotFractionX * renderNode.width
        renderNode.pivotY = transformOrigin.pivotFractionY * renderNode.height
        val shape = modifier.shape
        val clip = modifier.clip
        renderNode.clipToOutline = clip && shape !== RectangleShape
        renderNode.clipToBounds = clip && shape === RectangleShape
        val shapeChanged = outlineResolver.update(
            shape,
            renderNode.alpha,
            renderNode.clipToOutline,
            renderNode.elevation
        )
        renderNode.setOutline(outlineResolver.outline)
        val isClippingManually = renderNode.clipToOutline && outlineResolver.clipPath != null
        if (wasClippingManually != isClippingManually || (isClippingManually && shapeChanged)) {
            invalidate()
        } else {
            triggerRepaint()
        }
        if (!drawnWithZ && renderNode.elevation > 0f) {
            invalidateParentLayer()
        }
    }

    override fun resize(size: IntSize) {
        val width = size.width
        val height = size.height
        renderNode.pivotX = transformOrigin.pivotFractionX * width
        renderNode.pivotY = transformOrigin.pivotFractionY * height
        if (renderNode.setPosition(
            renderNode.left,
            renderNode.top,
            renderNode.left + width,
            renderNode.top + height
        )) {
            outlineResolver.update(Size(width.toFloat(), height.toFloat()))
            renderNode.setOutline(outlineResolver.outline)
            invalidate()
        }
    }

    override fun move(position: IntOffset) {
        val oldLeft = renderNode.left
        val oldTop = renderNode.top
        val newLeft = position.x
        val newTop = position.y
        if (oldLeft != newLeft || oldTop != newTop) {
            renderNode.offsetLeftAndRight(newLeft - oldLeft)
            renderNode.offsetTopAndBottom(newTop - oldTop)
            triggerRepaint()
        }
    }

    override fun invalidate() {
        if (!isDirty && !isDestroyed) {
            ownerView.invalidate()
            ownerView.dirtyLayers += this
            isDirty = true
        }
    }

    /**
     * This only triggers the system so that it knows that some kind of painting
     * must happen without actually causing the layer to be invalidated and have
     * to re-record its drawing.
     */
    private fun triggerRepaint() {
        ownerView.parent?.onDescendantInvalidated(ownerView, ownerView)
    }

    override fun drawLayer(canvas: Canvas) {
        val androidCanvas = canvas.nativeCanvas
        if (androidCanvas.isHardwareAccelerated) {
            updateDisplayList()
            drawnWithZ = renderNode.elevation > 0f
            if (drawnWithZ) {
                canvas.enableZ()
            }
            androidCanvas.drawRenderNode(renderNode)
            if (drawnWithZ) {
                canvas.disableZ()
            }
        } else {
            drawBlock(canvas)
        }
        isDirty = false
    }

    override fun updateDisplayList() {
        if (isDirty || !renderNode.hasDisplayList()) {
            canvasHolder.drawInto(renderNode.beginRecording()) {
                val clipPath = outlineResolver.clipPath
                val manuallyClip = renderNode.clipToOutline && clipPath != null
                if (manuallyClip) {
                    save()
                    clipPath(clipPath!!)
                }
                ownerView.observeLayerModelReads(this@RenderNodeLayer) {
                    drawBlock(this)
                }
                if (manuallyClip) {
                    restore()
                }
            }
            renderNode.endRecording()
            isDirty = false
        }
    }

    override fun destroy() {
        isDestroyed = true
        ownerView.dirtyLayers -= this
    }

    override fun getMatrix(): Matrix {
        val matrix = cacheMatrix ?: Matrix().also { cacheMatrix = it }
        renderNode.getMatrix(matrix)
        return matrix
    }
}
