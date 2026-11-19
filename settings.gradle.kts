// Maven 源按运行环境分流：
// - GitHub Actions（CI=true）在海外节点，访问阿里云镜像常返回 502 Bad Gateway，
//   导致依赖解析失败，因此云端只声明官方源。
// - 本地开发保留阿里云镜像优先，维持下载速度，官方源兜底。
// 说明：Gradle 的 pluginManagement / dependencyResolutionManagement 块作用域
// 看不到脚本顶层声明，因此环境判定在两个块内各写一次，表达式必须保持一致。
pluginManagement {
    val isCi = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    val isCi = System.getenv("CI").equals("true", ignoreCase = true)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "LiveTv"
include(":app")
