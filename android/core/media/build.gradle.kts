plugins { alias(libs.plugins.android.library); alias(libs.plugins.compose) }
android {
    namespace = "pw.pawchive.media"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core:data"))
    implementation(platform("androidx.compose:compose-bom:${libs.versions.composeBom.get()}"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}")
    implementation("io.coil-kt.coil3:coil-network-okhttp:${libs.versions.coil.get()}")
    implementation("io.coil-kt.coil3:coil-gif:${libs.versions.coil.get()}")
    implementation("me.saket.telephoto:zoomable-image-coil3:${libs.versions.telephoto.get()}")
    implementation("androidx.media3:media3-exoplayer:${libs.versions.media3.get()}")
    implementation("androidx.media3:media3-ui:${libs.versions.media3.get()}")
}
