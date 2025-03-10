package com.intellij.ide.starter.plugins

import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.utils.FileSystem
import com.intellij.ide.starter.utils.HttpClient
import com.intellij.ide.starter.utils.logError
import com.intellij.ide.starter.utils.logOutput
import org.kodein.di.direct
import org.kodein.di.instance
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines

open class PluginConfigurator(val testContext: IDETestContext) {
  val disabledPluginsPath: Path
    get() = testContext.paths.configDir / "disabled_plugins.txt"

  fun setupPluginFromPath(pathToPluginArchive: Path) = apply {
    FileSystem.unpack(pathToPluginArchive, testContext.paths.pluginsDir)
  }

  fun setupPluginFromURL(urlToPluginZipFile: String) = apply {
    val pluginRootDir = di.direct.instance<GlobalPaths>().getCacheDirectoryFor("plugins")
    val pluginZip: Path = pluginRootDir / testContext.ide.build / urlToPluginZipFile.substringAfterLast("/")

    HttpClient.download(urlToPluginZipFile, pluginZip)
    FileSystem.unpack(pluginZip, testContext.paths.pluginsDir)
  }

  fun setupPluginFromPluginManager(
    pluginId: String,
    ide: InstalledIde,
    channel: String? = null,
  ) = apply {
    logOutput("Setting up plugin: $pluginId ...")

    val fileName = pluginId.replace(".", "-") + ".zip"
    val downloadedPlugin = di.direct.instance<GlobalPaths>().getCacheDirectoryFor("plugins") / testContext.ide.build / fileName
    if (!downloadedPlugin.toFile().exists()) {
      val url = buildString {
        append("https://plugins.jetbrains.com/pluginManager/")
        append("?action=download")
        append("&id=${pluginId.replace(" ", "%20")}")
        append("&noStatistic=false")
        append("&build=${ide.productCode}-${ide.build}")
        channel?.let {
          append("&channel=$it")
        }
      }
      if (HttpClient.download(url, downloadedPlugin, retries = 1)) {
        FileSystem.unpack(downloadedPlugin, testContext.paths.pluginsDir)
      }
      else {
        logError("Plugin $pluginId downloading failed, skipping")
        return@apply
      }
    }
    else {
      FileSystem.unpack(downloadedPlugin, testContext.paths.pluginsDir)
    }
    logOutput("Plugin $pluginId setup finished")
  }

  fun disablePlugins(vararg pluginIds: String) = disablePlugins(pluginIds.toSet())

  fun disablePlugins(pluginIds: Set<String>) = also {
    disabledPluginsPath.writeLines(disabledPluginIds + pluginIds)
  }

  fun enablePlugins(vararg pluginIds: String) = enablePlugins(pluginIds.toSet())

  private fun enablePlugins(pluginIds: Set<String>) = also {
    disabledPluginsPath.writeLines(disabledPluginIds - pluginIds)
  }

  private val disabledPluginIds: Set<String>
    get() {
      val file = disabledPluginsPath
      return if (file.exists()) file.readLines().toSet() else emptySet()
    }


  private fun findPluginXmlByPluginIdInAGivenDir(pluginId: String, bundledPluginsDir: Path): Boolean {
    val jarFiles = bundledPluginsDir.toFile().walk().filter { it.name.endsWith(".jar") }.toList()
    jarFiles.forEach {
      val jarFile = JarFile(it)
      val entry = jarFile.getJarEntry("META-INF/plugin.xml")
      if (entry != null) {
        val inputStream = jarFile.getInputStream(entry)
        val text: String = inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        if (text.contains(" <id>$pluginId</id>")) {
          return true
        }
      }
    }
    return false
  }


  fun getPluginInstalledState(pluginId: String): PluginInstalledState {
    if (disabledPluginsPath.toFile().exists() && pluginId in disabledPluginIds) {
      return PluginInstalledState.DISABLED
    }

    val installedPluginDir = testContext.paths.pluginsDir
    if (findPluginXmlByPluginIdInAGivenDir(pluginId, installedPluginDir)) {
      return PluginInstalledState.INSTALLED
    }

    val bundledPluginsDir = testContext.ide.bundledPluginsDir
    if (bundledPluginsDir == null) {
      logOutput("Cannot ensure a plugin '$pluginId' is installed in ${testContext.ide}. Consider it is installed.")
      return PluginInstalledState.INSTALLED
    }

    if (findPluginXmlByPluginIdInAGivenDir(pluginId, bundledPluginsDir)) {
      return PluginInstalledState.BUNDLED_TO_IDE
    }
    return PluginInstalledState.NOT_INSTALLED
  }

  fun assertPluginIsInstalled(pluginId: String): PluginConfigurator {
    when (getPluginInstalledState(pluginId)) {
      PluginInstalledState.DISABLED -> error("Plugin '$pluginId' must not be listed in the disabled plugins file ${disabledPluginsPath}")
      PluginInstalledState.NOT_INSTALLED -> error("Plugin '$pluginId' must be installed")
      PluginInstalledState.BUNDLED_TO_IDE -> return this
      PluginInstalledState.INSTALLED -> return this
    }
  }
}