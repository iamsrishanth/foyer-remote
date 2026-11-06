plugins {
    id("com.android.application")
}

android {
    namespace = "com.kaera.foyer.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaera.foyer.remote"
        minSdk = 23
        targetSdk = 31
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    // JUnit only, and only for the pure-Java routing tests. The app itself stays framework-only.
    testImplementation("junit:junit:4.13.2")
}
