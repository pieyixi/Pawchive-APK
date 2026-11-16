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
    val feedPulse = MutableStateFlow(0)
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
    private var offset = 0
    private var requestId = 0L
    private var rawPosts = emptyList<Post>()
    private var mediaOnly = false
    init {
        if (initialCategory == 1) viewModelScope.launch {
            repo.hideMediaLessWorks.collect { enabled ->
                mediaOnly = enabled && (query.value.isNotBlank() || !filters.value.isDefault)
                state.value = state.value.copy(posts = rawPosts.visibleWorks(mediaOnly))
            }
        }
        viewModelScope.launch {
            var previous: Triple<String, Int, SearchFilters>? = null
            combine(query, category, filters) { q, c, f -> Triple(q, c, f) }.distinctUntilChanged().collectLatest { value ->
                if (previous != null && previous!!.second == value.second && previous!!.third == value.third) delay(250)
                previous = value
                load(false)
            }
        }
    }
    fun query(value: String) { saved["query"] = value }
    fun category(value: Int) { if (value != category.value) saved["category"] = value }
    fun load(append: Boolean) {
        if (append && (state.value.loading || !state.value.more)) return
        request?.cancel(); val token = ++requestId; val q = query.value; val type = category.value; val options = filters.value
        if (!append) { offset = 0; rawPosts = emptyList(); state.value = HomeState(loading = true) }
        else state.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try {
                if (type == 0) {
                    if (!append) {
                        repo.prepareAuthorFilters(options)
                        val cached = repo.searchAuthors(q, filters = options)
                        if (cached.isNotEmpty()) state.value = HomeState(creators = cached, loading = true)
                        val checked = repo.dao.metadata("index.checked")?.toLongOrNull() ?: 0
                        if (repo.dao.creatorCount() == 0 || System.currentTimeMillis() - checked > (if (options.sort == "updated") 120_000 else 86_400_000)) repo.refreshCreatorIndex()
                    }
                    val batch = repo.searchAuthors(q, offset, options)
                    if (token == requestId) { state.value = HomeState(creators = if (append) (state.value.creators + batch).distinctBy { it.key } else batch, more = batch.size == 50); offset += batch.size }
                } else {
                    mediaOnly = repo.hideMediaLessWorks.first() && (q.isNotBlank() || !options.isDefault)
                    val popular = q.isBlank() && options.isDefault
                    val cache = if (popular) "public:home:popular" else "public:home:$q:${wireJson.encodeToString(options)}"
                    if (!append) {
                        val cached = repo.cachedPosts(cache)
                        if (cached.isNotEmpty()) { rawPosts = cached; state.value = HomeState(posts = cached.visibleWorks(mediaOnly), loading = true) }
                    }
                    val batch = if (popular) repo.popularPosts(offset).let { WorkSearchPage(it, offset + it.size, it.size == 50) } else loadWorkSearchPage(repo.api, q, offset, mediaOnly, options)
                    if (token == requestId) {
                        rawPosts = if (append) (rawPosts + batch.rawPosts).distinctBy { it.key } else batch.rawPosts
                        state.value = HomeState(posts = rawPosts.visibleWorks(mediaOnly), more = batch.more); offset = batch.nextOffset
                        if (!append && !popular) repo.storePosts(cache, rawPosts.map { it.compact() })
                    }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; if (token == requestId) state.value = state.value.copy(loading = false, error = e.message ?: "加载失败") }
        }
    }
}

data class AuthorState(val creator: Creator? = null, val posts: List<Post> = emptyList(), val loading: Boolean = true, val more: Boolean = true, val error: String? = null)
class AuthorViewModel(private val repo: PawRepository, val service: String, val user: String) : ViewModel() {
    val state = MutableStateFlow(AuthorState())
    private var offset = 0
    private var loading = false
    init { load(false) }
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
    init { load() }
    fun load() { viewModelScope.launch {
        try {
            val cached = repo.cachedPosts("post:$service:$user:$id").firstOrNull()
            if (cached != null) state.value = state.value.copy(post = cached)
            val post = repo.post(service, user, id)
            state.value = DetailState(post, runCatching { repo.author(service, user) }.getOrNull())
        } catch (e: Exception) { if (e is CancellationException) throw e; state.value = state.value.copy(error = e.message ?: "加载失败") }
    } }
}

inline fun <reified T : ViewModel> vmFactory(crossinline make: (SavedStateHandle) -> T) = object : ViewModelProvider.Factory {
    override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: androidx.lifecycle.viewmodel.CreationExtras): VM {
        @Suppress("UNCHECKED_CAST") return make(extras.createSavedStateHandle()) as VM
    }
}
