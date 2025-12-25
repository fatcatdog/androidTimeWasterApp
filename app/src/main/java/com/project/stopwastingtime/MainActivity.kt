package com.project.stopwastingtime

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.project.stopwastingtime.ui.theme.StopWastingTimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.lifecycle.application
import android.app.Application // <-- ADD THIS IMPORT
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.ExperimentalPermissionsApi // Required for Accompanist


// --- The Main Activity (The App's Entry Point) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StopWastingTimeTheme {
                UsageTrackerScreen()
            }
        }
    }
}

// --- The ViewModel (Handles Business Logic and Data) ---
class UsageViewModel(application: Application, private val repository: AppLimitsRepository) : AndroidViewModel(application) {

    // MODIFIED: ViewModelFactory to inject the repository
    class UsageViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(UsageViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                // Pass the application context to the new constructor
                return UsageViewModel(context.applicationContext as Application, AppLimitsRepository(context)) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val _usageStats = MutableLiveData<List<AppUsage>>()
    val usageStats: LiveData<List<AppUsage>> = _usageStats

    private val _uiState = MutableLiveData<UsageUiState>(UsageUiState.Loading)
    val uiState: LiveData<UsageUiState> = _uiState

    // NEW: LiveData to hold the list of app limits
    private val _appLimits = MutableLiveData<List<AppLimit>>()
    val appLimits: LiveData<List<AppLimit>> = _appLimits

    fun loadUsageStats() {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                _uiState.postValue(UsageUiState.Loading)
                withContext(Dispatchers.IO) {
                    // All the original logic goes inside this 'if' block
                    val appOpsManager =
                        getApplication<Application>().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                    val mode = appOpsManager.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        getApplication<Application>().packageName
                    )
                    if (mode != AppOpsManager.MODE_ALLOWED) {
                        _uiState.postValue(UsageUiState.PermissionRequired)
                        return@withContext
                    }
                    val usageStatsManager = getApplication<Application>().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

                    val startTime = UsageMonitorWorker.getStartTimeForDailyReset()
                    val endTime = System.currentTimeMillis()

                    val stats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_BEST,
                        startTime,
                        endTime
                    )

                    if (stats.isNullOrEmpty()) {
                        _uiState.postValue(UsageUiState.NoData)
                        return@withContext
                    }

                    val pm = getApplication<Application>().packageManager

                    val aggregatedStats = stats
                        .groupBy { it.packageName } // Creates a Map<String, List<UsageStats>>
                        .mapValues { entry ->
                            // For each package, sum the totalTimeInForeground from all its entries
                            entry.value.sumOf { it.totalTimeInForeground }
                        }

                    val appUsageList = aggregatedStats
                        .filter { it.value > 1000 } // Filter by the aggregated time
                        .mapNotNull { (packageName, totalTimeInMillis) -> // Destructure the map entry
                            try {
                                val appInfo = pm.getApplicationInfo(packageName, 0)
                                AppUsage(
                                    appName = pm.getApplicationLabel(appInfo).toString(),
                                    packageName = packageName,
                                    usageTime = formatUsageTime(totalTimeInMillis), // Use the summed time
                                    appIcon = pm.getApplicationIcon(packageName),
                                    totalTimeInMillis = totalTimeInMillis // Use the summed time
                                )
                            } catch (e: PackageManager.NameNotFoundException) {
                                null
                            }
                        }
                        .sortedByDescending { it.totalTimeInMillis }

                    _usageStats.postValue(appUsageList)
                    _uiState.postValue(UsageUiState.Loaded)
                }
            }
        }
    }

    // NEW: Function to load the saved limits from the repository
    fun loadLimits() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _appLimits.postValue(repository.getLimits())
            }
        }
    }

    // NEW: Function to add or update an app limit
    fun setAppLimit(packageName: String, limitMinutes: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val currentLimits = repository.getLimits().toMutableList()
                val existingLimitIndex = currentLimits.indexOfFirst { it.packageName == packageName }

                if (existingLimitIndex != -1) {
                    // Update existing limit
                    if (limitMinutes > 0) {
                        currentLimits[existingLimitIndex] = AppLimit(packageName, limitMinutes)
                    } else {
                        // Remove limit if time is set to 0 or less
                        currentLimits.removeAt(existingLimitIndex)
                    }
                } else if (limitMinutes > 0) {
                    // Add new limit
                    currentLimits.add(AppLimit(packageName, limitMinutes))
                }

                repository.saveLimits(currentLimits)
                _appLimits.postValue(currentLimits) // Update the UI
            }
        }
    }

    private fun formatUsageTime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "< 1m"
        }
    }
}

// --- UI State Sealed Class (Unchanged) ---
sealed class UsageUiState {
    object Loading : UsageUiState()
    object PermissionRequired : UsageUiState()
    object NoData : UsageUiState()
    object Loaded : UsageUiState()
}

// --- The Main Screen Composable (The UI) ---
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
fun UsageTrackerScreen() {
    val context = LocalContext.current
    val viewModel: UsageViewModel = viewModel(factory = UsageViewModel.UsageViewModelFactory(context.applicationContext))
    // Use remember states to track if permissions are granted.
    // This will trigger a recomposition when the user returns to the app.
    var hasUsageAccess by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var hasOverlayPerms by remember { mutableStateOf(hasOverlayPermission(context)) }

    // --- THIS IS THE NEW PART ---
    // Hoist the notification permission state up to this level.
    val notificationPermissionState = rememberPermissionState(
        android.Manifest.permission.POST_NOTIFICATIONS
    )
    // The permission is considered granted if it's already granted OR if the device is older than Android 13.
    val hasNotificationPerms = notificationPermissionState.status.isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    // --- END OF NEW PART ---


    // This effect will re-check permissions when the user comes back to the app.
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            hasUsageAccess = hasUsageStatsPermission(context)
            hasOverlayPerms = hasOverlayPermission(context)
        }
    }

    if (hasUsageAccess && hasOverlayPerms && hasNotificationPerms) {
        // If all permissions are granted, show the main content (your app list).

        // --- THIS IS THE NEW LOGIC ---
        // When permissions are granted, start the Worker.
        // `LaunchedEffect` ensures this runs only once when this state is reached.
        LaunchedEffect(Unit) {
            val workRequest = OneTimeWorkRequestBuilder<UsageMonitorWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UsageMonitorWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Replace any existing worker
                workRequest
            )
        }
        // --- END OF NEW LOGIC ---
        MainContent(viewModel = viewModel)
    } else {
        // If any permission is missing, show the permission request screen.
        PermissionsRequiredScreen(
            hasUsageAccess = hasUsageAccess,
            hasOverlayAccess = hasOverlayPerms,
            // Pass the notification permission state down
            notificationPermissionState = notificationPermissionState,
            onGrantUsageAccessClick = {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
            },
            onGrantOverlayAccessClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsRequiredScreen(
    hasUsageAccess: Boolean,
    hasOverlayAccess: Boolean,
    notificationPermissionState: com.google.accompanist.permissions.PermissionState,
    onGrantUsageAccessClick: () -> Unit,
    onGrantOverlayAccessClick: () -> Unit
) {
    val showNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionState.status.isGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Show the button for "Usage Access" if it's not granted
        if (!hasUsageAccess) {
            Text(
                text = "This app needs usage access to monitor which apps you are using.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(onClick = onGrantUsageAccessClick) {
                Text("Grant Usage Access")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Show the button for "Display over other apps" if it's not granted
        if (!hasOverlayAccess) {
            Text(
                text = "This app needs permission to display over other apps to block them when the time limit is reached.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(onClick = onGrantOverlayAccessClick) {
                Text("Grant Display Over Other Apps")
            }
        }

        // --- THIS IS THE NEW UI ---
        // Show the button for "Notification" permission if it's not granted
        if (showNotificationPermission) {
            Text(
                text = "This app needs permission to show notifications when a time limit is reached.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(onClick = { notificationPermissionState.launchPermissionRequest() }) {
                Text("Grant Notification Permission")
            }
        }
        // --- END OF NEW UI ---
    }
}

// We need a simple lifecycle observer to refresh the permission state
@Composable
fun OnLifecycleEvent(onEvent: (owner: LifecycleOwner, event: Lifecycle.Event) -> Unit) {
    val eventHandler = rememberUpdatedState(onEvent)

    // --- THIS IS THE FIX ---
    // Get the lifecycleOwner directly in the @Composable context.
    // The 'remember' block around it is not necessary as LocalLifecycleOwner handles this.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { owner, event ->
            eventHandler.value(owner, event)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

// NEW: Main content with a tabbed layout
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainContent(viewModel: UsageViewModel) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Usage Today", "Limits")

    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(text = title) }
                )
            }
        }
        when (tabIndex) {
            0 -> UsageListScreen(viewModel)
            1 -> LimitsListScreen(viewModel)
        }
    }
}

// NEW: A screen to show the daily usage list
@Composable
fun UsageListScreen(viewModel: UsageViewModel) {
    // --- THIS IS THE FIX ---
    // Use LaunchedEffect to tell the ViewModel to load data when this screen is shown.
    // The `key1 = true` means this will run once when the composable enters the screen.
    LaunchedEffect(key1 = true) {
        viewModel.loadUsageStats()
        viewModel.loadLimits()
    }
    // --- END OF FIX ---

    val appUsages by viewModel.usageStats.observeAsState(emptyList())
    val appLimits by viewModel.appLimits.observeAsState(emptyList())

    // NEW: State to manage which app's limit dialog is open
    var showDialogForPackage by remember { mutableStateOf<String?>(null) }
    var selectedAppName by remember { mutableStateOf("") }
    var currentLimit by remember { mutableStateOf("") }

    if (appUsages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No apps were used in the last 24 hours.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(appUsages) { appUsage ->
                val limit = appLimits.find { it.packageName == appUsage.packageName }
                AppUsageRow(
                    appUsage = appUsage,
                    limitMinutes = limit?.limitMinutes,
                    onSetLimitClick = {
                        // Open the dialog when "Set Limit" is clicked
                        selectedAppName = appUsage.appName
                        currentLimit = limit?.limitMinutes?.toString() ?: ""
                        showDialogForPackage = appUsage.packageName
                    }
                )
            }
        }
    }

    // NEW: Show the dialog if a package is selected
    showDialogForPackage?.let { packageName ->
        SetLimitDialog(
            appName = selectedAppName,
            initialLimit = currentLimit,
            onDismiss = { showDialogForPackage = null },
            onConfirm = { newLimit ->
                viewModel.setAppLimit(packageName, newLimit)
                showDialogForPackage = null
            }
        )
    }
}

// MODIFIED: The AppUsageRow now includes the limit and a button
@Composable
fun AppUsageRow(appUsage: AppUsage, limitMinutes: Long?, onSetLimitClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(drawable = appUsage.appIcon), contentDescription = "${appUsage.appName} icon", modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = appUsage.appName, style = MaterialTheme.typography.bodyLarge)
            // NEW: Display the limit if it exists
            if (limitMinutes != null) {
                Text(text = "Limit: ${limitMinutes}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Text(text = appUsage.usageTime, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(8.dp))
        // NEW: Button to open the set limit dialog
        Button(onClick = onSetLimitClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Limit")
        }
    }
}

// NEW: A dialog for the user to enter a time limit
@Composable
fun SetLimitDialog(
    appName: String,
    initialLimit: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var limitInput by rememberSaveable { mutableStateOf(initialLimit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Set Limit for $appName") },
        text = {
            OutlinedTextField(
                value = limitInput,
                onValueChange = { limitInput = it.filter { char -> char.isDigit() } },
                label = { Text("Limit in minutes (0 to remove)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(limitInput.toLongOrNull() ?: 0)
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// NEW: A screen to show only apps with set limits
@Composable
fun LimitsListScreen(viewModel: UsageViewModel) {
    // --- ADD THE SAME FIX HERE ---
    LaunchedEffect(key1 = true) {
        viewModel.loadUsageStats()
        viewModel.loadLimits()
    }
    // --- END OF FIX ---

    val appUsages by viewModel.usageStats.observeAsState(emptyList())
    val appLimits by viewModel.appLimits.observeAsState(emptyList())

    if (appLimits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No app limits have been set.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(appLimits) { limit ->
                // Find the corresponding AppUsage object to get its name and icon
                val appUsage = appUsages.find { it.packageName == limit.packageName }
                if (appUsage != null) {
                    AppLimitRow(
                        appName = appUsage.appName,
                        appIcon = appUsage.appIcon,
                        limitMinutes = limit.limitMinutes
                    )
                }
            }
        }
    }
}

// NEW: A simple row to display an app and its limit
@Composable
fun AppLimitRow(appName: String, appIcon: Drawable, limitMinutes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(drawable = appIcon), contentDescription = "$appName icon", modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = appName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = "Limit: ${limitMinutes}m", style = MaterialTheme.typography.bodyMedium)
    }
}


// --- Screen to guide the user to grant permission (Unchanged) ---
@Composable
fun PermissionRequestScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Permission Required", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "This app needs access to your app usage data to function. Please grant the permission in the settings.", textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            Text("Grant Permission")
        }
    }
}
