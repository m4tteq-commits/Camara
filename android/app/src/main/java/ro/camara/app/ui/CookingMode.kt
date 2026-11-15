package ro.camara.app.ui

import android.media.RingtoneManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.camara.app.data.Recipe

@Composable
internal fun CookingMode(recipe: Recipe, modifier: Modifier, onFinish: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    var step by rememberSaveable(recipe.id) { mutableIntStateOf(0) }
    var ingredientsExpanded by rememberSaveable(recipe.id) { mutableStateOf(false) }
    val index = step.coerceIn(0, recipe.steps.lastIndex.coerceAtLeast(0))
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        val dockTimer = wide && maxHeight >= 520.dp
        LaunchedEffect(index, wide, dockTimer) { scroll.scrollTo(0) }
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (wide) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                IconTile(Icons.Outlined.Kitchen)
                                Text("Ingrediente", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(portionsLabel(recipe.servings), style = MaterialTheme.typography.bodySmall)
                            CookingIngredients(recipe)
                        }
                    }
                }
            }
            val right = Modifier.weight(if (wide) 1.75f else 1f).fillMaxHeight()
            Column(if (dockTimer) right else right.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val stepPane = if (dockTimer) Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll) else Modifier.fillMaxWidth()
                Column(stepPane) { StepCard(recipe, index, { step = it }, onFinish) }
                CookingTimer()
                if (!wide) ExpandablePanel("Ingrediente", countLabel(recipe.ingredients.size, "ingredient", "ingrediente"),
                    ingredientsExpanded, { ingredientsExpanded = !ingredientsExpanded }, icon = Icons.Outlined.Kitchen) {
                    CookingIngredients(recipe)
                }
            }
        }
    }
}

@Composable
private fun CookingIngredients(recipe: Recipe) {
    recipe.ingredients.forEach { ingredient ->
        Text("${ingredient.name} · ${ingredient.quantity}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepCard(recipe: Recipe, step: Int, onStep: (Int) -> Unit, onFinish: () -> Unit) {
    val count = recipe.steps.size
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
            if (count > 0) {
                SmallPill("Pasul ${step + 1} din $count")
                LinearProgressIndicator(progress = { (step + 1f) / count }, modifier = Modifier.fillMaxWidth())
                if (LocalCamaraMotion.current) {
                    AnimatedContent(step, transitionSpec = {
                        val direction = if (targetState >= initialState) 1 else -1
                        (fadeIn(tween(170)) + slideInHorizontally(tween(210)) { direction * it / 16 }) togetherWith fadeOut(tween(95))
                    }, label = "Pasul rețetei") { index ->
                        recipe.steps.getOrNull(index)?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    }
                } else Text(recipe.steps[step], style = MaterialTheme.typography.headlineSmall)
            } else {
                Text("Rețeta nu are pași salvați.", style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) OutlinedButton(onClick = { onStep(step - 1) }, modifier = Modifier.weight(1f)) { Text("Anterior") }
                Button(onClick = { if (step < count - 1) onStep(step + 1) else onFinish() }, modifier = Modifier.weight(1f)) {
                    Text(if (step < count - 1) "Următorul pas" else "Încheie ghidul")
                }
            }
        }
    }
}

@Composable
private fun CookingTimer() {
    val view = LocalView.current
    var minutes by rememberSaveable { mutableStateOf("5") }
    var deadline by rememberSaveable { mutableLongStateOf(0L) }
    var remaining by rememberSaveable { mutableLongStateOf(0L) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val configuredMinutes = minutes.toIntOrNull()?.takeIf { it in 1..180 }
    val running = deadline > 0L
    LaunchedEffect(deadline) {
        if (deadline == 0L) return@LaunchedEffect
        while (true) {
            remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remaining == 0L) break
            delay((remaining % 1_000L).takeIf { it > 0 } ?: 1_000L)
        }
        deadline = 0L
        finished = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        runCatching {
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(view.context, sound)?.play()
        }
    }
    val displayed = if (remaining > 0L || finished) remaining else (configuredMinutes ?: 0) * 60_000L
    val seconds = (displayed + 999) / 1000

    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Outlined.Timer)
                Text("Cronometru", style = MaterialTheme.typography.titleMedium)
            }
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.headlineLarge)
            if (finished) Text("Timpul a expirat", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(minutes, { input ->
                if (input.length <= 3 && input.all { it in '0'..'9' }) {
                    minutes = input
                    finished = false
                }
            }, label = { Text("Minute · 1–180") }, singleLine = true, enabled = !running && remaining == 0L,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (running) {
                        remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        deadline = 0L
                    } else {
                        remaining = if (remaining > 0L) remaining else checkNotNull(configuredMinutes) * 60_000L
                        finished = false
                        deadline = SystemClock.elapsedRealtime() + remaining
                    }
                }, enabled = running || remaining > 0L || configuredMinutes != null, modifier = Modifier.weight(1f)) {
                    Text(if (running) "Pauză" else if (remaining > 0L) "Reia" else "Pornește")
                }
                OutlinedButton(onClick = { deadline = 0L; remaining = 0L; finished = false },
                    enabled = running || remaining > 0L || finished, modifier = Modifier.weight(1f)) { Text("Resetează") }
            }
        }
    }
}
