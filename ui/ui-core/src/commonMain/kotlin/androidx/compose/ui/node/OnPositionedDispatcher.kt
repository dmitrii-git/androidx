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

package androidx.compose.ui.node

/**
 * Tracks the nodes being positioned and dispatches OnPositioned callbacks when we finished
 * the measure/layout pass.
 */
@OptIn(ExperimentalLayoutNodeApi::class)
internal class OnPositionedDispatcher {

    private var topDepth = Int.MAX_VALUE
    private var topNode: LayoutNode? = null

    fun onNodePositioned(node: LayoutNode) {
        if (node.depth < topDepth) {
            topDepth = node.depth
            topNode = node
        }
    }

    fun dispatch() {
        topNode?.also {
            it.dispatchOnPositionedCallbacks()
            topNode = null
            topDepth = Int.MAX_VALUE
        }
    }
}
