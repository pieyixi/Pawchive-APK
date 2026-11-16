package pw.pawchive.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.*
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

private val Context.preferences by preferencesDataStore("pawchive")
data class AccountState(val username: String? = null, val restoring: Boolean = true) { val owner get() = username?.let { "account:$it" } ?: "guest" }
data class FeedStatus(val running: Boolean = false, val error: String? = null, val checked: Int = 0, val total: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class PawRepository(private val context: Context, private val scope: CoroutineScope,
    val publicClient: OkHttpClient = siteClient(), database: PawDatabase? = null) {
    val db = database ?: Room.databaseBuilder(context, PawDatabase::class.java, "pawchive.db").build()
    val dao = db.dao()
    val api = pawApi(publicClient)
    private val vault = SessionVault(context)
    private var accountClient = siteClient()
    private var accountApi = pawApi(accountClient)
    private val generation = AtomicLong(0)
    private val sessionMutex = Mutex()
    private val collectionMutex = Mutex()
    private val indexMutex = Mutex()
    private val feedMutex = Mutex()
    private val profileGate = Semaphore(3)
    private val profileLocks = Array(64) { Mutex() }
    val account = MutableStateFlow(AccountState())
    val feedStatus = MutableStateFlow(FeedStatus())
    val indexLoading = MutableStateFlow(false)
    val dark: Flow<Boolean?> = context.preferences.data.map { it[booleanPreferencesKey("dark")] }
    val hideMediaLessWorks: Flow<Boolean> = context.preferences.data.map { it[booleanPreferencesKey("hide_media_less_works")] ?: false }.distinctUntilChanged()
    suspend fun setHideMediaLessWorks(value: Boolean) { context.preferences.edit { it[booleanPreferencesKey("hide_media_less_works")] = value } }
    val readerSettings: Flow<ReaderSettings> = context.preferences.data.map {
        it[stringPreferencesKey("reader_options")]?.let { raw -> runCatching { wireJson.decodeFromString<ReaderSettings>(raw) }.getOrNull() } ?: ReaderSettings()
    }.flowOn(Dispatchers.Default)
    suspend fun setReaderSettings(value: ReaderSettings) { context.preferences.edit { it[stringPreferencesKey("reader_options")] = wireJson.encodeToString(value) } }
    suspend fun setDark(dark: Boolean) { context.preferences.edit { it[booleanPreferencesKey("dark")] = dark } }

    init { scope.launch(Dispatchers.IO) {
        val saved = vault.load()
        if (saved != null) {
            val jar = persistentJar(saved.username, saved.cookies)
            accountClient = siteClient(jar); accountApi = pawApi(accountClient)
            account.value = AccountState(saved.username, false)
            // Network failure retains encrypted login; only explicit unauthorized invalidates it.
            runCatching { syncFavorites() }.onFailure {
                if (it is retrofit2.HttpException && it.code() == 401) logout()
            }
        } else account.value = AccountState(restoring = false)
    } }

    private fun persistentJar(username: String, initial: List<String>): SiteCookieJar {
        val token = generation.get()
        return SiteCookieJar(initial) { cookies -> if (generation.get() == token) vault.save(StoredSession(username, cookies)) }
    }

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) { sessionMutex.withLock {
        require(username.isNotBlank() && password.isNotEmpty()) { "请输入用户名和密码" }
        val jar = SiteCookieJar()
        val candidate = siteClient(jar)
        val html = candidate.newCall(Request.Builder().url("$SITE/account/login").build()).await().requireSuccess().use { it.body!!.string() }
        val form = FormBody.Builder().add("username", username.trim()).add("password", password).add("location", "/account")
        Jsoup.parse(html).select("form#login_form input[type=hidden][name]").forEach {
            if (it.attr("name") !in setOf("username", "password", "location")) form.add(it.attr("name"), it.attr("value"))
        }
        candidate.newCall(Request.Builder().url("$SITE/account/login").header("Origin", SITE).post(form.build()).build()).await().requireSuccess().close()
        // A 302 or a cookie is insufficient: verify an authenticated endpoint before adopting session.
        val verified = pawApi(candidate).favoriteAuthors()
        generation.incrementAndGet()
        vault.save(StoredSession(username.trim(), jar.snapshot()))
        accountClient = siteClient(persistentJar(username.trim(), jar.snapshot())); accountApi = pawApi(accountClient)
        account.value = AccountState(username.trim(), false)
        replaceAuthors(account.value.owner, verified)
        syncFavorites()
    } }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val oldClient = accountClient
        expireSession(generation.get())
        runCatching { oldClient.newCall(Request.Builder().url("$SITE/account/logout").build()).await().close() }
    }
    private fun expireSession(token: Long) {
        if (!generation.compareAndSet(token, token + 1)) return
        accountClient = siteClient(); accountApi = pawApi(accountClient)
        vault.clear(); account.value = AccountState(restoring = false)
    }

    fun followed(): Flow<List<Creator>> = account.flatMapLatest { state -> dao.watchCollection(state.owner, "author").map { rows -> rows.map { wireJson.decodeFromString<Creator>(it.payload) } } }.flowOn(Dispatchers.Default)
    fun collected(kind: String): Flow<List<Post>> = account.flatMapLatest { state -> dao.watchCollection(state.owner, kind).map { rows -> rows.map { wireJson.decodeFromString<Post>(it.payload) } } }.flowOn(Dispatchers.Default)
    fun blockedAuthors(): Flow<List<Creator>> = account.flatMapLatest { state -> dao.watchCollection(state.owner, "blocked").map { rows -> rows.map { row ->
        // 0.1.6 stored a representative post under the author key. Read it as an author.
        val json = wireJson.parseToJsonElement(row.payload).jsonObject
        if ("user" in json) {
            val post = wireJson.decodeFromString<Post>(row.payload)
            dao.creator(post.authorKey)?.let { wireJson.decodeFromString<Creator>(it.payload) } ?: Creator(post.user, post.service, post.user)
        } else wireJson.decodeFromString<Creator>(row.payload)
    }.distinctBy { it.key } } }.flowOn(Dispatchers.IO)
    suspend fun blockAuthor(creator: Creator, selected: Boolean) {
        val owner = account.value.owner
        if (selected) dao.collect(CollectionRow(owner, "blocked", creator.key, System.currentTimeMillis(), wireJson.encodeToString(creator)))
        else dao.uncollect(owner, "blocked", creator.key)
    }
    suspend fun localCollection(kind: String, post: Post, selected: Boolean) {
        require(kind == "later" || kind == "blocked")
        if (kind == "blocked") {
            val creator = dao.creator(post.authorKey)?.let { wireJson.decodeFromString<Creator>(it.payload) } ?: Creator(post.user, post.service, post.user)
            blockAuthor(creator, selected); return
        }
        val key = if (kind == "blocked") post.authorKey else post.key
        val owner = account.value.owner
        if (selected) dao.collect(CollectionRow(owner, kind, key, System.currentTimeMillis(), wireJson.encodeToString(post.compact())))
        else dao.uncollect(owner, kind, key)
    }
    fun feed(): Flow<List<Post>> = account.flatMapLatest { dao.watchPosts("${it.owner}:feed").map { rows -> rows.map { wireJson.decodeFromString<Post>(it.payload) } } }.flowOn(Dispatchers.Default)
    suspend fun cachedPosts(scope: String): List<Post> = withContext(Dispatchers.Default) { dao.posts(scope).map { wireJson.decodeFromString<Post>(it.payload) } }

    private suspend fun replaceAuthors(owner: String, authors: List<Creator>) = db.withTransaction {
        dao.clearCollection(owner, "author")
        authors.forEach { dao.collect(CollectionRow(owner, "author", it.key, it.timestamp, wireJson.encodeToString(it))) }
    }
    suspend fun syncFavorites() = withContext(Dispatchers.IO) { collectionMutex.withLock {
        val state = account.value; if (state.username == null) return@withLock
        val token = generation.get(); val requestApi = accountApi
        val authors: List<Creator>
        val posts: List<Post>
        try { authors = requestApi.favoriteAuthors(); posts = requestApi.favoritePosts() }
        catch (e: retrofit2.HttpException) {
            if (e.code() == 401) { expireSession(token); throw IOException("登录已失效，请重新登录") }
            throw e
        }
        if (token != generation.get()) return@withLock
        db.withTransaction {
            replaceAuthors(state.owner, authors)
            dao.clearCollection(state.owner, "favorite")
            posts.forEach { dao.collect(CollectionRow(state.owner, "favorite", it.key, it.timestamp, wireJson.encodeToString(it.compact()))) }
        }
    } }

    suspend fun follow(author: Creator, selected: Boolean) = changeCollection("author", author.key, author.service, author.id, null, wireJson.encodeToString(author), selected)
    suspend fun favorite(post: Post, selected: Boolean) = changeCollection("favorite", post.key, post.service, post.user, post.id, wireJson.encodeToString(post.compact()), selected)
    private suspend fun changeCollection(kind: String, key: String, service: String, user: String, post: String?, payload: String, selected: Boolean) = withContext(Dispatchers.IO) { collectionMutex.withLock {
        val state = account.value; val token = generation.get()
        if (state.username != null) {
            val path = "${if (post == null) "creator" else "post"}/${segment(service)}/${segment(user)}" + (post?.let { "/${segment(it)}" } ?: "")
            val request = Request.Builder().url("$SITE/api/v1/favorites/$path")
            if (selected) request.post(ByteArray(0).toRequestBody()) else request.delete()
            accountClient.newCall(request.build()).await().use { response ->
                if (response.code == 401) { expireSession(token); throw IOException("登录已失效，请重新登录") }
                response.requireSuccess()
            }
        }
        if (token == generation.get()) {
            if (selected) dao.collect(CollectionRow(state.owner, kind, key, System.currentTimeMillis(), payload)) else dao.uncollect(state.owner, kind, key)
        }
    } }
    suspend fun history(post: Post) {
        val owner = account.value.owner
        dao.collect(CollectionRow(owner, "history", post.key, System.currentTimeMillis(), wireJson.encodeToString(post.compact())))
        dao.collection(owner, "history").drop(200).forEach { dao.uncollect(owner, "history", it.key) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun refreshCreatorIndex(force: Boolean = false) = withContext(Dispatchers.IO) { indexMutex.withLock {
        val last = dao.metadata("index.checked")?.toLongOrNull() ?: 0
        if (!force && System.currentTimeMillis() - last < 120_000 && dao.creatorCount() > 0) return@withLock
        indexLoading.value = true
        try {
            val builder = Request.Builder().url("$SITE/api/v1/creators")
            dao.metadata("index.modified")?.let { builder.header("If-Modified-Since", it) }
            publicClient.newCall(builder.build()).await().use { response ->
                if (response.code != 304) {
                    response.requireSuccess()
                    val sequence = wireJson.decodeToSequence<Creator>(response.body!!.byteStream(), DecodeSequenceMode.ARRAY_WRAPPED)
                    sequence.chunked(500).forEach { chunk ->
                        currentCoroutineContext().ensureActive()
                        dao.creators(chunk.map { CreatorRow(it.key, it.name, it.service, it.timestamp, wireJson.encodeToString(it)) })
                    }
                    response.header("Last-Modified")?.let { dao.metadata(Metadata("index.modified", it)) }
                }
            }
            dao.metadata(Metadata("index.checked", System.currentTimeMillis().toString()))
        } finally { indexLoading.value = false }
    } }
    suspend fun searchAuthors(query: String, offset: Int = 0): List<Creator> = withContext(Dispatchers.IO) {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        dao.searchCreators(escaped, offset).map { wireJson.decodeFromString(it.payload) }
    }
    suspend fun popularPosts(offset: Int): List<Post> = withContext(Dispatchers.IO) {
        val cache = "public:home:popular"
        val checked = dao.metadata("popular.checked")?.toLongOrNull() ?: 0
        val cached = cachedPosts(cache)
        if (System.currentTimeMillis() - checked < 21_600_000 && (cached.size >= offset + 50 || cached.size % 50 != 0)) return@withContext cached.drop(offset).take(50)
        val html = publicClient.newCall(Request.Builder().url("$SITE/posts/popular?o=$offset").build()).await().requireSuccess().use { it.body!!.string() }
        val posts = Jsoup.parse(html).select("article.post-card[data-id]").map { card ->
            val thumbnail = card.selectFirst("img.post-card__image")?.attr("src").orEmpty()
            val path = thumbnail.substringAfter("/thumbnail/data", "")
            Post(card.attr("data-id"), card.attr("data-user"), card.attr("data-service"), card.selectFirst(".post-card__header")?.text().orEmpty(),
                file = path.takeIf { it.startsWith('/') }?.let { RemoteFile(it.substringAfterLast('/'), it) })
        }
        require(posts.isNotEmpty() || offset > 0) { "热门列表暂不可用" }
        db.withTransaction {
            val all = if (offset == 0) posts else (cached.take(offset) + posts).distinctBy { it.key }
            dao.clearPosts(cache)
            dao.posts(all.mapIndexed { index, post -> PostRow(cache, post.key, -index.toLong(), wireJson.encodeToString(post)) })
            if (offset == 0) dao.metadata(Metadata("popular.checked", System.currentTimeMillis().toString()))
        }
        posts
    }
    suspend fun author(service: String, user: String): Creator = withContext(Dispatchers.IO) {
        val key = "$service:$user"
        profileLocks[(key.hashCode() and Int.MAX_VALUE) % profileLocks.size].withLock {
            dao.creator(key)?.let { wireJson.decodeFromString<Creator>(it.payload) }
                ?: profileGate.withPermit { api.author(service, user) }.also { dao.creators(listOf(CreatorRow(it.key, it.name, it.service, it.timestamp, wireJson.encodeToString(it)))) }
        }
    }
    suspend fun post(service: String, user: String, id: String): Post = withContext(Dispatchers.IO) {
        val cacheScope = "post:$service:$user:$id"
        try { api.post(service, user, id).also { storePosts(cacheScope, listOf(it)); history(it) } }
        catch (e: Exception) { if (e is CancellationException) throw e; dao.posts(cacheScope).firstOrNull()?.let { wireJson.decodeFromString(it.payload) } ?: throw e }
    }
    suspend fun storePosts(scope: String, posts: List<Post>) = db.withTransaction {
        dao.clearPosts(scope)
        dao.posts(posts.map { PostRow(scope, it.key, it.timestamp, wireJson.encodeToString(it)) })
        dao.metadata(Metadata("cache.access:$scope", System.currentTimeMillis().toString()))
        dao.trimCaches("post:%", "cache.access:post:%", 200)
        dao.trimCaches("public:home:%", "cache.access:public:home:%", 10)
        dao.trimCaches("public:author:%", "cache.access:public:author:%", 30)
        dao.trimCaches("%:feed", "cache.access:%:feed", 3)
        dao.trimCacheMetadata()
    }

    suspend fun refreshFeed(manual: Boolean = true) = withContext(Dispatchers.IO) {
        if (!feedMutex.tryLock()) return@withContext
        val token = generation.get(); val state = account.value; val cacheScope = "${state.owner}:feed"
        feedStatus.value = FeedStatus(running = true)
        try {
            if (state.username != null) syncFavorites()
            val follows = dao.collection(state.owner, "author").map { wireJson.decodeFromString<Creator>(it.payload) }
            if (follows.isEmpty()) { if (generation.get() == token) dao.clearPosts(cacheScope); return@withContext }
            // Reuse the shared conditionally fetched creator index; failed index still permits bounded audits.
            runCatching { refreshCreatorIndex() }.onFailure { if (it is CancellationException) throw it }
            val marks = dao.marks(cacheScope).associateBy { it.key }
            val now = System.currentTimeMillis()
            val creators = follows.map { a -> dao.creator(a.key)?.let { wireJson.decodeFromString<Creator>(it.payload) } ?: a }
            val unchanged = creators.filter { a -> marks[a.key]?.let { it.updated >= a.timestamp } == true }.sortedBy { marks[it.key]?.checked ?: 0 }
            val audits = (unchanged.filter { now - (marks[it.key]?.checked ?: 0) > 600_000 }.take(8) +
                if (manual) unchanged.filter { now - (marks[it.key]?.checked ?: 0) > 60_000 }.take(2) else emptyList()).map { it.key }.toSet()
            val targets = creators.filter { a -> marks[a.key] == null || a.timestamp > marks[a.key]!!.updated || a.key in audits }
            feedStatus.value = FeedStatus(true, total = targets.size)
            val followedKeys = follows.map { it.key }.toSet()
            val old = dao.posts(cacheScope).map { wireJson.decodeFromString<Post>(it.payload) }.filter { it.authorKey in followedKeys }
            val incoming = mutableListOf<Post>(); val successful = mutableListOf<SyncMark>(); val errors = mutableListOf<String>()
            val mutex = Mutex(); val gate = Semaphore(3)
            coroutineScope { targets.map { author -> async { gate.withPermit {
                try {
                    val previous = old.filter { it.authorKey == author.key }.map { it.key }.toSet()
                    var batch = api.authorPosts(author.service, author.id)
                    if (previous.isNotEmpty() && batch.size >= 50 && batch.none { it.key in previous }) batch += api.authorPosts(author.service, author.id, 50)
                    mutex.withLock {
                        incoming.addAll(batch.map { it.compact() }); successful.add(SyncMark(cacheScope, author.key, author.timestamp, now))
                        feedStatus.value = feedStatus.value.copy(checked = feedStatus.value.checked + 1)
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; mutex.withLock { errors.add(author.name) } }
            } } }.awaitAll() }
            if (generation.get() == token) db.withTransaction {
                storePosts(cacheScope, mergeFeed(old, incoming)); successful.forEach { dao.mark(it) }
            }
            feedStatus.value = FeedStatus(error = if (errors.isEmpty()) null else "${errors.size} 位作者更新失败，已保留旧动态")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            feedStatus.value = FeedStatus(error = e.message ?: "刷新失败，已保留旧动态")
        } finally { feedStatus.value = feedStatus.value.copy(running = false); feedMutex.unlock() }
    }
}
