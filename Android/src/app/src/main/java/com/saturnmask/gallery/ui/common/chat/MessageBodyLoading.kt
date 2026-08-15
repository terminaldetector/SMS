/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saturnmask.gallery.ui.common.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HomeRepairService
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.ui.common.RotationalLoader

/** Composable function to display a loading indicator. */
@Composable
fun MessageBodyLoading(message: ChatMessageLoading? = null) {
  Row(
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    RotationalLoader(size = 24.dp)

    if (message?.extraProgressLabel?.isNotEmpty() == true) {
      AnimatedContent(
        message.extraProgressLabel,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
      ) { label ->
        ProgressLabelRow(label = label)
      }
    } else {
      Spacer(modifier = Modifier.width(1.dp))
    }
  }
}

/**
 * Only composed while there's an actual progress label to show, so the icon-flash animation isn't
 * created (and forcing recomposition every frame) for the common case where [MessageBodyLoading]
 * has nothing but the [RotationalLoader] to display.
 */
@Composable
private fun ProgressLabelRow(label: String) {
  val infiniteTransition = rememberInfiniteTransition(label = "icon-flash")
  // Read only inside the graphicsLayer{} lambda below (layout/draw phase), not via `by` in the
  // composable body -- see RotationalLoader.kt for why that avoids a per-frame recomposition.
  val iconAlpha =
    infiniteTransition.animateFloat(
      initialValue = 0.3f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          // Duration of one phase (1 second)
          animation = tween(1000, easing = LinearEasing),
          // Reverse back to start for a "breathing" effect
          repeatMode = RepeatMode.Reverse,
        ),
      label = "icon-alpha",
    )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      Icons.Rounded.HomeRepairService,
      contentDescription = null,
      modifier = Modifier.graphicsLayer { alpha = iconAlpha.value }.size(16.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
  }
}
