package com.invictus.kodex.feature.tools

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.tracer.ImageAnalyzer
import com.invictus.kodex.core.tracer.ImageTracer
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val QUALITY_LABELS = intArrayOf(
    R.string.tracer_quality_1,
    R.string.tracer_quality_2,
    R.string.tracer_quality_3,
    R.string.tracer_quality_4,
    R.string.tracer_quality_5,
)

/** Image tracer: choose a picture, set Quality / Colors, preview the SVG, download Preview / SVG / XML. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageTracerScreen(
    onBack: () -> Unit,
    vm: ImageTracerViewModel = viewModel(factory = ImageTracerViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pickImage) }

    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            if (e is ImageTracerViewModel.Effect.Message) scope.launch { snackbar.showSnackbar(e.text.resolve(context)) }
        }
    }

    s.heavyXmlChars?.let { chars ->
        AlertDialog(
            onDismissRequest = vm::dismissHeavyXml,
            title = { Text(stringResource(R.string.tracer_heavy_title)) },
            text = { Text(stringResource(R.string.tracer_heavy_body, Formatter.formatShortFileSize(context, chars.toLong()))) },
            confirmButton = { TextButton(onClick = vm::confirmHeavyXml) { Text(stringResource(R.string.tracer_heavy_export)) } },
            dismissButton = { TextButton(onClick = vm::dismissHeavyXml) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_tracer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (s.nativeMissing) {
                Text(
                    stringResource(R.string.tracer_native_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.errorContainer).padding(14.dp),
                )
            }

            // ---- image ----
            TracerCard(R.string.tracer_section_image) {
                OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }, enabled = !s.running, modifier = Modifier.fillMaxWidth()) {
                    Icon(XIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tracer_choose))
                }
                if (s.hasSource) {
                    Text(s.sourceName.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${s.origWidth} × ${s.origHeight} px",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    if (s.scaledDown) {
                        Text(
                            stringResource(R.string.tracer_scaled_down, ImageTracer.MAX_SIDE),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(stringResource(R.string.tracer_nothing), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }

            // ---- size ----
            if (s.hasSource) {
                TracerCard(R.string.tracer_section_size) {
                    SizeChips(
                        longSide = s.origLongSide,
                        selected = s.targetSide,
                        onSelect = vm::setTargetSide,
                    )
                    Text(stringResource(R.string.tracer_size_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }

            // ---- options ----
            val manual = s.hasSource && !s.nativeMissing && !s.auto
            TracerCard(R.string.tracer_section_options) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tracer_auto), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.tracer_auto_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = s.auto, onCheckedChange = vm::setAuto, enabled = !s.nativeMissing)
                }
                val kind = s.detected
                if (s.auto && kind != null) {
                    Text(
                        stringResource(R.string.tracer_auto_detected, stringResource(kindLabel(kind))),
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.primary,
                    )
                }
                SliderRow(
                    label = stringResource(R.string.tracer_quality),
                    value = s.quality.toFloat(),
                    range = ImageTracer.QUALITY_MIN.toFloat()..ImageTracer.QUALITY_MAX.toFloat(),
                    steps = ImageTracer.QUALITY_MAX - ImageTracer.QUALITY_MIN - 1,
                    valueText = stringResource(QUALITY_LABELS[s.quality - 1]),
                    enabled = manual,
                    onChange = { vm.setQuality(it.roundToInt()) },
                    onFinished = vm::commit,
                )
                SliderRow(
                    label = stringResource(R.string.tracer_colors),
                    value = s.colorIndex.toFloat(),
                    range = 0f..ImageTracer.COLOR_STEPS.lastIndex.toFloat(),
                    steps = ImageTracer.COLOR_STEPS.size - 2,
                    valueText = if (s.colors == 0) stringResource(R.string.tracer_colors_all) else s.colors.toString(),
                    enabled = manual,
                    onChange = { vm.setColorIndex(it.roundToInt()) },
                    onFinished = vm::commit,
                )
                Text(stringResource(R.string.tracer_exact_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            // ---- preview ----
            TracerCard(R.string.tracer_section_preview) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = s.preview
                    when {
                        preview != null -> Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.tracer_section_preview),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        )
                        s.previewFailed -> Text(
                            stringResource(R.string.tracer_preview_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                        !s.running -> Text(stringResource(R.string.tracer_preview_empty), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    if (s.running) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.tracer_tracing), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (s.hasResult) {
                    Text(
                        stringResource(
                            R.string.tracer_stats,
                            s.width, s.height, "%,d".format(s.paths),
                            Formatter.formatShortFileSize(context, s.svgBytes.toLong()),
                            s.elapsedMs / 1000.0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            // ---- downloads: one line, three buttons ----
            val canSave = s.hasResult && !s.running
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadButton(R.string.tracer_btn_preview, canSave && s.preview != null, vm::savePreview, Modifier.weight(1f))
                DownloadButton(R.string.tracer_btn_svg, canSave, vm::saveSvg, Modifier.weight(1f))
                DownloadButton(R.string.tracer_btn_xml, canSave, vm::saveXml, Modifier.weight(1f))
            }
        }
    }
}

private fun kindLabel(kind: ImageAnalyzer.Kind): Int = when (kind) {
    ImageAnalyzer.Kind.PIXEL_ART -> R.string.tracer_kind_pixel_art
    ImageAnalyzer.Kind.FLAT -> R.string.tracer_kind_flat
    ImageAnalyzer.Kind.GRAPHIC -> R.string.tracer_kind_graphic
    ImageAnalyzer.Kind.PHOTO -> R.string.tracer_kind_photo
}

/** "Original · N px" (the default, always there) followed by the presets smaller than the picture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizeChips(longSide: Int, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text(stringResource(R.string.tracer_size_original, longSide), maxLines = 1) },
        )
        ImageTracer.sizeOptions(longSide).forEach { side ->
            FilterChip(
                selected = selected == side,
                onClick = { onSelect(side) },
                label = { Text(stringResource(R.string.tracer_size_px, side), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun DownloadButton(label: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Icon(XIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), maxLines = 1)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TracerCard(title: Int, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}
