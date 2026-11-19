plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val supabaseUrl = (project.findProperty("SUPABASE_URL") as String?) ?: ""
val supabaseKey = (project.findProperty("SUPABASE_KEY") as String?) ?: ""

android {
    namespace = "com.christ.locaveriss"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.christ.locaveriss"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
    }

    // Trois applis à partir du même code :
    //  - chercheur : pour les personnes en quête de logement
    //  - pro       : pour les propriétaires et agences qui publient
    //  - admin     : pour l'équipe qui valide ou refuse les annonces
    flavorDimensions += "role"
    productFlavors {
        create("chercheur") {
            dimension = "role"
        }
        create("pro") {
            dimension = "role"
            applicationIdSuffix = ".pro"
            versionNameSuffix = "-pro"
        }
        create("admin") {
            dimension = "role"
            applicationIdSuffix = ".admin"
            versionNameSuffix = "-admin"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
}
