package cc.gilmore.todoai.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Same brand green as res/values/colors.xml's launcher_background (#1B5E20) — kept as a literal here rather than a resource reference since this is the one screen that wants it as a Compose Color, not an XML drawable/background. */
private val BrandGreen = Color(0xFF1B5E20)
private val BlockWhite = Color.White

/**
 * First thing shown on every cold start (see ui/navigation/NavGraph.kt's
 * `showSplash` gate) — brand + a 5-second progress bar, then the normal
 * app screens. The three-block "building" motif mirrors
 * res/drawable/ic_launcher_foreground.xml (same ascending-blocks-plus-
 * checkmark shape, same green) so the launcher icon and this screen read
 * as one identity; the blocks rise into place and the checkmark lands as
 * `progress` climbs, so the "building" animation and the progress bar are
 * driven by the exact same [Animatable] rather than two separate timers
 * that could drift out of sync.
 *
 * [onFinished] fires once, after the 5-second fill completes — NavGraph.kt
 * uses it to reveal whatever screen is actually next (Login or the main
 * app), so this composable doesn't need to know about auth state at all.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 5000, easing = LinearEasing))
        onFinished()
    }

    val value = progress.value

    Box(
        modifier = Modifier.fillMaxSize().background(BrandGreen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BuildingBlocks(progress = value)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Structure",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = BlockWhite,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Add Structure to your life",
                style = MaterialTheme.typography.bodyLarge,
                color = BlockWhite.copy(alpha = 0.85f),
            )

            Spacer(modifier = Modifier.height(48.dp))

            LinearProgressIndicator(
                progress = value,
                modifier = Modifier.width(200.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = BlockWhite,
                trackColor = BlockWhite.copy(alpha = 0.25f),
            )
        }
    }
}

/**
 * The launcher icon's three-ascending-blocks-plus-checkmark glyph, scaled
 * up and turned into a tiny "under construction" animation: each block
 * rises into place at its own progress threshold, and the checkmark lands
 * on the tallest block right as the bar finishes — a little payoff for
 * "built" that ties the splash's five seconds to something other than a
 * blank wait.
 */
@Composable
private fun BuildingBlocks(progress: Float) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Block(width = 32.dp, height = 40.dp, visible = progress >= 0.08f)
        Block(width = 32.dp, height = 70.dp, visible = progress >= 0.38f)
        Block(width = 32.dp, height = 100.dp, visible = progress >= 0.68f, showCheck = progress >= 0.94f)
    }
}

@Composable
private fun Block(width: Dp, height: Dp, visible: Boolean, showCheck: Boolean = false) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 2 },
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .background(BlockWhite),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedVisibility(visible = showCheck, enter = fadeIn(tween(250))) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = BrandGreen,
                    modifier = Modifier.padding(top = 14.dp).size(20.dp),
                )
            }
        }
    }
}
