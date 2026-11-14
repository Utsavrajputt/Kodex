package com.invictus.kodex.feature.settings

import com.invictus.kodex.ui.theme.ThemeSettings
import com.invictus.kodex.ui.theme.AppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/**
 * App identity, version, GitHub / Libraries buttons, support (UPI), developers (GitHub
 * contributors) and the license notice. Layout follows xmd's About screen, minus its Updates
 * section (Kodex has no in-app updater).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLibraries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    val githubUrl = stringResource(R.string.about_github_url)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.about_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AboutHero(
                versionText = stringResource(R.string.about_version_format, versionName),
                onLibrariesClick = onOpenLibraries,
                onGithubClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))) }
                },
            )

            Spacer(Modifier.height(8.dp))
            SettingsSectionHeader(title = stringResource(R.string.about_support_title))
            SupportCard()

            AboutContributorsSection(githubRepoUrl = githubUrl)

            Spacer(Modifier.height(8.dp))
            SettingsSectionHeader(title = stringResource(R.string.about_license_title))
            SettingsSectionCard(contentPadding = PaddingValues(16.dp)) {
                Row {
                    Icon(
                        imageVector = XIcons.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AGPL-3.0",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.about_license_body),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.about_made_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * Identity card on the same animated dark backdrop as xmd's About: icon with a soft glow, name, tagline,
 * version pill and two translucent, outlined action buttons.
 */
@Composable
private fun AboutHero(
    versionText: String,
    onLibrariesClick: () -> Unit,
    onGithubClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Aurora keeps its fixed blue/violet; every other theme tints the scene from its own colours.
    val aurora = ThemeSettings.theme == AppTheme.Aurora
    val heroPrimary = if (aurora) Color(0xFF5B93FF) else cs.primary
    val heroTertiary = if (aurora) Color(0xFF9B7BFF) else cs.tertiary
    val heroText = Color.White
    val heroShape = RoundedCornerShape(28.dp)

    Box(modifier = Modifier.fillMaxWidth().clip(heroShape)) {
        AboutHeaderBackground(heroPrimary, heroTertiary, Modifier.matchParentSize())
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val context = LocalContext.current
                val appIconBitmap = remember {
                    context.packageManager.getApplicationIcon(context.packageName)
                        .toBitmap()
                        .asImageBitmap()
                }
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(80.dp).background(
                            Brush.radialGradient(listOf(heroPrimary.copy(alpha = 0.45f), Color.Transparent)),
                            CircleShape,
                        ),
                    )
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(64.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = heroText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = heroText.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = heroPrimary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = versionText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = heroText,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val colors = ButtonDefaults.buttonColors(
                    containerColor = cs.surfaceContainerHigh.copy(alpha = 0.6f),
                    contentColor = heroText,
                )
                val border = BorderStroke(1.dp, heroPrimary.copy(alpha = 0.35f))
                Button(
                    onClick = onLibrariesClick,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = border,
                    colors = colors,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_library_cube),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_libraries_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Button(
                    onClick = onGithubClick,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = border,
                    colors = colors,
                ) {
                    Icon(
                        imageVector = XIcons.GitHub,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_github),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "Support Kodex" card: copyable UPI ID plus a button that opens the user's UPI app. */
@Composable
private fun SupportCard() {
    val context = LocalContext.current
    val upiId = stringResource(R.string.about_upi_id)
    SettingsSectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = XIcons.MonetizationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.about_support_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.about_support_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("kodex_upi_id", upiId))
                        Toast.makeText(context, R.string.about_upi_copied, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_upi_id_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = upiId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    imageVector = XIcons.ContentCopy,
                    contentDescription = stringResource(R.string.about_upi_copy),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                try {
                    val uri = Uri.parse("upi://pay?pa=$upiId&pn=Utsav%20Rajput&cu=INR")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.about_upi_no_app, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            Icon(XIcons.MonetizationOn, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(R.string.about_upi_send), fontWeight = FontWeight.SemiBold)
        }
    }
}
