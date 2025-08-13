package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.minecraft.util.math.Vec3d
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import kotlin.math.*

@Serializable
data class PointWalkConfig(
    val waypoints: List<SerializableVec3d>,
    val name: String
)

@Serializable
data class SerializableVec3d(
    val x: Double,
    val y: Double, 
    val z: Double
) {
    fun toVec3d() = Vec3d(x, y, z)
    
    companion object {
        fun from(vec: Vec3d) = SerializableVec3d(vec.x, vec.y, vec.z)
    }
}

object ModulePointWalk : ClientModule("PointWalk", Category.MOVEMENT) {

    private val speed by float("Speed", 0.3f, 0.01f..2.0f)
    private val yOffset by float("YOffset", 0.0f, -2.0f..2.0f)
    private val range by float("Range", 1.5f, 0.1f..10.0f)
    private val mode by enumChoice("Mode", WalkMode.STRAFE)
    private val rotateToTarget by boolean("RotateToTarget", true)
    private val rotationSpeed by float("RotationSpeed", 2.0f, 0.1f..10.0f)
    private val autoRun by boolean("AutoRun", false)
    private val announceWaypoint by boolean("AnnounceWaypoint", false)
    private val waitTicks by int("WaitTicks", 0, 0..100)

    private val waypoints = mutableListOf<Vec3d>()
    private var currentTargetIndex = 0
    private var isWalking = false
    private var waitCounter = 0
    
    private val configDirectory = File(mc.runDirectory, "liquidbounce/pointwalk")
    private val json = Json { prettyPrint = true }

    enum class WalkMode(override val choiceName: String) : NamedChoice {
        STRAFE("Strafe"),
        DIRECT("Direct"),
        VANILLA("Vanilla")
    }

    fun addWaypoint(pos: Vec3d) {
        val adjustedPos = Vec3d(pos.x, pos.y + yOffset, pos.z)
        waypoints.add(adjustedPos)
        chat(regular("Waypoint "), variable("${waypoints.size}"), regular(" added at "), 
             variable("${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}"))
        
        if (autoRun && waypoints.size >= 2 && !enabled) {
            enabled = true
        }
    }

    fun clearWaypoints() {
        waypoints.clear()
        currentTargetIndex = 0
        isWalking = false
        waitCounter = 0
        chat(regular("All waypoints cleared"))
    }

    fun listWaypoints() {
        if (waypoints.isEmpty()) {
            chat(regular("No waypoints set"))
            return
        }
        
        chat(regular("Waypoints ("), variable("${waypoints.size}"), regular("):"))
        waypoints.forEachIndexed { index, pos ->
            val marker = if (index == currentTargetIndex) " -> " else "    "
            chat(regular("$marker${index + 1}. "), 
                 variable("${pos.x.toInt()}, ${(pos.y - yOffset).toInt()}, ${pos.z.toInt()}"))
        }
    }

    override fun enable() {
        if (waypoints.isEmpty()) {
            chat(regular("No waypoints set. Use "), variable(".pointwalk pos"), regular(" to add waypoints."))
            enabled = false
            return
        }
        
        isWalking = true
        currentTargetIndex = 0
        waitCounter = 0
        chat(regular("PointWalk started with "), variable("${waypoints.size}"), regular(" waypoints"))
    }

    override fun disable() {
        isWalking = false
        waitCounter = 0
        chat(regular("PointWalk stopped"))
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        if (!isWalking || waypoints.isEmpty()) {
            return@handler
        }

        if (waitCounter > 0) {
            waitCounter--
            return@handler
        }

        val currentTarget = waypoints[currentTargetIndex]
        val playerPos = player.pos
        val distance = playerPos.distanceTo(currentTarget)

        if (distance <= range) {
            val oldIndex = currentTargetIndex
            currentTargetIndex = (currentTargetIndex + 1) % waypoints.size
            waitCounter = waitTicks
            
            if (announceWaypoint) {
                chat(regular("Reached waypoint "), variable("${oldIndex + 1}"), 
                     regular(", moving to waypoint "), variable("${currentTargetIndex + 1}"))
            }
            return@handler
        }

        if (rotateToTarget) {
            val direction = currentTarget.subtract(playerPos).normalize()
            val targetYaw = atan2(-direction.x, direction.z) * 180.0 / PI
            val targetPitch = -asin(direction.y) * 180.0 / PI
            
            // Плавный поворот
            val currentYaw = player.yaw.toDouble()
            val currentPitch = player.pitch.toDouble()
            
            // Нормализация углов для корректного интерполирования
            val yawDiff = normalizeAngle(targetYaw - currentYaw)
            val pitchDiff = targetPitch - currentPitch
            
            val rotSpeed = rotationSpeed.toDouble()
            val newYaw = currentYaw + (yawDiff * rotSpeed / 10.0)
            val newPitch = currentPitch + (pitchDiff * rotSpeed / 10.0)
            
            player.yaw = newYaw.toFloat()
            player.pitch = newPitch.coerceIn(-90.0, 90.0).toFloat()
        }
    }

    @Suppress("unused")
    private val moveHandler = handler<PlayerMoveEvent> {
        if (!isWalking || waypoints.isEmpty() || waitCounter > 0) {
            return@handler
        }

        val currentTarget = waypoints[currentTargetIndex]
        val playerPos = player.pos
        val distance = playerPos.distanceTo(currentTarget)

        if (distance <= range) {
            return@handler
        }

        when (mode) {
            WalkMode.STRAFE -> {
                val direction = currentTarget.subtract(playerPos).normalize()
                val yaw = atan2(-direction.x, direction.z)
                
                it.movement = it.movement.withStrafe(
                    yaw = Math.toDegrees(yaw).toFloat(),
                    speed = speed.toDouble(),
                    input = null
                )
            }
            WalkMode.DIRECT -> {
                val direction = currentTarget.subtract(playerPos).normalize()
                val moveSpeed = speed.toDouble()
                
                it.movement = Vec3d(
                    direction.x * moveSpeed,
                    it.movement.y,
                    direction.z * moveSpeed
                )
            }
            WalkMode.VANILLA -> {
                val direction = currentTarget.subtract(playerPos).normalize()
                val moveSpeed = speed.toDouble()
                
                player.velocity = Vec3d(
                    direction.x * moveSpeed,
                    player.velocity.y,
                    direction.z * moveSpeed
                )
            }
        }
    }

    fun saveConfig(name: String) {
        try {
            if (!configDirectory.exists()) {
                configDirectory.mkdirs()
            }
            
            if (waypoints.isEmpty()) {
                chat(regular("No waypoints to save"))
                return
            }
            
            val config = PointWalkConfig(
                waypoints = waypoints.map { SerializableVec3d.from(it) },
                name = name
            )
            
            val configFile = File(configDirectory, "$name.json")
            configFile.writeText(json.encodeToString(config))
            
            chat(regular("Config "), variable(name), regular(" saved with "), 
                 variable("${waypoints.size}"), regular(" waypoints"))
        } catch (e: Exception) {
            chat(regular("Failed to save config: ${e.message}"))
        }
    }

    fun loadConfig(name: String) {
        try {
            val configFile = File(configDirectory, "$name.json")
            
            if (!configFile.exists()) {
                chat(regular("Config "), variable(name), regular(" not found"))
                return
            }
            
            val configText = configFile.readText()
            val config = json.decodeFromString<PointWalkConfig>(configText)
            
            waypoints.clear()
            waypoints.addAll(config.waypoints.map { it.toVec3d() })
            currentTargetIndex = 0
            waitCounter = 0
            
            chat(regular("Config "), variable(name), regular(" loaded with "), 
                 variable("${waypoints.size}"), regular(" waypoints"))
        } catch (e: Exception) {
            chat(regular("Failed to load config: ${e.message}"))
        }
    }

    fun listConfigs() {
        try {
            if (!configDirectory.exists()) {
                chat(regular("No configs found"))
                return
            }
            
            val configs = configDirectory.listFiles { _, name -> name.endsWith(".json") }
            
            if (configs.isNullOrEmpty()) {
                chat(regular("No configs found"))
                return
            }
            
            chat(regular("Available configs:"))
            configs.forEach { file ->
                val configName = file.nameWithoutExtension
                chat(regular("  - "), variable(configName))
            }
        } catch (e: Exception) {
            chat(regular("Failed to list configs: ${e.message}"))
        }
    }

    private fun normalizeAngle(angle: Double): Double {
        var normalized = angle % 360.0
        if (normalized > 180.0) normalized -= 360.0
        else if (normalized < -180.0) normalized += 360.0
        return normalized
    }
}