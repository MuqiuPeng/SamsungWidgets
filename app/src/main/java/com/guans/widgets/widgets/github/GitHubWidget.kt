package com.guans.widgets.widgets.github

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.guans.widgets.MainActivity
import com.guans.widgets.R
import com.guans.widgets.data.CiState
import com.guans.widgets.data.GitHub
import com.guans.widgets.data.RepoStatus
import com.guans.widgets.data.formatElapsed
import com.guans.widgets.ui.Destination
import com.guans.widgets.widgets.common.WidgetContainer

class GitHubWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Content(currentState())
            }
        }
    }
}

class GitHubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GitHubWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GitHubScheduler.schedule(context)
        GitHubScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        GitHubScheduler.cancel(context)
    }
}

class GitHubRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GitHubUpdater.refresh(context)
    }
}

@Composable
private fun Content(state: Preferences) {
    val context = LocalContext.current
    // tick 每次推送都会变，用它触发重绘
    state[GitHubKeys.TICK]
    val loading = state[GitHubKeys.LOADING] == true
    val now = System.currentTimeMillis()
    val cached = GitHub.cached(context)
    val roomy = LocalSize.current.width >= 220.dp
    val openApp = actionStartActivity<MainActivity>(actionParametersOf(MainActivity.DESTINATION to Destination.GitHub.name))
    val status = when {
        loading -> context.getString(R.string.status_updating)
        cached.error == "rate_limited" -> context.getString(R.string.github_rate_limited)
        cached.error != null -> context.getString(R.string.status_failed)
        cached.updatedAt > 0 -> formatElapsed(context, now - cached.updatedAt)
        else -> ""
    }

    WidgetContainer(Destination.GitHub) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.github_widget_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = status,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (cached.error != null && !loading) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = context.getString(R.string.action_refresh),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(22.dp).clickable(actionRunCallback<GitHubRefreshAction>()),
            )
        }
        Spacer(GlanceModifier.height(6.dp))

        if (cached.statuses.isEmpty()) {
            Text(
                text = context.getString(if (GitHub.repos(context).isEmpty()) R.string.github_widget_empty else R.string.widget_loading),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.clickable(openApp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(cached.statuses, itemId = { it.fullName.hashCode().toLong() }) { repo ->
                    RepoRow(context, repo, now, roomy)
                }
            }
        }
    }
}

@Composable
private fun RepoRow(context: Context, repo: RepoStatus, now: Long, roomy: Boolean) {
    val open = actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${repo.fullName}")))
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ciMark(repo.ci),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ciColor(repo.ci)),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = repo.name,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = repo.release ?: "—",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
        }
        val details = buildList {
            if (repo.pushedAt > 0) add(context.getString(R.string.github_pushed, formatElapsed(context, now - repo.pushedAt)))
            if (repo.openIssues > 0) add(context.getString(R.string.github_issues, repo.openIssues))
            if (roomy && repo.stars > 0) add("★ ${repo.stars}")
        }
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 18.dp),
            )
        }
    }
}

private fun ciMark(ci: CiState): String = when (ci) {
    CiState.SUCCESS -> "✓"
    CiState.FAILURE -> "✕"
    CiState.RUNNING -> "●"
    CiState.NONE -> "○"
}

private val CI_GREEN = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF5BD27A))
private val CI_RED = ColorProvider(day = Color(0xFFD32F2F), night = Color(0xFFFF6B6B))
private val CI_AMBER = ColorProvider(day = Color(0xFFB26A00), night = Color(0xFFFFC14D))

@Composable
private fun ciColor(ci: CiState) = when (ci) {
    CiState.SUCCESS -> CI_GREEN
    CiState.FAILURE -> CI_RED
    CiState.RUNNING -> CI_AMBER
    CiState.NONE -> GlanceTheme.colors.onSurfaceVariant
}
