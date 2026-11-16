package pw.pawchive.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "creators", indices = [Index("name"), Index("updated")])
data class CreatorRow(@PrimaryKey val key: String, val name: String, val service: String, val updated: Long, val payload: String,
    @ColumnInfo(defaultValue = "0") val indexed: Long = 0, @ColumnInfo(defaultValue = "0") val favorited: Long = 0)
@Entity(tableName = "posts", primaryKeys = ["scope", "key"], indices = [Index(value = ["scope", "time"])])
data class PostRow(val scope: String, val key: String, val time: Long, val payload: String)
@Entity(tableName = "marks", primaryKeys = ["scope", "key"])
data class SyncMark(val scope: String, val key: String, val updated: Long, val checked: Long)
@Entity(tableName = "collections", primaryKeys = ["owner", "kind", "key"])
data class CollectionRow(val owner: String, val kind: String, val key: String, val time: Long, val payload: String)
@Entity(tableName = "downloads")
data class DownloadRow(
    @PrimaryKey val id: String, val name: String, val url: String, val destination: String,
    val status: String = "queued", val bytes: Long = 0, val total: Long = -1,
    val etag: String? = null, val error: String? = null, val created: Long = System.currentTimeMillis()
)
@Entity(tableName = "metadata")
data class Metadata(@PrimaryKey val key: String, val value: String)

@Dao
interface PawDao {
    @Upsert suspend fun creators(rows: List<CreatorRow>)
    @Query("SELECT * FROM creators WHERE name LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY name COLLATE NOCASE ASC, `key` ASC LIMIT :limit OFFSET :offset")
    suspend fun searchCreators(query: String, offset: Int = 0, limit: Int = 50): List<CreatorRow>
    @androidx.room.RawQuery suspend fun filterCreators(query: androidx.sqlite.db.SupportSQLiteQuery): List<CreatorRow>
    @Query("SELECT * FROM creators ORDER BY `key` LIMIT :limit OFFSET :offset") suspend fun creatorPage(offset: Int, limit: Int = 500): List<CreatorRow>
    @Query("DELETE FROM metadata WHERE `key` LIKE 'creator.ai:%'") suspend fun clearAiCreators()
    @Upsert suspend fun metadata(rows: List<Metadata>)
    @Query("SELECT * FROM metadata WHERE `key` LIKE 'search.page:%'") suspend fun searchPageMetadata(): List<Metadata>
    @Query("SELECT COUNT(*) FROM creators") suspend fun creatorCount(): Int
    @Query("SELECT * FROM creators WHERE `key` = :key") suspend fun creator(key: String): CreatorRow?
    @Upsert suspend fun posts(rows: List<PostRow>)
    @Query("SELECT * FROM posts WHERE scope = :scope ORDER BY time DESC") fun watchPosts(scope: String): Flow<List<PostRow>>
    @Query("SELECT * FROM posts WHERE scope = :scope ORDER BY time DESC") suspend fun posts(scope: String): List<PostRow>
    @Query("DELETE FROM posts WHERE scope = :scope") suspend fun clearPosts(scope: String)
    @Query("DELETE FROM posts WHERE scope LIKE :pattern AND scope NOT IN (SELECT substr(`key`,14) FROM metadata WHERE `key` LIKE :metadataPattern ORDER BY CAST(value AS INTEGER) DESC LIMIT :limit)")
    suspend fun trimCaches(pattern: String, metadataPattern: String, limit: Int)
    @Query("DELETE FROM metadata WHERE `key` LIKE 'cache.access:%' AND substr(`key`,14) NOT IN (SELECT DISTINCT scope FROM posts)")
    suspend fun trimCacheMetadata()
    @Upsert suspend fun mark(value: SyncMark)
    @Query("SELECT * FROM marks WHERE scope = :scope") suspend fun marks(scope: String): List<SyncMark>
    @Upsert suspend fun collect(row: CollectionRow)
    @Query("DELETE FROM collections WHERE owner = :owner AND kind = :kind AND `key` = :key") suspend fun uncollect(owner: String, kind: String, key: String)
    @Query("SELECT * FROM collections WHERE owner = :owner AND kind = :kind ORDER BY time DESC") fun watchCollection(owner: String, kind: String): Flow<List<CollectionRow>>
    @Query("SELECT * FROM collections WHERE owner = :owner AND kind = :kind ORDER BY time DESC") suspend fun collection(owner: String, kind: String): List<CollectionRow>
    @Query("DELETE FROM collections WHERE owner = :owner AND kind = :kind") suspend fun clearCollection(owner: String, kind: String)
    @Upsert suspend fun download(row: DownloadRow)
    @Query("DELETE FROM downloads WHERE id = :id AND status NOT IN ('queued','running')") suspend fun removeDownloadRecord(id: String)
    @Query("DELETE FROM downloads WHERE status NOT IN ('queued','running')") suspend fun clearDownloadRecords()
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun download(id: String): DownloadRow?
    @Query("SELECT * FROM downloads ORDER BY created DESC") fun downloads(): Flow<List<DownloadRow>>
    @Query("SELECT * FROM downloads WHERE status IN ('queued','running')") suspend fun unfinishedDownloads(): List<DownloadRow>
    @Upsert suspend fun metadata(row: Metadata)
    @Query("SELECT value FROM metadata WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Query("DELETE FROM metadata WHERE `key` = :key") suspend fun removeMetadata(key: String)
}

@Database(entities = [CreatorRow::class, PostRow::class, SyncMark::class, CollectionRow::class, DownloadRow::class, Metadata::class], version = 2, exportSchema = true)
abstract class PawDatabase : RoomDatabase() { abstract fun dao(): PawDao }
