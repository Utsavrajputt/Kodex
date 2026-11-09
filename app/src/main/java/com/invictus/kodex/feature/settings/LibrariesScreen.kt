package com.invictus.kodex.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/** The open-source libraries Kodex is built on: one card per library with what it is used for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraries = listOf(
        "Sora Editor" to stringResource(R.string.about_credit_sora_desc),
        "TextMate (tm4e)" to stringResource(R.string.about_credit_textmate_desc),
        "JGit" to stringResource(R.string.about_credit_jgit_desc),
        "Jetpack Compose & Material 3" to stringResource(R.string.about_credit_compose_desc),
        "Material Symbols" to stringResource(R.string.about_credit_symbols_desc),
        "Room" to stringResource(R.string.about_credit_room_desc),
        "DataStore" to stringResource(R.string.about_credit_datastore_desc),
        "Coil" to stringResource(R.string.about_credit_coil_desc),
        "OkHttp" to stringResource(R.string.about_credit_okhttp_desc),
        "AndroidSVG" to stringResource(R.string.about_credit_androidsvg_desc),
        "Apache Commons Compress" to stringResource(R.string.about_credit_commons_compress_desc),
        "Junrar" to stringResource(R.string.about_credit_junrar_desc),
        "AndroidX WebKit" to stringResource(R.string.about_credit_webkit_desc),
        "Kotlin Coroutines" to stringResource(R.string.about_credit_coroutines_desc),
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.about_libraries_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.about_credits_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            libraries.forEach { (name, desc) ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
