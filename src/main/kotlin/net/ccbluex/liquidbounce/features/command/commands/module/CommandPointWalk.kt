/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.command.commands.module

import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.CommandException
import net.ccbluex.liquidbounce.features.command.CommandFactory
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.features.module.modules.movement.ModulePointWalk
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.util.math.Vec3d

/**
 * PointWalk Command
 *
 * Manages waypoints for cyclic walking
 *
 * Module: [ModulePointWalk]
 */
object CommandPointWalk : CommandFactory {

    override fun createCommand(): Command {
        return CommandBuilder
            .begin("pointwalk")
            .requiresIngame()
            .subcommand(createPosCommand())
            .subcommand(createPos1Command())
            .subcommand(createPos2Command())
            .subcommand(createAddCommand())
            .subcommand(createClearCommand())
            .subcommand(createListCommand())
            .subcommand(createStartCommand())
            .subcommand(createStopCommand())
            .subcommand(createSaveCommand())
            .subcommand(createLoadCommand())
            .subcommand(createConfigsCommand())
            .handler { _, _ ->
                ModulePointWalk.addWaypoint(player.pos)
            }
            .build()
    }

    private fun createPosCommand() = CommandBuilder
        .begin("pos")
        .handler { _, _ ->
            ModulePointWalk.addWaypoint(player.pos)
        }
        .build()

    private fun createPos1Command() = CommandBuilder
        .begin("pos1")
        .handler { _, _ ->
            ModulePointWalk.addWaypoint(player.pos)
        }
        .build()

    private fun createPos2Command() = CommandBuilder
        .begin("pos2")
        .handler { _, _ ->
            ModulePointWalk.addWaypoint(player.pos)
        }
        .build()

    private fun createAddCommand() = CommandBuilder
        .begin("add")
        .parameter(
            ParameterBuilder
                .begin<Float>("x")
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Float>("y")
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Float>("z")
                .required()
                .build()
        )
        .handler { command, args ->
            val x = (args[0] as String).toDoubleOrNull()
            val y = (args[1] as String).toDoubleOrNull()
            val z = (args[2] as String).toDoubleOrNull()

            if (x == null || y == null || z == null) {
                throw CommandException(command.result("invalidCoordinates"))
            }

            ModulePointWalk.addWaypoint(Vec3d(x, y, z))
        }
        .build()

    private fun createClearCommand() = CommandBuilder
        .begin("clear")
        .handler { _, _ ->
            ModulePointWalk.clearWaypoints()
        }
        .build()

    private fun createListCommand() = CommandBuilder
        .begin("list")
        .handler { _, _ ->
            ModulePointWalk.listWaypoints()
        }
        .build()

    private fun createStartCommand() = CommandBuilder
        .begin("start")
        .handler { _, _ ->
            ModulePointWalk.enabled = true
        }
        .build()

    private fun createStopCommand() = CommandBuilder
        .begin("stop")
        .handler { _, _ ->
            ModulePointWalk.enabled = false
        }
        .build()

    private fun createSaveCommand() = CommandBuilder
        .begin("save")
        .parameter(
            ParameterBuilder
                .begin<String>("name")
                .required()
                .build()
        )
        .handler { _, args ->
            val name = args[0] as String
            ModulePointWalk.saveConfig(name)
        }
        .build()

    private fun createLoadCommand() = CommandBuilder
        .begin("load")
        .parameter(
            ParameterBuilder
                .begin<String>("name")
                .required()
                .build()
        )
        .handler { _, args ->
            val name = args[0] as String
            ModulePointWalk.loadConfig(name)
        }
        .build()

    private fun createConfigsCommand() = CommandBuilder
        .begin("configs")
        .handler { _, _ ->
            ModulePointWalk.listConfigs()
        }
        .build()
}