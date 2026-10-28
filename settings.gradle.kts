pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
        gradlePluginPortal()
        google()
        mavenCentral()
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
