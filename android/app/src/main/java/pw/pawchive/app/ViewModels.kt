package pw.pawchive.app

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pw.pawchive.data.*

class AppViewModel(val repo: PawRepository) : ViewModel() {
    val account = repo.account
    val later = repo.collected("later").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val blocked = repo.blockedAuthors().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun later(post: Post) = action("later:${post.key}") {
        val selected = later.value.none { it.key == post.key }
        repo.localCollection("later", post, selected)
        messages.emit(if (selected) "已加入稍后再看" else "已移出稍后再看")
    }
    fun block(post: Post) = action("block:${post.authorKey}") {
        val selected = blocked.value.none { it.key == post.authorKey }
        repo.localCollection("blocked", post, selected)
        messages.emit(if (selected) "已屏蔽作者，可在我的页面管理" else "已取消屏蔽")
    }
    val follows = repo.followed().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun unblock(author: Creator) = action("block:${author.key}") { repo.blockAuthor(author, false); messages.emit("已取消屏蔽") }
    val favorites = repo.collected("favorite").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = repo.collected("history").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val feed = repo.feed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transfers = repo.dao.downloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val hideMediaLessWorks = repo.hideMediaLessWorks.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hideSuspectedNovels = repo.hideSuspectedNovels.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val feedPulse = MutableStateFlow(0)
    val scrollToTop = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private var feedJob: Job? = null
    private val pendingActions = mutableSetOf<String>()
    init {
        viewModelScope.launch { repo.account.filter { !it.restoring }.map { it.owner }.distinctUntilChanged().collect {
            feedJob?.cancelAndJoin()
            repo.feedStatus.value = FeedStatus()
        } }
    }
    fun refreshFeed(manual: Boolean = true) {
        if (feedJob?.isActive == true) return
        feedJob = viewModelScope.launch { repo.refreshFeed(manual) }
    }
    fun action(key: String = "", block: suspend () -> Unit) {
        if (key.isNotEmpty() && !pendingActions.add(key)) return
        viewModelScope.launch {
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; messages.emit(e.message ?: "操作未成功，请重试") }
            finally { pendingActions.remove(key) }
        }
    }
    fun follow(author: Creator) = action("follow:${author.key}") { repo.follow(author, follows.value.none { it.key == author.key }) }
    fun favorite(post: Post) = action("favorite:${post.key}") { repo.favorite(post, favorites.value.none { it.key == post.key }) }
}

data class HomeState(val creators: List<Creator> = emptyList(), val posts: List<Post> = emptyList(), val loading: Boolean = false, val more: Boolean = true, val error: String? = null)
@OptIn(FlowPreview::class)
class HomeViewModel(private val repo: PawRepository, private val saved: SavedStateHandle, initialCategory: Int = 1) : ViewModel() {
    val query = saved.getStateFlow("query", "")
    val category = saved.getStateFlow("category", initialCategory)
    val filters = saved.getStateFlow("filters", wireJson.encodeToString(SearchFilters())).map { wireJson.decodeFromString<SearchFilters>(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, wireJson.decodeFromString(saved.get<String>("filters") ?: wireJson.encodeToString(SearchFilters())))
    fun filters(value: SearchFilters) { saved["filters"] = wireJson.encodeToString(value) }
    val state = MutableStateFlow(HomeState(loading = true))
    private var request: Job? = null
    private var debounceJob: Job? = null
    private var offset = 0
    private var requestId = 0L
    private var rawPosts = emptyList<Post>()
    private var mediaOnly = false
    private var hideNovels = false
    private fun visiblePosts(): List<Post> = rawPosts.visibleWorks(mediaOnly).filterNot {
        hideNovels && (query.value.isNotBlank() || !filters.value.isDefault) && repo.novelVerdicts.value[it.key] == true
    }
    suspend fun inspectVisible(posts: List<Post>) {
        if (hideNovels && (query.value.isNotBlank() || !filters.value.isDefault)) repo.inspectNovels(posts)
    }
    init {
        if (initialCategory == 1) viewModelScope.launch {
            combine(repo.hideMediaLessWorks, repo.hideSuspectedNovels, repo.novelVerdicts) { media, novels, _ -> media to novels }.collect { (enabled, novels) ->
                mediaOnly = enabled && (query.value.isNotBlank() || !filters.value.isDefault)
                hideNovels = novels
                state.value = state.value.copy(posts = visiblePosts())
            }
        }
        viewModelScope.launch {
            var previous: Triple<String, Int, SearchFilters>? = null
            combine(query, category, filters) { q, c, f -> Triple(q, c, f) }.distinctUntilChanged().collect { value ->
                val debounce = previous != null && previous!!.second == value.second && previous!!.third == value.third
                previous = value
                debounceJob?.cancel()
                if (debounce) {
                    // The old request loses ownership as soon as the query changes.
                    requestId++
                    request?.cancel()
                    state.value = state.value.copy(loading = true, error = null)
                    debounceJob = viewModelScope.launch { delay(250); load(false) }
                }
                else load(false)
            }
        }
    }
    fun query(value: String) { saved["query"] = value }
    fun category(value: Int) { if (value != category.value) saved["category"] = value }
    private var authorMaintenanceStarted = false
    fun load(append: Boolean, force: Boolean = false) {
        if (force && !append) debounceJob?.cancel()
        if (append && (state.value.loading || !state.value.more)) return
        request?.cancel(); val token = ++requestId; val q = query.value; val type = category.value; val options = filters.value
        val started = android.os.SystemClock.elapsedRealtime()
        if (!append) offset = 0
        state.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            var cacheHit = false
            try {
                if (type == 0) {
                    // Local sorts never wait on a full-site index download.
                    if (repo.dao.creatorCount() == 0) repo.refreshCreatorIndex()
                    repo.prepareAuthorFilters(options)
                    val batch = repo.searchAuthors(q, offset, options)
                    if (token == requestId) { state.value = HomeState(creators = if (append) (state.value.creators + batch).distinctBy { it.key } else batch, more = batch.size == 50); offset += batch.size }
                    if (!authorMaintenanceStarted) {
                        authorMaintenanceStarted = true
                        viewModelScope.launch {
                            runCatching {
                                repo.prepareAuthorFilters(SearchFilters(sort = "hot"))
                                val checked = repo.dao.metadata("index.checked")?.toLongOrNull() ?: 0
                                if (System.currentTimeMillis() - checked > 86_400_000) repo.refreshCreatorIndex()
                            }.onFailure { if (it is CancellationException) throw it }
                        }
                    }
                } else {
                    mediaOnly = repo.hideMediaLessWorks.first() && (q.isNotBlank() || !options.isDefault)
                    val popular = q.isBlank() && options.isDefault
                    val cache = if (popular) "public:home:popular" else "public:home:$q:${wireJson.encodeToString(options)}"
                    if (!append || popular) {
                        val cachedPage = if (popular) repo.cachedPopularPage(offset) else repo.cachedWorkPage(cache)
                        val cached = cachedPage?.page?.rawPosts ?: repo.cachedPosts(cache)
                        if (token != requestId) return@launch
                        if ((cachedPage != null && !popular) || cached.isNotEmpty()) {
                            val maxAge = if (popular) POPULAR_CACHE_MS else 300_000L
                            val fresh = cachedPage != null && System.currentTimeMillis() - cachedPage.checkedAt in 0L until maxAge && (popular || !force)
                            if (!append || fresh) {
                                rawPosts = if (append) (rawPosts + cached).distinctBy { it.key } else cached
                                state.value = HomeState(posts = visiblePosts(), loading = !fresh, more = cachedPage?.page?.more ?: true)
                            }
                            if (fresh) { offset = cachedPage!!.page.nextOffset; cacheHit = true; return@launch }
                        } else if (!append) { rawPosts = emptyList(); state.value = HomeState(loading = true) }
                        // Expired cache is a display fallback, not the next network offset.
                        if (!append) offset = 0
                    }
                    val batch = if (popular) repo.fetchPopularPosts(offset).let { WorkSearchPage(it, offset + it.size, it.size == 50) }
                        else withTimeoutOrNull(20_000) { repo.searchWorkPage(q, offset, mediaOnly, options, !append) } ?: throw java.io.IOException("网站响应较慢，请重试")
                    if (token == requestId) {
                        if (!append && !popular) repo.storeWorkPage(cache, batch)
                        if (token != requestId) return@launch
                        rawPosts = (if (append) (rawPosts + batch.rawPosts).distinctBy { it.key } else batch.rawPosts).distinctBy { it.key }.map { it.compact() }
                        state.value = HomeState(posts = visiblePosts(), more = batch.more); offset = batch.nextOffset
                    }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (token == requestId) state.value = state.value.copy(loading = false, error = e.message ?: "加载失败") }
            finally { android.util.Log.d("PawSearch", "page type=$type sort=${options.sort} media=${options.media} cached=$cacheHit elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}") }
        }
    }
}

data class AuthorState(val creator: Creator? = null, val posts: List<Post> = emptyList(), val loading: Boolean = true, val more: Boolean = true, val error: String? = null)
class AuthorViewModel(private val repo: PawRepository, val service: String, val user: String) : ViewModel() {
    val state = MutableStateFlow(AuthorState())
    private val _similarCreators = MutableStateFlow<List<Creator>>(emptyList())
    val similarCreators = _similarCreators.asStateFlow()
    private var offset = 0
    private var loading = false
    init {
        load(false)
        viewModelScope.launch {
            try {
                withTimeoutOrNull(8_000) { repo.similarCreators(service, user) }?.let { _similarCreators.value = it }
            } catch (e: java.io.IOException) {
                // Recommendations are optional. Keep the author's profile and works available.
            }
        }
    }
    fun load(append: Boolean) {
        if (loading || (append && !state.value.more)) return
        loading = true
        viewModelScope.launch {
            try {
                val cache = "public:author:$service:$user"
                if (!append) {
                    val rows = repo.cachedPosts(cache)
                    state.value = state.value.copy(posts = rows, loading = true)
                    state.value = state.value.copy(creator = repo.author(service, user))
                    offset = 0
                }
                val batch = repo.api.authorPosts(service, user, offset)
                state.value = state.value.copy(posts = if (append) (state.value.posts + batch).distinctBy { it.key } else batch, loading = false, more = batch.size == 50, error = null)
                offset += batch.size
                if (!append) repo.storePosts(cache, batch.map { it.compact() })
            } catch (e: Exception) { if (e is CancellationException) throw e; state.value = state.value.copy(loading = false, error = e.message ?: "加载失败") }
            finally { loading = false }
        }
    }
}
data class DetailState(val post: Post? = null, val creator: Creator? = null, val error: String? = null)
class DetailViewModel(private val repo: PawRepository, val service: String, val user: String, val id: String) : ViewModel() {
    val state = MutableStateFlow(DetailState())
    private var request: Job? = null
    init { load() }
    fun load() {
        if (request?.isActive == true) return
        request = viewModelScope.launch {
            state.value = state.value.copy(error = null)
            try {
                val cached = repo.cachedPosts("post:$service:$user:$id").firstOrNull()
                if (cached != null) state.value = state.value.copy(post = cached)
                val post = withTimeoutOrNull(20_000) { repo.post(service, user, id) }
                    ?: throw java.io.IOException("作品加载超时，请重试")
                // The author's profile should not block access to the work or media.
                state.value = state.value.copy(post = post)
                val creator = withTimeoutOrNull(8_000) {
                    try { repo.author(service, user) }
                    catch (e: Exception) { if (e is CancellationException) throw e; null }
                }
                if (creator != null) state.value = state.value.copy(creator = creator)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = state.value.copy(error = e.message ?: "加载失败")
            }
        }
    }
}

inline fun <reified T : ViewModel> vmFactory(crossinline make: (SavedStateHandle) -> T) = object : ViewModelProvider.Factory {
    override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: androidx.lifecycle.viewmodel.CreationExtras): VM {
        @Suppress("UNCHECKED_CAST") return make(extras.createSavedStateHandle()) as VM
    }
}
