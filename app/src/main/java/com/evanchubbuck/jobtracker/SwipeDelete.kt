package com.evanchubbuck.jobtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class DeleteSwipeAction { NONE, CONFIRM, DELETE }

internal fun deleteSwipeAction(fraction: Float, skipConfirmation: Boolean): DeleteSwipeAction = when {
    skipConfirmation && fraction > 0.7f -> DeleteSwipeAction.DELETE
    fraction >= 0.5f -> DeleteSwipeAction.CONFIRM
    else -> DeleteSwipeAction.NONE
}

/** Wait for release so a long swipe can pass the confirmation threshold. */
@Composable
internal fun SwipeDeleteContainer(
    deleteLabel: String,
    skipConfirmation: Boolean,
    onDelete: () -> Unit,
    onDeleteImmediately: () -> Unit,
    onMoveToTop: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var width by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val confirmDelete by rememberUpdatedState(onDelete)
    val deleteNow by rememberUpdatedState(onDeleteImmediately)
    val moveToTop by rememberUpdatedState(onMoveToTop)
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        val moving = offset > 0f
        Box(Modifier.matchParentSize()
            .background(if (moving) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(16.dp)).padding(horizontal = 24.dp),
            contentAlignment = if (moving) Alignment.CenterStart else Alignment.CenterEnd) {
            val label = if (moving) "Move to top" else when (deleteSwipeAction(-offset / width, skipConfirmation)) {
                DeleteSwipeAction.DELETE -> "Release to delete now"
                DeleteSwipeAction.CONFIRM -> "Release to confirm"
                DeleteSwipeAction.NONE -> deleteLabel
            }
            Text(label, color = if (moving) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }
            .pointerInput(width, skipConfirmation, onMoveToTop != null) {
                detectHorizontalDragGestures(onDragEnd = {
                    val fraction = offset / width
                    offset = 0f
                    if (fraction >= 0.5f) moveToTop?.invoke()
                    else when (deleteSwipeAction(-fraction, skipConfirmation)) {
                        DeleteSwipeAction.DELETE -> deleteNow()
                        DeleteSwipeAction.CONFIRM -> confirmDelete()
                        DeleteSwipeAction.NONE -> Unit
                    }
                }, onDragCancel = { offset = 0f }) { change, amount ->
                    change.consume()
                    offset = (offset + amount).coerceIn(-width, if (moveToTop != null) width else 0f)
                }
            }) { content() }
    }
}
