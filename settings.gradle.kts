pluginManagement {
    repositories {
        // 插件坐标很小，优先走官方仓库：国内镜像对部分插件（如 KSP）存在缺件，
        // 而 GitHub Actions 上镜像不可达时会导致 plugin marker 解析失败。
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

dependencyResolutionManagement {
    // 本机 ~/.gradle/init.d/mirrors.init.gradle 会注入镜像仓库；FAIL_ON_PROJECT_REPOS 会把注入判为违规。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://repo.huaweicloud.com/repository/maven/")
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-android"

include(":core:model")
include(":core:domain")
include(":core:protocol")
include(":core:database")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":core:testing")
include(":feature:sessions")
include(":feature:conversation")
include(":feature:inbox")
include(":feature:settings")
include(":app")
