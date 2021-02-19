package com.github.monun.delayedrespawn.plugin

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*

/**
 * @author Monun
 */

class DelayedRespawnPlugin : JavaPlugin(), Listener, Runnable {

    private lateinit var respawnFolder: File

    private var respawnDelay: Long = 0L
    private lateinit var respawns: IdentityHashMap<Player, Respawn>

    override fun onEnable() {
        respawnFolder = File(dataFolder, "respawns").also(File::mkdirs)

        saveDefaultConfig()
        respawnDelay = config.getLong("respawn-delay")
        respawns = IdentityHashMap()

        server.pluginManager.registerEvents(this, this)
        server.scheduler.runTaskTimer(this, this, 0L, 1L)

        for (player in Bukkit.getOnlinePlayers()) {
            if (load(player)) player.gameMode = GameMode.SPECTATOR
        }
    }

    override fun onDisable() {
        Bukkit.getOnlinePlayers().forEach(this::save)
    }

    private val Player.respawnFile: File
        get() = File(respawnFolder, "$uniqueId.yml")

    private fun load(player: Player): Boolean {
        val file = player.respawnFile

        if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            val respawn = Respawn().also { it.load(config) }

            file.delete()

            if (respawn.remainRespawnTime > 0) {
                respawns[player] = respawn
                return true
            }
        }

        return false
    }

    private fun save(player: Player) {
        respawns.remove(player)?.let { respawn ->
            val config = respawn.save()
            config.save(player.respawnFile)
        }
    }

    override fun run() {
        val iterator = respawns.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val respawn = entry.value
            val remain = respawn.remainRespawnTime

            if (remain > 0) {
                entry.key.sendActionBar(String.format("${ChatColor.RED}${ChatColor.BOLD}리스폰까지 %.1f초", remain / 1000.0))
            } else {
                entry.key.run {
                    iterator.remove()
                    sendActionBar(" ")
                    gameMode = respawn.gameMode
                    teleport(respawn.respawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerSpawn(event: PlayerRespawnEvent) {
        val player = event.player
        val gameMode = player.gameMode
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) return

        player.gameMode = GameMode.SPECTATOR

        respawns[player] = Respawn().apply {
            this.respawnLocation = event.respawnLocation
            this.gameMode = gameMode
            this.respawnTime = TimeTools.machineTimeMillis() + respawnDelay
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (load(player)) player.gameMode = GameMode.SPECTATOR
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        save(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        if (event.player in respawns && event.cause == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.isCancelled = true
        }
    }
}

class Respawn {
    companion object {
        const val KEY_LOCATION = "location"
        const val KEY_WORLD = "world"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_Z = "z"
        const val KEY_YAW = "yaw"
        const val KEY_PITCH = "pitch"
        const val KEY_TIME = "respawn-time"
        const val KEY_GAME_MODE = "game-mode"
    }

    lateinit var respawnLocation: Location
    lateinit var gameMode: GameMode

    var respawnTime: Long = 0

    val remainRespawnTime: Long
        get() = respawnTime - TimeTools.machineTimeMillis()

    fun save() = YamlConfiguration().apply {
        createSection("location").also { section ->
            respawnLocation.run {
                section[KEY_WORLD] = world.name
                section[KEY_X] = x
                section[KEY_Y] = y
                section[KEY_Z] = z
                section[KEY_YAW] = yaw
                section[KEY_PITCH] = pitch
            }
        }
        this[KEY_TIME] = remainRespawnTime
        this[KEY_GAME_MODE] = gameMode.name.toLowerCase()
    }

    fun load(config: YamlConfiguration) {
        respawnLocation = config.getConfigurationSection(KEY_LOCATION)!!.let { section ->
            val world = section.getString(KEY_WORLD)!!.let { Bukkit.getWorld(it) }
            val x = section.getDouble(KEY_X)
            val y = section.getDouble(KEY_Y)
            val z = section.getDouble(KEY_Z)
            val yaw = section.getDouble(KEY_YAW).toFloat()
            val pitch = section.getDouble(KEY_PITCH).toFloat()
            Location(world, x, y, z, yaw, pitch)
        }
        respawnTime = TimeTools.machineTimeMillis() + config.getLong(KEY_TIME)
        gameMode = GameMode.valueOf(config.getString(KEY_GAME_MODE)!!.toUpperCase())
    }
}

object TimeTools {
    fun machineTimeMillis(): Long {
        return System.nanoTime() / 1000000L
    }
}