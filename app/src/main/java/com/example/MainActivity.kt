package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONArray

// Data model representing each premium site card
data class Site(
    val name: String,
    val url: String,
    val description: String,
    val iconText: String,
    val colorHex: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    WebGridAppScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

// ViewModel to load sites JSON asynchronously and safely manage states
class SiteViewModel : ViewModel() {
    private val _sites = mutableStateOf<List<Site>>(emptyList())
    val sites: State<List<Site>> = _sites

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _isLoading = mutableStateOf(true)
    val isLoading: State<Boolean> = _isLoading

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage

    private val _selectedSite = mutableStateOf<Site?>(null)
    val selectedSite: State<Site?> = _selectedSite

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSite(site: Site) {
        _selectedSite.value = site
    }

    fun loadSites(context: Context) {
        try {
            _isLoading.value = true
            val jsonString = context.assets.open("sites.json").bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<Site>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Site(
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        description = obj.optString("description", ""),
                        iconText = obj.optString("icon_text", "S"),
                        colorHex = obj.optString("color_hex", "#6750A4")
                    )
                )
            }
            _sites.value = list
            if (list.isNotEmpty() && _selectedSite.value == null) {
                _selectedSite.value = list.firstOrNull { it.name.lowercase().contains("topz") } ?: list.first()
            }
            _errorMessage.value = null
        } catch (e: Exception) {
            Log.e("SiteViewModel", "Failed to load sites JSON", e)
            _errorMessage.value = "Error parsing file: " + e.localizedMessage
        } finally {
            _isLoading.value = false
        }
    }
}

@Composable
fun WebGridAppScreen(
    modifier: Modifier = Modifier,
    viewModel: SiteViewModel = viewModel()
) {
    val context = LocalContext.current
    val sites by viewModel.sites
    val searchQuery by viewModel.searchQuery
    val isLoading by viewModel.isLoading
    val errorMessage by viewModel.errorMessage
    val selectedSite by viewModel.selectedSite

    // Load static data from JSON config assets on screen launch
    LaunchedEffect(Unit) {
        viewModel.loadSites(context)
    }

    // Filter sites based on search string
    val filteredSites = remember(sites, searchQuery) {
        if (searchQuery.isBlank()) {
            sites
        } else {
            sites.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true) ||
                        it.url.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Warm ivory/cream background styled to perfectly match the design palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFDF8F6))
    ) {
        // App Header: "Shortcuts", "8 items organized", user image on high-fidelity mockup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Shortcuts",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1D1B1E),
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "${sites.size} items organized",
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
            }

            // User Icon Badge styled to match the design spec HTML
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEADDFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "👤",
                    fontSize = 20.sp,
                    color = Color(0xFF21005D),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Beautiful custom-designed interactive search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("site_search_input"),
            placeholder = { Text("Search curated sites...", fontSize = 14.sp, color = Color(0xFF49454F)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFF49454F)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF6750A4),
                unfocusedBorderColor = Color(0xFFCAC4D0),
                focusedContainerColor = Color(0xFFF7F2FA),
                unfocusedContainerColor = Color(0x1A6750A4)
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Grid of Curated Bookmarks
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF6750A4))
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF2B8B5)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Initialization Error",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF601410)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = Color(0xFF601410),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                filteredSites.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No results found",
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF49454F)
                            )
                        }
                    }
                }
                else -> {
                    // Beautiful grid layout of elements
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("sites_grid")
                    ) {
                        items(filteredSites) { site ->
                            val isSelected = selectedSite == site
                            SiteCard(
                                site = site,
                                isSelected = isSelected,
                                onSelect = { viewModel.selectSite(site) },
                                onOpenDirectly = { openSiteInCustomTab(context, site.url) }
                            )
                        }
                    }
                }
            }
        }

        // Active Selection Card Panel inspired exactly by the design HTML
        selectedSite?.let { activeSite ->
            val activeColor = remember(activeSite.colorHex) {
                try {
                    Color(android.graphics.Color.parseColor(activeSite.colorHex))
                } catch (e: Exception) {
                    Color(0xFF6750A4)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .background(Color(0xFFF7F2FA), shape = RoundedCornerShape(28.dp))
                    .border(width = 1.dp, color = Color(0xFFCAC4D0), shape = RoundedCornerShape(28.dp))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Accent key visual
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(activeColor, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔗",
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }

                    Column {
                        Text(
                            text = "ACTIVE SELECTION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = activeSite.name + " (" + activeSite.url + ")",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1D1B1E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action triggar to launch Custom standard Tab
                Button(
                    onClick = { openSiteInCustomTab(context, activeSite.url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("launch_custom_tab_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = activeColor,
                        contentColor = Color.White
                    ),
                    shape = CircleShape,
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 0.dp
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "🌐 Open in Custom Browser",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Custom simulation footer navigation to fulfill layout completeness
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .background(Color(0xFFF3EDF7))
                .border(width = 1.dp, color = Color(0xFFE7E0EC))
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            // Home item (Active representation)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.clickable(onClick = {})
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .background(Color(0xFFEADDFF), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = Color(0xFF21005D),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Home",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
            }

            // Search simulation
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.clickable(onClick = { viewModel.updateSearchQuery("") })
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF49454F),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Search",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF49454F)
                )
            }

            // Settings simulation
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.clickable(onClick = {})
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF49454F),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "Settings",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF49454F)
                )
            }
        }
    }
}

// Gorgeous Site Grid card reflecting the prompt request:
// "On screen starting some list show in grid View with top circular icon below name, and on button click let open url in google Custom browser."
@Composable
fun SiteCard(
    site: Site,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenDirectly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val siteColor = remember(site.colorHex) {
        try {
            Color(android.graphics.Color.parseColor(site.colorHex))
        } catch (e: Exception) {
            Color(0xFF6750A4)
        }
    }

    // Interactive scale feedback
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFF7F2FA) else Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 210.dp)
            .clickable { onSelect() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) siteColor else Color(0xFFCAC4D0).copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp)
            )
            .testTag("site_card_${site.name.lowercase().replace(" ", "_")}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Name is displayed AT THE TOP
            Text(
                text = site.name,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFF1D1B1E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Circular Icon displayed directly BELOW the name as requested! Lovely rings!
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(siteColor.copy(alpha = 0.15f), shape = CircleShape)
                    .border(width = 2.dp, color = siteColor, shape = CircleShape)
                    .shadow(elevation = 1.dp, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = site.iconText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = siteColor
                )
            }

            // 3. Compact descriptions
            Text(
                text = site.description,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color(0xFF49454F),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Quick Action Button inside card that opens Google Custom tab
            Button(
                onClick = onOpenDirectly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag("visit_button_${site.name.lowercase().replace(" ", "_")}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = siteColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    text = "Visit Site",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }
    }
}

// Safe helper using Chrome Custom Tabs and fallback action
fun openSiteInCustomTab(context: Context, url: String) {
    try {
        val parsedUri = Uri.parse(url)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(context, parsedUri)
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (ex: Exception) {
            Log.e("CustomTab", "Failed to launch webpage fallback browser", ex)
        }
    }
}
