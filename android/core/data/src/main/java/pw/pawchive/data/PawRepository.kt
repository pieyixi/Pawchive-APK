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
import kotlinx.serialization.json.JsonPrimitive
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.room.migration.Migration
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
    val publicClient: OkHttpClient = siteClient(), database: PawDatabase? = null, sessionClient: OkHttpClient? = null) {
    val db = database ?: Room.databaseBuilder(context, PawDatabase::class.java, "pawchive.db")
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE creators ADD COLUMN indexed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE creators ADD COLUMN favorited INTEGER NOT NULL DEFAULT 0")
            }
        }).build()
    val dao = db.dao()
    val api = pawApi(publicClient)
    private val vault = SessionVault(context)
    private var accountClient = sessionClient ?: siteClient()
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
            runCatching { syncFollowedAuthors() }.onFailure {
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
    fun feed(): Flow<List<Post>> = account.flatMapLatest { dao.watchPosts("${it.owner}:feed").map { rows -> rows.map { wireJson.decodeFromString<Post>(it.payload) }.sortedByDescending { it.feedTimestamp } } }.flowOn(Dispatchers.Default)
    suspend fun cachedPosts(scope: String): List<Post> = withContext(Dispatchers.Default) { dao.posts(scope).map { wireJson.decodeFromString<Post>(it.payload) } }

    private suspend fun replaceAuthors(owner: String, authors: List<Creator>) = db.withTransaction {
        val previous = dao.collection(owner, "author").associate { it.key to it.payload }
        if (previous.size == authors.size && authors.all { previous[it.key] == wireJson.encodeToString(it) }) return@withTransaction
        dao.clearCollection(owner, "author")
        authors.forEach { dao.collect(CollectionRow(owner, "author", it.key, it.timestamp, wireJson.encodeToString(it))) }
    }
    suspend fun syncFollowedAuthors(): List<Creator> = withContext(Dispatchers.IO) { collectionMutex.withLock {
        val state = account.value
        if (state.username == null) return@withLock dao.collection(state.owner, "author").map { wireJson.decodeFromString<Creator>(it.payload) }
        val token = generation.get()
        val authors = try { accountApi.favoriteAuthors() } catch (e: retrofit2.HttpException) {
            if (e.code() == 401) { expireSession(token); throw IOException("登录已失效，请重新登录") }; throw e
        }
        if (token != generation.get()) throw CancellationException("Account changed")
        db.withTransaction { replaceAuthors(state.owner, authors) }
        authors
    } }

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
                    val existing = mutableMapOf<String, String>()
                    var pageOffset = 0
                    do { val rows = dao.creatorPage(pageOffset); rows.forEach { existing[it.key] = it.payload }; pageOffset += rows.size } while (rows.size == 500)
                    val sequence = wireJson.decodeToSequence<Creator>(response.body!!.byteStream(), DecodeSequenceMode.ARRAY_WRAPPED)
                    sequence.chunked(500).forEach { chunk ->
                        currentCoroutineContext().ensureActive()
                        val changed = chunk.filter { creator -> existing[creator.key] != wireJson.encodeToString(creator) }
                        if (changed.isNotEmpty()) dao.creators(changed.map { it.creatorRow() })
                    }
                    response.header("Last-Modified")?.let { dao.metadata(Metadata("index.modified", it)) }
                }
            }
            dao.metadata(Metadata("index.checked", System.currentTimeMillis().toString()))
        } finally { indexLoading.value = false }
    } }
    private fun Creator.creatorRow() = CreatorRow(key, name, service, timestamp, wireJson.encodeToString(this), timeValue(indexed), timeValue(favorited) / 1000)

    /** Backfill only new sort columns after the non-destructive v1 -> v2 migration. */
    suspend fun prepareAuthorFilters(filters: SearchFilters) = withContext(Dispatchers.IO) { indexMutex.withLock {
        if (filters.sort in setOf("indexed", "hot") && dao.metadata("index.sort-fields") != "2") {
            var offset = 0
            do {
                val rows = dao.creatorPage(offset)
                dao.creators(rows.map { wireJson.decodeFromString<Creator>(it.payload).creatorRow() })
                offset += rows.size
            } while (rows.size == 500)
            dao.metadata(Metadata("index.sort-fields", "2"))
        }
        if (filters.ai != "all" && System.currentTimeMillis() - (dao.metadata("creator.ai.checked")?.toLongOrNull() ?: 0) > 86_400_000) {
            val tagged = publicClient.newCall(Request.Builder().url("$SITE/api/v1/creators?only=ai").build()).await().requireSuccess().use {
                wireJson.decodeFromString<List<Creator>>(it.body!!.string())
            }
            db.withTransaction {
                dao.clearAiCreators()
                dao.metadata(tagged.map { Metadata("creator.ai:${it.key}", "1") })
                dao.metadata(Metadata("creator.ai.checked", System.currentTimeMillis().toString()))
            }
        }
    } }
    suspend fun searchAuthors(query: String, offset: Int = 0, filters: SearchFilters = SearchFilters()): List<Creator> = withContext(Dispatchers.IO) {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val args = mutableListOf<Any>("%$escaped%")
        val where = StringBuilder("name LIKE ? ESCAPE '\\'")
        if (filters.service != "all") { where.append(" AND service = ?"); args += filters.service }
        if (filters.ai != "all") where.append(" AND ${if (filters.ai == "hide") "NOT " else ""}EXISTS (SELECT 1 FROM metadata m WHERE m.`key` = 'creator.ai:' || creators.`key`)")
        val column = when (filters.sort) { "updated" -> "updated"; "indexed" -> "indexed"; "hot" -> "favorited"; else -> "name COLLATE NOCASE" }
        val direction = if (filters.order == "desc") "DESC" else "ASC"
        args += offset
        dao.filterCreators(SimpleSQLiteQuery("SELECT * FROM creators WHERE $where ORDER BY $column $direction, name COLLATE NOCASE ASC, `key` ASC LIMIT 50 OFFSET ?", args.toTypedArray())).map { wireJson.decodeFromString(it.payload) }
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
                ?: profileGate.withPermit { api.author(service, user) }.also { dao.creators(listOf(it.creatorRow())) }
        }
    }
    suspend fun post(service: String, user: String, id: String): Post = withContext(Dispatchers.IO) {
        val cacheScope = "post:$service:$user:$id"
        try { api.post(service, user, id).also { storePosts(cacheScope, listOf(it)); history(it) } }
        catch (e: Exception) { if (e is CancellationException) throw e; dao.posts(cacheScope).firstOrNull()?.let { wireJson.decodeFromString(it.payload) } ?: throw e }
    }
    suspend fun storePosts(scope: String, posts: List<Post>) = db.withTransaction {
        dao.clearPosts(scope)
        dao.posts(posts.map { PostRow(scope, it.key, if (scope.endsWith(":feed")) it.feedTimestamp else it.timestamp, wireJson.encodeToString(it)) })
        dao.metadata(Metadata("cache.access:$scope", System.currentTimeMillis().toString()))
        dao.trimCaches("post:%", "cache.access:post:%", 200)
        dao.trimCaches("public:home:%", "cache.access:public:home:%", 10)
        dao.trimCaches("public:author:%", "cache.access:public:author:%", 30)
        dao.trimCaches("%:feed", "cache.access:%:feed", 3)
        dao.trimCacheMetadata()
    }

    data class FeedMetrics(val elapsedMs: Long = 0, val authorListRequests: Int = 0, val postRequests: Int = 0,
        val targets: Int = 0, val received: Int = 0, val pendingBackfills: Int = 0)
    val feedMetrics = MutableStateFlow(FeedMetrics())

    suspend fun refreshFeed(manual: Boolean = true) = withContext(Dispatchers.IO) {
        if (!feedMutex.tryLock()) return@withContext
        val started = android.os.SystemClock.elapsedRealtime()
        val token = generation.get(); val state = account.value; val cacheScope = "${state.owner}:feed"
        var listRequests = 0; var postRequests = 0; var received = 0; var pending = 0; var targetCount = 0
        feedStatus.value = FeedStatus(running = true)
        try {
            // Logged-in API returns timestamps for only followed creators in one request.
            // No favorite-post sync, global index refresh or eight-author audit in this path.
            val follows = if (state.username != null) { listRequests++; withTimeoutOrNull(12_000) { syncFollowedAuthors() } ?: throw IOException("网站响应超时，已保留旧动态") }
                else dao.collection(state.owner, "author").map { wireJson.decodeFromString<Creator>(it.payload) }
            if (follows.isEmpty()) { if (generation.get() == token) dao.clearPosts(cacheScope); return@withContext }
            val creators = if (state.username != null && follows.all { it.timestamp > 0 }) follows else {
                // Guest/missing server timestamps: retain a complete, conditionally fetched discovery fallback.
                listRequests++; refreshCreatorIndex()
                follows.map { a -> dao.creator(a.key)?.let { wireJson.decodeFromString<Creator>(it.payload) } ?: a }
            }
            val marks = dao.marks(cacheScope).associateBy { it.key }
            val now = System.currentTimeMillis()
            val cursors = creators.associate { a -> a.key to (dao.metadata("feed.backfill:$cacheScope:${a.key}")?.split(',') ?: emptyList()) }
            // Site timestamps can be rounded to an hour; overlap only two recently updated creators.
            val overlap = if (manual) creators.filter { a -> a.timestamp > now - 3_600_000 && now - (marks[a.key]?.checked ?: 0) > 300_000 }
                .sortedByDescending { it.timestamp }.take(2).map { it.key }.toSet() else emptySet()
            val targets = creators.filter { a -> marks[a.key] == null || a.timestamp != marks[a.key]!!.updated || cursors[a.key]!!.isNotEmpty() || a.key in overlap }
                .sortedByDescending { it.timestamp }
            targetCount = targets.size
            feedStatus.value = FeedStatus(true, total = targets.size)
            val followedKeys = follows.map { it.key }.toSet()
            var current = dao.posts(cacheScope).map { wireJson.decodeFromString<Post>(it.payload) }.filter { it.authorKey in followedKeys }
            // Re-index legacy cached rows by added time, without clearing visible content.
            if (generation.get() == token && (dao.metadata("feed.sort:$cacheScope") != "added" || current.size != dao.posts(cacheScope).size)) {
                db.withTransaction { storePosts(cacheScope, mergeFeed(current, emptyList())); dao.metadata(Metadata("feed.sort:$cacheScope", "added")) }
            }
            val previousByAuthor = current.groupBy { it.authorKey }.mapValues { (_, posts) -> posts.map { it.key }.toSet() }
            val mutex = Mutex(); val gate = Semaphore(3); val errors = mutableListOf<String>()
            coroutineScope { targets.map { author -> async { gate.withPermit {
                try {
                    val mark = marks[author.key]
                    val cursor = cursors[author.key].orEmpty()
                    val pendingOffset = cursor.takeIf { it.size == 2 && it[0].toLongOrNull() != null }?.get(1)?.toIntOrNull()?.takeIf { it > 0 && it % 50 == 0 }
                    val needsHead = mark == null || mark.updated != author.timestamp || author.key in overlap
                    var resumeAfterHead = pendingOffset.takeIf { needsHead }
                    val previousIds = previousByAuthor[author.key].orEmpty()
                    val batch = mutableListOf<Post>()
                    var offset = if (needsHead) 0 else pendingOffset ?: 0
                    var continuing = offset > 0
                    var backfill = false
                    // Most updates need just the first page. A changed creator with no fresh imports
                    // there is a historical-import candidate; continue up to three pages, saving a cursor.
                    for (page in 0..2) {
                        mutex.withLock { postRequests++ }
                        val posts = withTimeoutOrNull(20_000) { api.authorPosts(author.service, author.id, offset) } ?: throw IOException("作者更新超时")
                        batch += posts
                        // A new import must not reset an unfinished historical scan to page one.
                        // Read the fresh head, then resume the saved historical page.
                        if (resumeAfterHead != null && posts.size >= 50) {
                            offset = resumeAfterHead; resumeAfterHead = null; continuing = true; backfill = true
                            continue
                        }
                        resumeAfterHead = null
                        offset += posts.size
                        backfill = posts.size >= 50 && (continuing || (mark != null && author.timestamp != mark.updated &&
                            (posts.none { timeValue(it.added) >= author.timestamp } || (previousIds.isNotEmpty() && posts.none { it.key in previousIds }))))
                        if (posts.size < 50 || !backfill) break
                        if (page < 2) yield()
                    }
                    mutex.withLock {
                        if (generation.get() != token) throw CancellationException("Account changed")
                        db.withTransaction {
                            val merged = mergeFeed(current, batch.map { it.compact() })
                            storePosts(cacheScope, merged)
                            dao.mark(SyncMark(cacheScope, author.key, author.timestamp, now))
                            val key = "feed.backfill:$cacheScope:${author.key}"
                            if (backfill) { dao.metadata(Metadata(key, "${author.timestamp},$offset")); pending++ } else dao.removeMetadata(key)
                        }
                        current = mergeFeed(current, batch.map { it.compact() })
                        received += batch.size
                        feedStatus.value = feedStatus.value.copy(checked = feedStatus.value.checked + 1)
                    }
                } catch (e: Exception) { if (e is CancellationException) throw e; mutex.withLock { errors.add(author.key) } }
            } } }.awaitAll() }
            if (generation.get() == token) feedStatus.value = FeedStatus(error = if (errors.isEmpty()) null else "${errors.size} 位作者更新失败，已保留旧动态")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (generation.get() == token) feedStatus.value = FeedStatus(error = e.message ?: "刷新失败，已保留旧动态")
        } finally {
            feedMetrics.value = FeedMetrics(android.os.SystemClock.elapsedRealtime() - started, listRequests, postRequests, targetCount, received, pending)
            android.util.Log.d("PawFeed", "refresh=${feedMetrics.value}")
            if (generation.get() == token) feedStatus.value = feedStatus.value.copy(running = false)
            feedMutex.unlock()
        }
    }
}
