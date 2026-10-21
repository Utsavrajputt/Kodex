package com.invictus.kodex.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.feature.workspace.UiText
import com.invictus.kodex.feature.workspace.asString
import com.invictus.kodex.ui.components.expressiveClickable
import com.invictus.kodex.ui.components.rememberFolderPickerLauncher
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * Bottom sheet for starting a project: a name and the folder to create it in, or a jump to Clone.
 * Layout: icon badge + title, name field, location card with a live "will be created at" preview,
 * a tall Create button, then an "or" divider and a Clone card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(
    defaultParent: File,
    error: UiText?,
    onCreate: (parent: File, name: String) -> Unit,
    onDismissError: () -> Unit,
    onClone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var parentPath by rememberSaveable { mutableStateOf(defaultParent.path) }
    val pickFolder = rememberFolderPickerLauncher { parentPath = it }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = { if (name.isNotBlank()) onCreate(File(parentPath), name) }
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: badge + title / subtitle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(XIcons.CreateNewFolder, contentDescription = null, tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        stringResource(R.string.new_project_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.new_project_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (error != null) onDismissError()
                },
                label = { Text(stringResource(R.string.new_project_name)) },
                leadingIcon = { Icon(XIcons.Folder, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                isError = error != null,
                supportingText = { Text(error?.asString() ?: stringResource(R.string.new_project_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )

            // Location card with the resulting path underneath.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(XIcons.FolderOpen, contentDescription = null, tint = cs.primary)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                stringResource(R.string.new_project_location),
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant,
                            )
                            Text(
                                parentPath,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(onClick = pickFolder, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.new_project_change))
                        }
                    }
                    if (name.isNotBlank()) {
                        HorizontalDivider(color = cs.outlineVariant)
                        Text(
                            text = stringResource(R.string.new_project_will_create) + "\n" +
                                File(parentPath, name.trim()).path,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Button(
                onClick = submit,
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(XIcons.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_project_create), style = MaterialTheme.typography.titleMedium)
            }

            // "or" divider.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
                Text(
                    stringResource(R.string.new_project_or),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
            }

            // Clone card.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .expressiveClickable(onClick = onClone)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(cs.onSecondaryContainer.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(XIcons.GitClone, contentDescription = null, tint = cs.onSecondaryContainer)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            stringResource(R.string.home_clone),
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSecondaryContainer,
                        )
                        Text(
                            stringResource(R.string.new_project_clone_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSecondaryContainer.copy(alpha = 0.75f),
                        )
                    }
                    Icon(XIcons.ChevronRight, contentDescription = null, tint = cs.onSecondaryContainer)
                }
            }
        }
    }
}
