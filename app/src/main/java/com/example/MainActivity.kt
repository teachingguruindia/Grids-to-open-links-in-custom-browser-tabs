package com.example

import android.app.Application
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import com.example.data.AppDatabase
import com.example.data.FavoriteRepository
import com.example.data.FavoriteSite
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset

// Data model representing each premium site card
data class Site(
    val name: String,
    val url: String,
    val description: String,
    val iconUrl: String,
    val colorHex: String = "#6750A4"
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

// AndroidViewModel to preserve states and load offline and online lists safely
class SiteViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val favoriteRepository = FavoriteRepository(db.favoriteDao)

    private val _offlineSites = mutableStateOf<List<Site>>(emptyList())
    val offlineSites: State<List<Site>> = _offlineSites

    private val _onlineSites = mutableStateOf<List<Site>>(emptyList())
    val onlineSites: State<List<Site>> = _onlineSites

    // Flow of favorites retrieved reactively from SQLite Room
    val favoritesState: StateFlow<List<FavoriteSite>> = favoriteRepository.allFavorites
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _isSearchExpanded = mutableStateOf(false)
    val isSearchExpanded: State<Boolean> = _isSearchExpanded

    private val _isOfflineLoading = mutableStateOf(false)
    val isOfflineLoading: State<Boolean> = _isOfflineLoading

    private val _isOnlineLoading = mutableStateOf(false)
    val isOnlineLoading: State<Boolean> = _isOnlineLoading

    private val _offlineError = mutableStateOf<String?>(null)
    val offlineError: State<String?> = _offlineError

    private val _onlineError = mutableStateOf<String?>(null)
    val onlineError: State<String?> = _onlineError

    private val _selectedSite = mutableStateOf<Site?>(null)
    val selectedSite: State<Site?> = _selectedSite

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSearchExpanded(expanded: Boolean) {
        _isSearchExpanded.value = expanded
        if (!expanded) {
            _searchQuery.value = ""
        }
    }

    fun selectSite(site: Site) {
        _selectedSite.value = site
    }

    // Toggle favorite state inside database
    fun toggleFavorite(site: Site) {
        viewModelScope.launch {
            val isFav = favoriteRepository.isFavorite(site.url)
            if (isFav) {
                favoriteRepository.removeFavoriteByUrl(site.url)
            } else {
                favoriteRepository.addFavorite(
                    FavoriteSite(
                        url = site.url,
                        name = site.name,
                        description = site.description,
                        iconUrl = site.iconUrl,
                        colorHex = site.colorHex
                    )
                )
            }
        }
    }

    fun insertFavoriteDirectly(favorite: FavoriteSite) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(favorite)
        }
    }

    fun deleteFavoriteDirectly(url: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavoriteByUrl(url)
        }
    }

    fun updateFavorite(oldUrl: String, favorite: FavoriteSite) {
        viewModelScope.launch {
            if (oldUrl != favorite.url) {
                favoriteRepository.removeFavoriteByUrl(oldUrl)
            }
            favoriteRepository.addFavorite(favorite)
        }
    }

    fun loadAll(context: Context) {
        loadOfflineSites(context)
        loadOnlineSites()
    }

    fun loadOfflineSites(context: Context) {
        viewModelScope.launch {
            try {
                _isOfflineLoading.value = true
                val jsonString = withContext(Dispatchers.IO) {
                    context.assets.open("sites.json").bufferedReader().use { it.readText() }
                }
                val list = parseJsonToSites(jsonString)
                _offlineSites.value = list
                if (_selectedSite.value == null && list.isNotEmpty()) {
                    _selectedSite.value = list.first()
                }
                _offlineError.value = null
            } catch (e: Exception) {
                Log.e("SiteViewModel", "Failed to load offline sites", e)
                _offlineError.value = e.localizedMessage ?: "Unknown offline load error"
            } finally {
                _isOfflineLoading.value = false
            }
        }
    }

    fun loadOnlineSites() {
        viewModelScope.launch {
            try {
                _isOnlineLoading.value = true
                val jsonString = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://manojindia.com/projects/topzlinks1/onlinelinks.json")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.string()
                        } else null
                    }
                }
                if (jsonString != null) {
                    val list = parseJsonToSites(jsonString)
                    _onlineSites.value = list
                    _onlineError.value = null
                } else {
                    _onlineError.value = "Failed to fetch from server: response unsuccessful"
                }
            } catch (e: Exception) {
                Log.e("SiteViewModel", "Failed to load online sites", e)
                _onlineError.value = e.localizedMessage ?: "Network error"
            } finally {
                _isOnlineLoading.value = false
            }
        }
    }

    private fun parseJsonToSites(jsonString: String): List<Site> {
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<Site>()
        val colorPalette = listOf("#6750A4", "#0288D1", "#E91E63", "#009688", "#FF9800", "#9C27B0")
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val assignedColor = colorPalette[i % colorPalette.size]
            list.add(
                Site(
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    description = obj.optString("description", ""),
                    iconUrl = obj.optString("iconurl", ""),
                    colorHex = assignedColor
                )
            )
        }
        return list
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WebGridAppScreen(
    modifier: Modifier = Modifier,
    viewModel: SiteViewModel = viewModel()
) {
    val context = LocalContext.current
    val offlineSites by viewModel.offlineSites
    val onlineSites by viewModel.onlineSites
    val favorites by viewModel.favoritesState.collectAsStateWithLifecycle()
    val favoriteUrls = remember(favorites) { favorites.map { it.url }.toSet() }

    val searchQuery by viewModel.searchQuery
    val isSearchExpanded by viewModel.isSearchExpanded
    val isOfflineLoading by viewModel.isOfflineLoading
    val isOnlineLoading by viewModel.isOnlineLoading
    val offlineError by viewModel.offlineError
    val onlineError by viewModel.onlineError
    val selectedSite by viewModel.selectedSite

    val coroutineScope = rememberCoroutineScope()

    // Setup Swipable pager with three tabs: 0 -> Offline, 1 -> Online, 2 -> Favorite
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 3 }
    )

    var showAddDialog by remember { mutableStateOf(false) }
    var showOptionsDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var favoriteToManage by remember { mutableStateOf<FavoriteSite?>(null) }

    // Load static and dynamic links once at screen init
    LaunchedEffect(Unit) {
        viewModel.loadAll(context)
    }

    // Warm ivory/cream background styled to perfectly match the design palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFDF8F6))
    ) {
        // App Header: Title "Topz AI Tools", dynamic counts, and Top search icon action trigger!
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Topz AI Tools",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E),
                    letterSpacing = (-0.5).sp
                )
                val itemsSummaryStr = when (pagerState.currentPage) {
                    0 -> "${offlineSites.size} offline items"
                    1 -> "${onlineSites.size} server items"
                    else -> "${favorites.size} saved favorites"
                }
                Text(
                    text = itemsSummaryStr,
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🔍 Single Global Search button in the top bar!
                IconButton(
                    onClick = { viewModel.toggleSearchExpanded(!isSearchExpanded) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isSearchExpanded) Color(0xFFEADDFF) else Color(0x0C6750A4),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "Search sites button",
                        tint = Color(0xFF6750A4)
                    )
                }

                // Interactive profile icon avatar (M3 branding)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEADDFF))
                        .clickable { showPrivacyDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "👤",
                        fontSize = 18.sp,
                        color = Color(0xFF21005D),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Animated Search Input Bar shown optionally if search toggle is active
        AnimatedVisibility(
            visible = isSearchExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("site_search_input"),
                placeholder = { Text("Search offline, online or favorite sites...", fontSize = 14.sp, color = Color(0xFF49454F)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF49454F)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear text", tint = Color(0xFF49454F))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedContainerColor = Color(0xFFF7F2FA),
                    unfocusedContainerColor = Color(0x0F6750A4)
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Decider between regular Swipable Tab navigation or Unified Global Search Results display
        if (isSearchExpanded && searchQuery.isNotBlank()) {
            // Render beautiful classification of search queries across ALL datasets directly
            val matchingOffline = recuerdedFilteredList(offlineSites, searchQuery)
            val matchingOnline = recuerdedFilteredList(onlineSites, searchQuery)
            val matchingFavs = remember(favorites, searchQuery) {
                favorites.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                            it.description.contains(searchQuery, ignoreCase = true) ||
                            it.url.contains(searchQuery, ignoreCase = true)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                if (matchingOffline.isEmpty() && matchingOnline.isEmpty() && matchingFavs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No match found anywhere", fontWeight = FontWeight.SemiBold, color = Color(0xFF49454F))
                            Text("Try searching some other keyword", fontSize = 13.sp, color = Color(0xFF49454F).copy(alpha = 0.7f))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (matchingFavs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "MATCHING FAVORITES (${matchingFavs.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE91E63),
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            items(matchingFavs) { fSite ->
                                val siteRepresentation = remember(fSite) {
                                    Site(fSite.name, fSite.url, fSite.description, fSite.iconUrl, fSite.colorHex)
                                }
                                FavoriteItemRow(
                                    favorite = fSite,
                                    isFavorite = true,
                                    onToggleFav = { viewModel.toggleFavorite(siteRepresentation) },
                                    onSelect = { viewModel.selectSite(siteRepresentation) },
                                    onOpenDirectly = { openSiteInCustomTab(context, fSite.url) },
                                    onLongClick = {
                                        favoriteToManage = fSite
                                        showOptionsDialog = true
                                    }
                                )
                            }
                        }

                        if (matchingOffline.isNotEmpty()) {
                            item {
                                Text(
                                    text = "MATCHING OFFLINE LINKS (${matchingOffline.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4),
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            items(matchingOffline) { site ->
                                val holdingIsFav = favoriteUrls.contains(site.url)
                                val favObj = remember(site) { FavoriteSite(site.url, site.name, site.description, site.iconUrl, site.colorHex) }
                                FavoriteItemRow(
                                    favorite = favObj,
                                    isFavorite = holdingIsFav,
                                    onToggleFav = { viewModel.toggleFavorite(site) },
                                    onSelect = { viewModel.selectSite(site) },
                                    onOpenDirectly = { openSiteInCustomTab(context, site.url) },
                                    onLongClick = {
                                        if (holdingIsFav) {
                                            favoriteToManage = favObj
                                            showOptionsDialog = true
                                        }
                                    }
                                )
                            }
                        }

                        if (matchingOnline.isNotEmpty()) {
                            item {
                                Text(
                                    text = "MATCHING ONLINE LINKS (${matchingOnline.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0288D1),
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            items(matchingOnline) { site ->
                                val holdingIsFav = favoriteUrls.contains(site.url)
                                val favObj = remember(site) { FavoriteSite(site.url, site.name, site.description, site.iconUrl, site.colorHex) }
                                FavoriteItemRow(
                                    favorite = favObj,
                                    isFavorite = holdingIsFav,
                                    onToggleFav = { viewModel.toggleFavorite(site) },
                                    onSelect = { viewModel.selectSite(site) },
                                    onOpenDirectly = { openSiteInCustomTab(context, site.url) },
                                    onLongClick = {
                                        if (holdingIsFav) {
                                            favoriteToManage = favObj
                                            showOptionsDialog = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Show standard tabs for navigation if search query is empty
            val tabTitles = listOf("Offline", "Online", "Favorite")
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color(0xFFFDF8F6),
                contentColor = Color(0xFF6750A4),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        color = Color(0xFF6750A4)
                    )
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        },
                        selectedContentColor = Color(0xFF6750A4),
                        unselectedContentColor = Color(0xFF49454F)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Box wrapper to allow floating a plus Floating Action Button dynamically on the Favorite tab list
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Swipeable Pager for Offline, Online, and Favorite Tabs
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            // OFFLINE TAB: Loaded from local sites.json assets in an adaptive dynamic grid
                            OfflineTabContent(
                                isLoading = isOfflineLoading,
                                errorMessage = offlineError,
                                sitesList = offlineSites,
                                favoriteUrls = favoriteUrls,
                                selectedSite = selectedSite,
                                onSelectSite = { viewModel.selectSite(it) },
                                onToggleFav = { viewModel.toggleFavorite(it) },
                                onOpenDirectly = { openSiteInCustomTab(context, it.url) }
                            )
                        }
                        1 -> {
                            // ONLINE TAB: Loaded dynamically from URL in an adaptive layout
                            OnlineTabContent(
                                isLoading = isOnlineLoading,
                                errorMessage = onlineError,
                                sitesList = onlineSites,
                                favoriteUrls = favoriteUrls,
                                selectedSite = selectedSite,
                                onSelectSite = { viewModel.selectSite(it) },
                                onToggleFav = { viewModel.toggleFavorite(it) },
                                onOpenDirectly = { openSiteInCustomTab(context, it.url) },
                                onRetry = { viewModel.loadOnlineSites() }
                            )
                        }
                        2 -> {
                            // FAVORITE TAB: Displayed beautifully in LIST VIEW style (Left icon, right content)
                            FavoriteTabContent(
                                favorites = favorites,
                                onToggleFav = { siteRepresentation -> viewModel.toggleFavorite(siteRepresentation) },
                                onSelectSite = { siteRepresentation -> viewModel.selectSite(siteRepresentation) },
                                onOpenDirectly = { url -> openSiteInCustomTab(context, url) },
                                onLongClick = { favoriteSite ->
                                    favoriteToManage = favoriteSite
                                    showOptionsDialog = true
                                }
                            )
                        }
                    }
                }

                // Plus Floating Icon (visible only in Favorite section with a scale transition)
                androidx.compose.animation.AnimatedVisibility(
                    visible = pagerState.currentPage == 2,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp)
                ) {
                    FloatingActionButton(
                        onClick = { showAddDialog = true },
                        containerColor = Color(0xFF6750A4),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("add_favorite_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add custom favorite link"
                        )
                    }
                }
            }
        }

        // Selected Site Summary Card detailed at screen bottom for fast launch actions
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
                    // Accent Key Vector
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

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE SELECTION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = activeSite.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1D1B1E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Easy Toggle favorite right inside selection panel
                    val selectingIsFav = favoriteUrls.contains(activeSite.url)
                    IconButton(onClick = { viewModel.toggleFavorite(activeSite) }) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Toggle favorite status",
                            tint = if (selectingIsFav) Color(0xFFFFB300) else Color(0x3F49454F)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Custom Google Chrome tab launcher button
                Button(
                    onClick = { openSiteInCustomTab(context, activeSite.url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
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
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Custom Navigation Bar simulation keeping things gorgeous
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(Color(0xFFF3EDF7))
                .border(width = 1.dp, color = Color(0xFFE7E0EC))
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .background(
                            if (pagerState.currentPage == 0) Color(0xFFEADDFF) else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Offline Library",
                        tint = if (pagerState.currentPage == 0) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }
                Text(text = "Offline", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B1E))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .background(
                            if (pagerState.currentPage == 1) Color(0xFFEADDFF) else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Online Directory",
                        tint = if (pagerState.currentPage == 1) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }
                Text(text = "Online", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF49454F))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(2) }
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .background(
                            if (pagerState.currentPage == 2) Color(0xFFEADDFF) else Color.Transparent,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorites",
                        tint = if (pagerState.currentPage == 2) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                }
                Text(text = "Favorites", fontSize = 11.sp, fontStyle = null, fontWeight = FontWeight.Medium, color = Color(0xFF49454F))
            }
        }

        // --- DIALOGS HANDLING ---
        if (showAddDialog) {
            CustomAddFavoriteDialog(
                onDismiss = { showAddDialog = false },
                onSave = { name, url, desc ->
                    viewModel.insertFavoriteDirectly(
                        FavoriteSite(
                            url = url,
                            name = name,
                            description = desc,
                            iconUrl = "", // Empty iconUrl for custom/entered shortcuts
                            colorHex = "#9C27B0", // Premium purple style for custom shortcuts
                            isCustom = true
                        )
                    )
                    showAddDialog = false
                }
            )
        }

        if (showOptionsDialog && favoriteToManage != null) {
            val fav = favoriteToManage!!
            ManageFavoriteOptionsDialog(
                favorite = fav,
                onDismiss = { showOptionsDialog = false },
                onEdit = {
                    showOptionsDialog = false
                    showEditDialog = true
                },
                onDelete = {
                    viewModel.deleteFavoriteDirectly(fav.url)
                    showOptionsDialog = false
                    favoriteToManage = null
                }
            )
        }

        if (showEditDialog && favoriteToManage != null) {
            val fav = favoriteToManage!!
            EditFavoriteDialog(
                favorite = fav,
                onDismiss = {
                    showEditDialog = false
                    favoriteToManage = null
                },
                onSave = { newName, newUrl, newDesc ->
                    viewModel.updateFavorite(
                        oldUrl = fav.url,
                        favorite = FavoriteSite(
                            url = newUrl,
                            name = newName,
                            description = newDesc,
                            iconUrl = fav.iconUrl,
                            colorHex = fav.colorHex,
                            isCustom = true
                        )
                    )
                    showEditDialog = false
                    favoriteToManage = null
                }
            )
        }

        if (showPrivacyDialog) {
            PrivacyPolicyDialog(onDismiss = { showPrivacyDialog = false })
        }
    }
}

@Composable
fun OfflineTabContent(
    isLoading: Boolean,
    errorMessage: String?,
    sitesList: List<Site>,
    favoriteUrls: Set<String>,
    selectedSite: Site?,
    onSelectSite: (Site) -> Unit,
    onToggleFav: (Site) -> Unit,
    onOpenDirectly: (Site) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = Color(0xFF6750A4),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            errorMessage != null -> {
                Text(
                    text = "Offline assets reading error:\n$errorMessage",
                    color = Color.Red,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    textAlign = TextAlign.Center
                )
            }
            sitesList.isEmpty() -> {
                Text(
                    text = "No offline bookmarks found",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF49454F)
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("sites_grid")
                ) {
                    items(sitesList) { site ->
                        val isSelected = selectedSite?.url == site.url
                        val isFavorite = favoriteUrls.contains(site.url)
                        SiteCard(
                            site = site,
                            isSelected = isSelected,
                            isFavorite = isFavorite,
                            onSelect = { onSelectSite(site) },
                            onToggleFavorite = { onToggleFav(site) },
                            onOpenDirectly = { onOpenDirectly(site) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineTabContent(
    isLoading: Boolean,
    errorMessage: String?,
    sitesList: List<Site>,
    favoriteUrls: Set<String>,
    selectedSite: Site?,
    onSelectSite: (Site) -> Unit,
    onToggleFav: (Site) -> Unit,
    onOpenDirectly: (Site) -> Unit,
    onRetry: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    color = Color(0xFF6750A4),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Unable to read server list:\n$errorMessage",
                        color = Color(0xFF601410),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                    ) {
                        Text("Retry Connection")
                    }
                }
            }
            sitesList.isEmpty() -> {
                Text(
                    text = "No online links found on server",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF49454F)
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sitesList) { site ->
                        val isSelected = selectedSite?.url == site.url
                        val isFavorite = favoriteUrls.contains(site.url)
                        SiteCard(
                            site = site,
                            isSelected = isSelected,
                            isFavorite = isFavorite,
                            onSelect = { onSelectSite(site) },
                            onToggleFavorite = { onToggleFav(site) },
                            onOpenDirectly = { onOpenDirectly(site) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteTabContent(
    favorites: List<FavoriteSite>,
    onToggleFav: (Site) -> Unit,
    onSelectSite: (Site) -> Unit,
    onOpenDirectly: (String) -> Unit,
    onLongClick: (FavoriteSite) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (favorites.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    color = Color(0x0C6750A4),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Zero state icon",
                            tint = Color(0xFF6750A4).copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No saved favorites yet",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap the star icon on any offline or online bookmarks to save them here for quick access.",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = Color(0xFF49454F)
                )
            }
        } else {
            // Favorite Tab shows beautifully in custom LIST VIEW style!
            // Left side: icon | Right side: Name, toggle star
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favorites) { favorite ->
                    val siteRepresentation = remember(favorite) {
                        Site(
                            name = favorite.name,
                            url = favorite.url,
                            description = favorite.description,
                            iconUrl = favorite.iconUrl,
                            colorHex = favorite.colorHex
                        )
                    }
                    FavoriteItemRow(
                        favorite = favorite,
                        isFavorite = true,
                        onToggleFav = { onToggleFav(siteRepresentation) },
                        onSelect = { onSelectSite(siteRepresentation) },
                        onOpenDirectly = { onOpenDirectly(favorite.url) },
                        onLongClick = { onLongClick(favorite) }
                    )
                }
            }
        }
    }
}

// Favorite List style representation row implementation
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteItemRow(
    favorite: FavoriteSite,
    isFavorite: Boolean,
    onToggleFav: () -> Unit,
    onSelect: () -> Unit,
    onOpenDirectly: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val siteColor = remember(favorite.colorHex) {
        try {
            Color(android.graphics.Color.parseColor(favorite.colorHex))
        } catch (e: Exception) {
            Color(0xFF6750A4)
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongClick
            )
            .border(
                width = 1.dp,
                color = Color(0xFFCAC4D0).copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left Side: Circular Image or Symbol
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(siteColor.copy(alpha = 0.15f), shape = CircleShape)
                    .border(width = 1.5.dp, color = siteColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (favorite.iconUrl.isNotEmpty()) {
                    SubcomposeAsyncImage(
                        model = favorite.iconUrl,
                        contentDescription = favorite.name + " avatar",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(CircleShape),
                        loading = {
                            CircularProgressIndicator(color = siteColor, strokeWidth = 1.5.dp)
                        },
                        error = {
                            Text(
                                text = favorite.name.firstOrNull()?.toString()?.uppercase() ?: "S",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = siteColor
                            )
                        }
                    )
                } else {
                    Text(
                        text = favorite.name.firstOrNull()?.toString()?.uppercase() ?: "S",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = siteColor
                    )
                }
            }

            // Central section: Right side name & info show
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = favorite.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1D1B1E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = favorite.description,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF49454F),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right side buttons: Open URL button & toggle favorite star
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Open quick link immediately in Custom browser
                IconButton(onClick = onOpenDirectly) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Visit Site",
                        tint = siteColor
                    )
                }

                // Add to Favorite star
                IconButton(onClick = onToggleFav) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Unfavorite link",
                        tint = if (isFavorite) Color(0xFFFFB300) else Color(0x3FCAC4D0)
                    )
                }
            }
        }
    }
}

// Grid Card representation for Offline and Online tabs preserving:
// - "top circular icon below name, and on button click let open url in google Custom browser"
// - "add to favorite icon on each data online or offline"
@Composable
fun SiteCard(
    site: Site,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
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

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFF7F2FA) else Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 216.dp)
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
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // First display: Name at the top and Star toggle overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = site.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.2.sp,
                    color = Color(0xFF1D1B1E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { onToggleFavorite() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorite link toggle",
                        tint = if (isFavorite) Color(0xFFFFB300) else Color(0x3FCAC4D0),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Second display: Circular Icon directly BELOW the Name (as requested)
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(siteColor.copy(alpha = 0.15f), shape = CircleShape)
                    .border(width = 2.dp, color = siteColor, shape = CircleShape)
                    .shadow(elevation = 2.dp, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (site.iconUrl.isNotEmpty()) {
                    SubcomposeAsyncImage(
                        model = site.iconUrl,
                        contentDescription = site.name + " avatar icon",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(CircleShape),
                        loading = {
                            CircularProgressIndicator(
                                color = siteColor,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        error = {
                            Text(
                                text = site.name.firstOrNull()?.toString()?.uppercase() ?: "S",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = siteColor
                            )
                        }
                    )
                } else {
                    Text(
                        text = site.name.firstOrNull()?.toString()?.uppercase() ?: "S",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = siteColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Third display: Description supporting text
            Text(
                text = site.description,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color(0xFF49454F),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Fourth display: "Visit" Button executing Custom Tabs
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
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
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

// Helper filter list remembering function
@Composable
fun recuerdedFilteredList(sitesList: List<Site>, query: String): List<Site> {
    return remember(sitesList, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            sitesList.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true) ||
                        it.url.contains(query, ignoreCase = true)
            }
        }
    }
}

// Safe method opening URLs utilizing CustomTabs, with a clean FLAG_ACTIVITY_NEW_TASK context fallback
fun openSiteInCustomTab(context: Context, url: String) {
    try {
        if (url.startsWith("mailto:", ignoreCase = true)) {
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                // Securely copy email to clipboard if no mail app exists on emulator
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("email", url.substringAfter("mailto:"))
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, "Support email copied to clipboard!", android.widget.Toast.LENGTH_LONG).show()
                Log.e("CustomTab", "Failed to launch mail app directly, copied to clipboard instead", ex)
            }
            return
        }
        val parsedUri = Uri.parse(url)
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .setShowTitle(true)
            .build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, parsedUri)
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            Log.e("CustomTab", "Failed to launch webpage fallback browser", ex)
        }
    }
}

// --- DIALOG COMPOSABLES IMPLEMENTATIONS ---

@Composable
fun CustomAddFavoriteDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, desc: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    
    var nameError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFDF8F6),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEADDFF), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add custom link icon",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Add Custom Link",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Create a custom shortcut bookmark inside your favorites page.",
                    fontSize = 12.sp,
                    color = Color(0xFF49454F)
                )
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        nameError = false
                    },
                    label = { Text("Shortcut Name *") },
                    placeholder = { Text("e.g. My Portfolio") },
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text("Name is required", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        urlError = false
                    },
                    label = { Text("URL *") },
                    placeholder = { Text("e.g. https://mywebsite.com") },
                    isError = urlError,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (urlError) {
                    Text("Enter a valid URL", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    placeholder = { Text("e.g. My personal website lookup") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isNameInvalid = name.isBlank()
                    val isUrlInvalid = url.trim().isBlank()
                    nameError = isNameInvalid
                    urlError = isUrlInvalid
                    if (!isNameInvalid && !isUrlInvalid) {
                        val preparedUrl = url.trim()
                        val formattedUrl = if (!preparedUrl.startsWith("http://") && !preparedUrl.startsWith("https://")) {
                            "https://$preparedUrl"
                        } else {
                            preparedUrl
                        }
                        val descVal = if (description.isBlank()) "Custom added link" else description
                        onSave(name, formattedUrl, descVal)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                shape = CircleShape
            ) {
                Text("Add Shortcut", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF6750A4))
            ) {
                Text("Cancel", fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
fun ManageFavoriteOptionsDialog(
    favorite: FavoriteSite,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFDF8F6),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFF7F2FA), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Manage link options icon",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Manage Shortcut",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = favorite.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF1D1B1E)
                )
                Text(
                    text = favorite.url,
                    fontSize = 12.sp,
                    color = Color(0xFF49454F).copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB3261E)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete icon", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (favorite.isCustom) "Delete" else "Remove", fontWeight = FontWeight.Bold)
                    }
                }

                if (favorite.isCustom) {
                    Button(
                        onClick = onEdit,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit icon", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF49454F))
            ) {
                Text("Close", fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
fun EditFavoriteDialog(
    favorite: FavoriteSite,
    onDismiss: () -> Unit,
    onSave: (newName: String, newUrl: String, newDesc: String) -> Unit
) {
    var name by remember { mutableStateOf(favorite.name) }
    var url by remember { mutableStateOf(favorite.url) }
    var description by remember { mutableStateOf(favorite.description) }

    var nameError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFDF8F6),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEADDFF), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit link icon",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Edit Shortcut",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Modify details for this saved dynamic favorite bookmark.",
                    fontSize = 12.sp,
                    color = Color(0xFF49454F)
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        nameError = false
                    },
                    label = { Text("Shortcut Name *") },
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError) {
                    Text("Name is required", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        urlError = false
                    },
                    label = { Text("URL *") },
                    isError = urlError,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (urlError) {
                    Text("Enter a valid URL", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedLabelColor = Color(0xFF6750A4),
                        unfocusedLabelColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isNameInvalid = name.isBlank()
                    val isUrlInvalid = url.trim().isBlank()
                    nameError = isNameInvalid
                    urlError = isUrlInvalid
                    if (!isNameInvalid && !isUrlInvalid) {
                        val preparedUrl = url.trim()
                        val formattedUrl = if (!preparedUrl.startsWith("http://") && !preparedUrl.startsWith("https://")) {
                            "https://$preparedUrl"
                        } else {
                            preparedUrl
                        }
                        onSave(name, formattedUrl, description)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                shape = CircleShape
            ) {
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF6750A4))
            ) {
                Text("Cancel", fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFDF8F6),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEADDFF), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Privacy Policy Icon",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Privacy & Data",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Privacy Policy",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                Text(
                    text = "Last updated: May 26, 2026",
                    fontSize = 12.sp,
                    color = Color(0xFF49454F).copy(alpha = 0.7f)
                )
                Text(
                    text = "Welcome to Topz AI Tools. We value your privacy and are committed to protecting your personal data.",
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "1. Information We Collect",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                Text(
                    text = "We do not collect any personal identifier details automatically. When you add dynamic shortcuts or manage bookmarks, this data is stored locally on your device using a secure, offline Room Database structure.",
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "2. How Your Data is Used",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    BulletPointItem("To persist your custom shortcuts and favorites choices across app launches.")
                    BulletPointItem("To enable quick custom-tab preview browsing.")
                    BulletPointItem("To optimize local global lookups and keyword queries.")
                }
                
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "3. Data Security",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                Text(
                    text = "Since your favorites list and preferences are entirely stored on-device, you have full controller ownership of your databases. This app does not upload your links to external servers or telemetry systems.",
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "4. Contact Us",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D1B1E)
                )
                
                val context = LocalContext.current
                Text(
                    text = "If you have any questions, feel free to contact our support team directly:",
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
                
                Button(
                    onClick = { openSiteInCustomTab(context, "mailto:support@topz.ai") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x146750A4),
                        contentColor = Color(0xFF6750A4)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("email_support_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Email support team",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "support@topz.ai",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                shape = CircleShape
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun BulletPointItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B1E))
        Text(text, fontSize = 14.sp, color = Color(0xFF49454F))
    }
}
