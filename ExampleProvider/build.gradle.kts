dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "DİZİSOL film ve dizi katalog sağlayıcısı"
    authors = listOf("rochland")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "tr"
    iconUrl = "https://dizisol.com/favicon-32.png"
}
