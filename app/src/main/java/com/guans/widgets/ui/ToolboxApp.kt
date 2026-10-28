package com.guans.widgets.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.guans.widgets.R
import kotlinx.coroutines.launch

/** 抽屉里的页面。新增小工具时在 WIDGETS 里加一项。 */
enum class Destination { ExchangeRate, Settings }

private val WIDGETS = listOf(Destination.ExchangeRate)

/** 应用骨架：左侧抽屉分为“小工具”和“设置”两部分。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxApp(onAddToHome: () -> Unit, checkingUpdates: Boolean, onCheckUpdates: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(Destination.ExchangeRate) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = drawerState.isClosed && destination != Destination.ExchangeRate) {
        destination = Destination.ExchangeRate
    }

    fun go(to: Destination) {
        destination = to
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
                    )
                    Text(
                        stringResource(R.string.nav_widgets),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    WIDGETS.forEach { widget ->
                        NavigationDrawerItem(
                            icon = { Icon(painterResource(R.drawable.ic_exchange), contentDescription = null) },
                            label = { Text(title(widget)) },
                            selected = destination == widget,
                            onClick = { go(widget) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(title(Destination.Settings)) },
                        selected = destination == Destination.Settings,
                        onClick = { go(Destination.Settings) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title(destination)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    },
                )
            },
        ) { padding ->
            when (destination) {
                Destination.ExchangeRate -> ExchangeRateScreen(padding, onAddToHome)
                Destination.Settings -> AppSettingsScreen(padding, checkingUpdates, onCheckUpdates)
            }
        }
    }
}

@Composable
private fun title(destination: Destination): String = when (destination) {
    Destination.ExchangeRate -> stringResource(R.string.settings_title)
    Destination.Settings -> stringResource(R.string.nav_settings)
}

/** 各页面共用的内容边距：左右 16dp，上下避开顶栏和系统栏。 */
fun screenPadding(scaffold: PaddingValues) = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    top = scaffold.calculateTopPadding(),
    bottom = scaffold.calculateBottomPadding() + 24.dp,
)

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
