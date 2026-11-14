package com.invictus.kodex.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.github.GitHubContributor
import com.invictus.kodex.core.github.GitHubContributorsRepository
import com.invictus.kodex.ui.icons.XIcons

private const val MAX_VISIBLE_CONTRIBUTORS = 8

private sealed interface ContributorsUiState {
    data object Loading : ContributorsUiState
    data object Error : ContributorsUiState
    data class Loaded(val contributors: List<GitHubContributor>) : ContributorsUiState
}

/**
 * About screen's "Developers" section: the repo's GitHub contributors (avatar and login, no commit
 * counts), top [MAX_VISIBLE_CONTRIBUTORS] with a "View all" link to GitHub's contributors graph.
 * The list is cached on disk for 72h by [GitHubContributorsRepository].
 */
@Composable
internal fun AboutContributorsSection(
    githubRepoUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var refreshRequest by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ContributorsUiState>(ContributorsUiState.Loading) }

    LaunchedEffect(refreshRequest) {
        state = ContributorsUiState.Loading
        GitHubContributorsRepository
            .contributors(context, forceRefresh = refreshRequest > 0)
            .onSuccess { state = ContributorsUiState.Loaded(it) }
            .onFailure { state = ContributorsUiState.Error }
    }

    val viewAllUrl = "${githubRepoUrl.trimEnd('/')}/graphs/contributors"

    Column(modifier = modifier) {
        Spacer(Modifier.height(8.dp))
        SettingsSectionHeader(title = stringResource(R.string.about_developers_title))
        SettingsSectionCard {
            when (val current = state) {
                ContributorsUiState.Loading -> ContributorsLoading()
                ContributorsUiState.Error -> ContributorsError(
                    onRetry = { refreshRequest++ },
                    onViewAll = { runCatching { uriHandler.openUri(viewAllUrl) } },
                )
                is ContributorsUiState.Loaded -> {
                    val visible = current.contributors.take(MAX_VISIBLE_CONTRIBUTORS)
                    if (visible.isEmpty()) {
                        Text(
                            text = stringResource(R.string.about_contributors_empty),
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        visible.forEachIndexed { index, contributor ->
                            ContributorRow(
                                contributor = contributor,
                                onClick = { runCatching { uriHandler.openUri(contributor.profileUrl) } },
                            )
                            if (index != visible.lastIndex) SettingsDivider()
                        }
                    }
                    SettingsDivider()
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(viewAllUrl) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.about_contributors_view_all))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorRow(contributor: GitHubContributor, onClick: () -> Unit) {
    val imageLoader = (LocalContext.current.applicationContext as KodexApp).container.avatarImageLoader
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = XIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            AsyncImage(
                model = contributor.avatarUrl ?: "https://github.com/${contributor.login}.png?size=128",
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = contributor.login,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = XIcons.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ContributorsLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.about_contributors_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContributorsError(onRetry: () -> Unit, onViewAll: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.about_contributors_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.about_contributors_retry)) }
            TextButton(onClick = onViewAll) { Text(stringResource(R.string.about_contributors_view_all)) }
        }
    }
}
