package pw.pawchive.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PawApi {
    @GET("posts") suspend fun posts(@Query("q") query: String = "", @Query("o") offset: Int = 0): List<Post>
    @GET("{service}/user/{user}") suspend fun authorPosts(@Path("service") service: String, @Path("user") user: String, @Query("o") offset: Int = 0): List<Post>
    @GET("{service}/user/{user}/profile") suspend fun author(@Path("service") service: String, @Path("user") user: String): Creator
    @GET("{service}/user/{user}/post/{post}") suspend fun post(@Path("service") service: String, @Path("user") user: String, @Path("post") post: String): Post
    @GET("account/favorites") suspend fun favoriteAuthors(@Query("type") type: String = "artist"): List<Creator>
    @GET("account/favorites") suspend fun favoritePosts(@Query("type") type: String = "post"): List<Post>
}
fun siteClient(jar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(jar).connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "PawchiveAndroid/0.1.0").build()) }
    .build()
fun pawApi(client: OkHttpClient, baseUrl: String = "$SITE/api/v1/"): PawApi = Retrofit.Builder().baseUrl(baseUrl).client(client)
    .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType())).build().create(PawApi::class.java)

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

fun Response.requireSuccess(): Response { if (!isSuccessful) { val code = code; close(); throw IOException(if (code == 401) "登录已失效，请重新登录" else "网站响应 $code") }; return this }
