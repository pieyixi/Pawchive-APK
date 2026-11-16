package pw.pawchive.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pw.pawchive.data.*
import pw.pawchive.download.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs real foreground transfer service against isolated loopback HTTPS, never the user's account. */
@RunWith(AndroidJUnit4::class)
class TransferDeviceTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()

    @Test fun foregroundTransferResumesWithValidatorAndWritesExactFile() = runBlocking {
        val context = activity.activity
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "qa-$id").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val database = Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val oldRepository = TransferRuntime.repository
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val repository = PawRepository(isolated, jobScope, client, database)
        val output = File(root, "fixture.bin")
        val partial = File(context.cacheDir, "transfers/$id.part").apply { parentFile!!.mkdirs() }
        try {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false); server.start()
                val bytes = ByteArray(524288) { ((it * 17 + 23) % 251).toByte() }
                partial.writeBytes(bytes.copyOfRange(0, 131072))
                server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"fixture-v1\"")
                    .setHeader("Content-Range", "bytes 131072-524287/524288").setBody(Buffer().write(bytes, 131072, bytes.size - 131072)))
                database.dao().download(DownloadRow(id, "fixture.bin", server.url("/fixture.bin").toString(), Uri.fromFile(output).toString(), status = "running", bytes = 131072, etag = "\"fixture-v1\""))
                TransferRuntime.repository = repository
                val coordinator = DownloadCoordinator(context, repository)
                coordinator.reconcileInterruptedTasks()
                assertEquals("paused", database.dao().download(id)!!.status)
                coordinator.schedule(id)
                withTimeout(25000) {
                    while (database.dao().download(id)?.status !in listOf("complete", "failed")) delay(100)
                }
                val row = database.dao().download(id)!!
                assertEquals(row.error, "complete", row.status)
                assertArrayEquals(bytes, output.readBytes())
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("bytes=131072-", request.getHeader("Range")); assertEquals("\"fixture-v1\"", request.getHeader("If-Range"))
                assertFalse(partial.exists())
            }
        } finally {
            TransferRuntime.running[id]?.cancelAndJoin()
            TransferRuntime.repository = oldRepository
            jobScope.cancel(); database.close(); partial.delete(); output.delete(); root.delete()
        }
    }
}
