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
package net.ccbluex.liquidbounce.features.module.modules.world.autofarm

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.hasInventorySpace
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.movement.getDegreesRelativeToView
import net.ccbluex.liquidbounce.utils.movement.getDirectionalInputForDegrees
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.entity.ItemEntity
import net.minecraft.item.Items
import net.minecraft.util.math.Vec3d
import java.util.EnumSet

object AutoFarmAutoWalk : ToggleableConfigurable(ModuleAutoFarm, "AutoWalk", false) {

    // Maximum distance to walk to targets
    private val maxWalkDistance by float("MaxDistance", 20f, 4f..40f).onChanged {
        maxWalkDistanceSquared = it.sq()
    }
    private var maxWalkDistanceSquared: Float = maxWalkDistance.sq()
    
    // Maximum vertical distance to consider targets
    private val maxVerticalDistance by float("MaxVerticalDistance", 12f, 2f..20f)

    // Makes the player move to farmland blocks where there is a need for crop replacement
    private val toPlace by boolean("ToPlace", true)

    private val toItems = object : ToggleableConfigurable(this, "ToItems", true) {
        private val range by float("Range", 25f, 8f..64f).onChanged {
            rangeSquared = it.sq()
        }
        
        // Special range for cocoa beans on elevated surfaces
        private val cocoaRange by float("CocoaRange", 30f, 10f..50f).onChanged {
            cocoaRangeSquared = it.sq()
        }

        var rangeSquared: Float = range.sq()
        var cocoaRangeSquared: Float = cocoaRange.sq()
    }

    private val autoJump by boolean("AutoJump", true)

    init {
        tree(toItems)
    }

    private var invHadSpace = true

    var walkTarget: Vec3d? = null

    @Suppress("CognitiveComplexMethod")
    private fun findWalkToItem(): Vec3d? {
        val items = world.entities.filter { entity ->
            if (entity is ItemEntity && isAllowedItem(entity)) {
                val distance = entity.squaredDistanceTo(player)
                val item = entity.stack.item
                
                // Check vertical distance separately to allow items above/below player
                val verticalDistance = kotlin.math.abs(entity.pos.y - player.pos.y)
                if (verticalDistance > maxVerticalDistance) {
                    if (Math.random() < 0.05) { // 5% chance to log filtered items
                        val coords = "${entity.pos.x.toInt()},${entity.pos.y.toInt()},${entity.pos.z.toInt()}"
                        val msg = "Item too far vertically: $item at $coords, vDist=${verticalDistance.toInt()}"
                        println("[AutoWalk] $msg")
                    }
                    return@filter false
                }
                
                // Use extended range for cocoa beans
                val maxRange = if (item == Items.COCOA_BEANS) {
                    toItems.cocoaRangeSquared
                } else {
                    toItems.rangeSquared
                }
                
                distance < maxRange
            } else {
                false
            }
        }
        
        if (items.isEmpty()) {
            if (Math.random() < 0.1) { // 10% chance to see what's happening
                val totalEntities = world.entities.count()
                println("[AutoWalk] No items found in range - searched $totalEntities entities")
            }
            return null
        } else {
            // Always log when we find items after a block break
            if (Math.random() < 0.2) { // 20% chance to see items found
                val itemTypes = items.map { (it as ItemEntity).stack.item.toString() }.distinct()
                println("[AutoWalk] Found ${items.size} items: $itemTypes")
            }
        }
        
        // Find the closest item, but prioritize cocoa beans that might be stuck on blocks
        val closestItem = items.minByOrNull { entity ->
            val distance = entity.squaredDistanceTo(player)
            val item = (entity as ItemEntity).stack.item
            
            // Debug: Show found items occasionally
            if (Math.random() < 0.05 && item == Items.COCOA_BEANS) {
                val coords = "${entity.pos.x.toInt()},${entity.pos.y.toInt()},${entity.pos.z.toInt()}"
                println("[AutoWalk] Found cocoa beans at $coords, distance=${distance.toInt()}")
            }
            
            // If it's cocoa beans, give it higher priority (lower weight)
            if (item == Items.COCOA_BEANS) {
                distance * 0.3 // Make cocoa beans much more attractive
            } else {
                distance
            }
        } as ItemEntity?
        
        return closestItem?.pos
    }
    
    private fun isAllowedItem(itemEntity: ItemEntity): Boolean {
        val item = itemEntity.stack.item
        
        return when {
            ModuleAutoFarm.AutoGarden.enabled -> isAutoGardenAllowedItem(item)
            ModuleAutoFarm.CropFilter.enabled -> isCropFilterAllowedItem(item)
            else -> true // В обычном режиме собираем все
        }
    }
    
    private fun isAutoGardenAllowedItem(item: net.minecraft.item.Item): Boolean {
        return when (item) {
            Items.WHEAT, Items.WHEAT_SEEDS -> ModuleAutoFarm.AutoGarden.targetWheat
            Items.CARROT -> ModuleAutoFarm.AutoGarden.targetCarrot
            Items.POTATO, Items.POISONOUS_POTATO -> ModuleAutoFarm.AutoGarden.targetPotato
            Items.BEETROOT, Items.BEETROOT_SEEDS -> ModuleAutoFarm.AutoGarden.targetBeetroot
            Items.NETHER_WART -> ModuleAutoFarm.AutoGarden.targetNetherWart
            Items.PUMPKIN -> ModuleAutoFarm.AutoGarden.targetPumpkin
            Items.MELON_SLICE -> ModuleAutoFarm.AutoGarden.targetMelon
            Items.COCOA_BEANS -> ModuleAutoFarm.AutoGarden.targetCocoa
            else -> false
        }
    }
    
    private fun isCropFilterAllowedItem(item: net.minecraft.item.Item): Boolean {
        return when (item) {
            Items.WHEAT, Items.WHEAT_SEEDS -> ModuleAutoFarm.CropFilter.wheat
            Items.CARROT -> ModuleAutoFarm.CropFilter.carrot
            Items.POTATO, Items.POISONOUS_POTATO -> ModuleAutoFarm.CropFilter.potato
            Items.BEETROOT, Items.BEETROOT_SEEDS -> ModuleAutoFarm.CropFilter.beetroot
            Items.NETHER_WART -> ModuleAutoFarm.CropFilter.netherWart
            Items.PUMPKIN -> ModuleAutoFarm.CropFilter.pumpkin
            Items.MELON_SLICE -> ModuleAutoFarm.CropFilter.melon
            Items.SUGAR_CANE -> ModuleAutoFarm.CropFilter.sugarCane
            Items.CACTUS -> ModuleAutoFarm.CropFilter.cactus
            Items.KELP -> ModuleAutoFarm.CropFilter.kelp
            Items.BAMBOO -> ModuleAutoFarm.CropFilter.bamboo
            Items.COCOA_BEANS -> ModuleAutoFarm.CropFilter.cocoa
            else -> false
        }
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    fun updateWalkTarget(): Boolean {
        if (!enabled) {
            // Debug: Show when AutoWalk is disabled
            if (Math.random() < 0.1) { // 10% chance to see status
                println("[AutoWalk] AutoWalk is disabled")
            }
            return false
        } else {
            if (Math.random() < 0.05) { // 5% chance to show enabled status
                println("[AutoWalk] AutoWalk enabled, updating targets")
            }
        }
        
        // Don't update walk target if depositing to hopper
        if (ModuleAutoFarm.hopperTarget != null) {
            walkTarget = ModuleAutoFarm.hopperTarget!!.toCenterPos()
            println("[AutoWalk] Walking to hopper")
            return true
        }

        val invHasSpace = hasInventorySpace()
        if (!invHasSpace && invHadSpace && toItems.enabled) {
            notification("Inventory is Full", "autoFarm wont walk to items", NotificationEvent.Severity.ERROR)
        }
        invHadSpace = invHasSpace

        val blockTarget = findWalkToBlock()
        val itemTarget = if (toItems.enabled && invHasSpace) findWalkToItem() else null
        
        // Debug: Show found targets
        if (Math.random() < 0.05) { // 5% chance to avoid spam
            val blockInfo = blockTarget?.let { "${it.x.toInt()},${it.y.toInt()},${it.z.toInt()}" } ?: "null"
            val itemInfo = itemTarget?.let { "${it.x.toInt()},${it.y.toInt()},${it.z.toInt()}" } ?: "null"
            println("[AutoWalk] Targets - Block: $blockInfo, Item: $itemInfo")
        }
        
        walkTarget = if (toItems.enabled && invHasSpace) {
            // Check if itemTarget is cocoa beans - prioritize them
            val itemIsCocoa = itemTarget != null && world.entities.any { entity ->
                entity is ItemEntity && entity.pos.squaredDistanceTo(itemTarget) < 1.0 && 
                entity.stack.item == Items.COCOA_BEANS
            }
            
            if (itemIsCocoa) {
                // Always prioritize cocoa beans
                if (Math.random() < 0.1) {
                    println("[AutoWalk] Prioritizing cocoa beans over blocks")
                }
                itemTarget
            } else {
                // Normal priority - closest target
                arrayOf(blockTarget, itemTarget).minByOrNull {
                    it?.squaredDistanceTo(player.pos) ?: Double.MAX_VALUE
                }
            }
        } else {
            blockTarget
        }
        
        // Debug: Show walk target decision
        if (Math.random() < 0.05) { // 5% chance to avoid spam
            val walkInfo = walkTarget?.let { "${it.x.toInt()},${it.y.toInt()},${it.z.toInt()}" } ?: "null"
            println("[AutoWalk] Set walkTarget to: $walkInfo")
        }

        val target = walkTarget ?: run {
            if (Math.random() < 0.02) { // 2% chance to avoid spam
                println("[AutoWalk] No walk target found, not updating rotation")
            }
            return false
        }

        RotationManager.setRotationTarget(
            Rotation.lookingAt(point = target, from = player.eyePos),
            configurable = ModuleAutoFarm.rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = ModuleAutoFarm
        )
        return true
    }

    @Suppress("CognitiveComplexMethod")
    private fun findWalkToBlock(): Vec3d? {
        if (AutoFarmBlockTracker.isEmpty()) return null

        val allowedItems = EnumSet.of(AutoFarmTrackedStates.Destroy)
        // 1. true: we should always walk to blocks we want to destroy because we can do so even without any items
        // 2. false: we should only walk to farmland blocks if we got the needed items
        // 3. false: same as 2. only go if we got the needed items for soulsand (netherwarts)
        if (toPlace) {
            for (item in Slots.OffhandWithHotbar.items) {
                when (item) {
                    in ModuleAutoFarm.filteredFarmlandItems -> allowedItems.add(AutoFarmTrackedStates.Farmland)
                    in ModuleAutoFarm.filteredSoulsandItems -> allowedItems.add(AutoFarmTrackedStates.Soulsand)
                    in ModuleAutoFarm.filteredJungleLogItems -> allowedItems.add(AutoFarmTrackedStates.JungleLog)
                }
            }
        }

        val trackedBlocks = AutoFarmBlockTracker.iterate().toList()
        
        // Debug: Show tracked blocks occasionally
        if (Math.random() < 0.02) { // 2% chance to avoid spam
            val destroyBlocks = trackedBlocks.count { (_, state) -> state == AutoFarmTrackedStates.Destroy }
            val farmlandBlocks = trackedBlocks.count { (_, state) -> state == AutoFarmTrackedStates.Farmland }
            val soulsandBlocks = trackedBlocks.count { (_, state) -> state == AutoFarmTrackedStates.Soulsand }
            val jungleBlocks = trackedBlocks.count { (_, state) -> state == AutoFarmTrackedStates.JungleLog }
            
            val message = "Tracked blocks: Destroy=$destroyBlocks, Farm=$farmlandBlocks, " +
                "Soul=$soulsandBlocks, Jungle=$jungleBlocks"
            println("[AutoWalk] $message")
        }
        
        val closestBlock = trackedBlocks.mapNotNull { (pos, state) ->
            if (state in allowedItems) {
                val centerPos = pos.toCenterPos()
                val distance = player.squaredDistanceTo(centerPos)
                val verticalDistance = kotlin.math.abs(centerPos.y - player.y)
                
                // Allow targets within horizontal and vertical range
                val inRange = distance <= maxWalkDistanceSquared && verticalDistance <= maxVerticalDistance
                if (inRange) {
                    // Debug: Show found valid blocks occasionally  
                    if (Math.random() < 0.01 || state == AutoFarmTrackedStates.JungleLog) {
                        val coords = "${pos.x},${pos.y},${pos.z}"
                        val vDist = verticalDistance.toInt()
                        println("[AutoWalk] Found valid block at $coords, state=$state, vDist=$vDist")
                    }
                    centerPos
                } else {
                    null
                }
            } else {
                null
            }
        }.minByOrNull(player::squaredDistanceTo)

        // Debug: Show result
        if (closestBlock != null && Math.random() < 0.02) {
            val coords = "${closestBlock.x.toInt()},${closestBlock.y.toInt()},${closestBlock.z.toInt()}"
            println("[AutoWalk] Returning closest block: $coords")
        } else if (closestBlock == null && Math.random() < 0.02) {
            println("[AutoWalk] No closest block found")
        }
        
        return closestBlock
    }

    fun stopWalk() {
        walkTarget = null
    }

    private fun shouldWalk() = (walkTarget != null && mc.currentScreen !is HandledScreen<*>)

    @Suppress("unused")
    private val horizontalMovementHandling = handler<MovementInputEvent> { event ->
        if (!shouldWalk()) {
            return@handler
        }

        val target = walkTarget!!
        val positionRelativeToPlayer = target.subtract(player.pos)
        val distance = positionRelativeToPlayer.length()
        
        // Check if target is an item entity (for item pickup)
        val targetIsItem = world.entities.any { entity ->
            entity is ItemEntity && entity.pos.squaredDistanceTo(target) < 1.0
        }
        
        // Smart stopping distance - handle both elevated and lower targets
        val targetHeightDiff = target.y - player.y
        val dx = target.x - player.x 
        val dz = target.z - player.z
        val horizontalDistance = kotlin.math.sqrt(dx * dx + dz * dz)
        
        val minDistance = when {
            // For items below us, allow closer approach and don't stop too early
            targetIsItem && targetHeightDiff < -0.5 -> 0.8
            // For elevated items, get much closer
            targetIsItem && targetHeightDiff > 0.5 -> 0.5
            // For regular items
            targetIsItem -> 1.0
            // For elevated blocks, get closer
            targetHeightDiff > 0.5 -> 0.5
            // For lower blocks, allow closer approach
            targetHeightDiff < -0.5 -> 0.8
            // For same level targets
            else -> 0.3
        }
        
        // Handle stopping logic for different height scenarios
        val shouldStop = when {
            // For items below us - be more lenient with horizontal positioning to allow collection
            targetIsItem && targetHeightDiff < -0.5 -> {
                // For items below, allow moving much closer horizontally
                // This fixes the issue where player stops on cocoa block but can't reach seeds below
                val reasonablyCloseHorizontally = horizontalDistance < 1.0 // Increased from 0.3
                val withinReachVertically = distance < 1.5 // Allow closer approach
                reasonablyCloseHorizontally && withinReachVertically
            }
            // For non-item targets below (blocks), use stricter positioning
            !targetIsItem && targetHeightDiff < -0.5 -> {
                val veryCloseHorizontally = horizontalDistance < 0.3
                val almostDirectlyBelow = distance < 0.5
                veryCloseHorizontally && almostDirectlyBelow
            }
            // For elevated targets, be more lenient - allow approaching elevated items/blocks
            targetHeightDiff > 0.5 -> {
                // For items above us, stop when reasonably close
                if (targetIsItem) {
                    distance < 1.2 // Allow getting closer to elevated items
                } else {
                    // For blocks above us, normal stopping
                    distance < minDistance
                }
            }
            // Normal stopping for same-level targets
            else -> distance < minDistance
        }
        
        if (shouldStop) {
            event.directionalInput = DirectionalInput.NONE
            if (Math.random() < 0.1) { // Log occasionally
                val msg = "Stopped: dist=${distance.toInt()}, hDist=${horizontalDistance.toInt()}, " +
                    "heightDiff=${targetHeightDiff.toInt()}"
                println("[AutoWalk] $msg")
            }
            return@handler
        }
        
        // Use the existing utility functions with current player yaw for stable movement
        val yawDifference = getDegreesRelativeToView(positionRelativeToPlayer, player.yaw)
        event.directionalInput = getDirectionalInputForDegrees(DirectionalInput.NONE, yawDifference)
        player.isSprinting = true
    }

    @Suppress("unused")
    private val verticalMovementHandling = handler<MovementInputEvent> { event ->
        if (!shouldWalk()) return@handler

        // We want to swim up in water, so we don't drown and can move onwards
        if (player.isTouchingWater) {
            event.jump = true
        }

        // Auto jump when enabled - only when actually colliding
        if (autoJump && player.horizontalCollision) {
            event.jump = true
        }
        
        // Always auto jump when colliding horizontally and not moving
        if (player.horizontalCollision && player.velocity.x.let { kotlin.math.abs(it) } < 0.1 && 
            player.velocity.z.let { kotlin.math.abs(it) } < 0.1) {
            event.jump = true
        }
        
        // Smart jumping logic for multi-level cocoa farms
        val target = walkTarget!!
        val playerPos = player.pos
        val heightDiff = target.y - playerPos.y
        val distance = playerPos.distanceTo(target)
        
        // Check if we need to descend to reach target  
        if (heightDiff < -0.5 && distance < 3.0) {
            val targetIsItem = world.entities.any { entity ->
                entity is ItemEntity && entity.pos.squaredDistanceTo(target) < 1.0
            }
            
            // For items below us, use sneak when close enough
            if (targetIsItem) {
                val horizontalDist = kotlin.math.sqrt((target.x - playerPos.x) * (target.x - playerPos.x) + 
                                                    (target.z - playerPos.z) * (target.z - playerPos.z))
                
                // Start sneaking when we get close horizontally, but not too far away
                if (horizontalDist < 1.2 && distance < 2.0) {
                    event.sneak = true
                    if (Math.random() < 0.1) {
                        val msg = "Sneaking to descend: hDist=${horizontalDist.toInt()}, vDist=${(-heightDiff).toInt()}"
                        println("[AutoWalk] $msg")
                    }
                    
                    // Don't jump when trying to descend, but allow continued movement
                    event.jump = false
                    // Don't return early - let horizontal movement continue
                }
            }
        }
        
        // Check if we're trying to reach an item that might be on a block
        val targetIsItem = world.entities.any { entity ->
            entity is ItemEntity && entity.pos.squaredDistanceTo(target) < 1.0
        }
        
        // Check if target is a block we need to break (don't jump ON blocks we want to break)
        @Suppress("SwallowedException")
        val targetIsBreakableBlock = try {
            val targetBlockPos = net.minecraft.util.math.BlockPos(target.x.toInt(), target.y.toInt(), target.z.toInt())
            val state = world.getBlockState(targetBlockPos)
            ModuleAutoFarm.isTargeted(state, targetBlockPos)
        } catch (e: Exception) {
            false
        }
        
        // Smart jumping logic for multi-level cocoa farms  
        val playerVel = player.velocity
        val isMoving = kotlin.math.abs(playerVel.x) > 0.03 || kotlin.math.abs(playerVel.z) > 0.03
        val direction = target.subtract(playerPos).normalize()
        val movingTowards = (playerVel.x * direction.x + playerVel.z * direction.z) > 0.03
        
        // Calculate how many blocks need to be jumped to reach target
        val blocksToJump = kotlin.math.ceil(heightDiff).toInt()
        
        // For targets 3+ blocks high, start jumping 2 blocks before target
        val jumpStartDistance = when {
            heightDiff >= 3.0 -> 2.5  // Start jumping 2+ blocks before
            heightDiff >= 2.0 -> 1.8  // Start jumping ~2 blocks before  
            heightDiff >= 1.0 -> 1.2  // Start jumping ~1 block before
            else -> 0.8              // Normal close jumping
        }
        
        val needsJump = when {
            // Never jump if target is a breakable block (we want to break it, not jump on it)
            targetIsBreakableBlock -> {
                if (Math.random() < 0.02) {
                    println("[AutoWalk] Not jumping - target is breakable block")
                }
                false
            }
            
            // Special logic for high elevated items (3+ blocks) - start jumping early
            targetIsItem && heightDiff >= 3.0 && distance < (jumpStartDistance + 3.0) && 
            (isMoving || movingTowards) -> {
                if (Math.random() < 0.1) {
                    val msg = "Early jumping: height=${heightDiff.toInt()}, dist=${distance.toInt()}"
                    println("[AutoWalk] $msg")
                }
                true
            }
            
            // Regular elevated items - start jumping at calculated distance
            targetIsItem && heightDiff >= 1.0 && distance < (jumpStartDistance + 1.0) && 
            (isMoving || movingTowards) -> {
                if (Math.random() < 0.05) {
                    val msg = "Elevated jump: height=${heightDiff.toInt()}, start=${jumpStartDistance.toInt()}"
                    println("[AutoWalk] $msg")
                }
                true
            }
            
            // Small elevation items - normal jumping
            targetIsItem && distance < 6.0 && heightDiff > 0.3 && (isMoving || movingTowards) -> {
                if (Math.random() < 0.05) {
                    println("[AutoWalk] Normal jumping to elevated item, height=${heightDiff.toInt()}")
                }
                true
            }
            
            // For planting blocks - only jump if elevated and not breakable
            !targetIsItem && !targetIsBreakableBlock && heightDiff >= 1.0 && 
            distance < (jumpStartDistance + 2.0) && (isMoving || movingTowards) -> {
                if (Math.random() < 0.03) {
                    val msg = "Jumping to planting block: height=${heightDiff.toInt()}"
                    println("[AutoWalk] $msg")
                }
                true
            }
            
            // Jump when there's any horizontal collision and target is higher (but not breakable)
            !targetIsBreakableBlock && player.horizontalCollision && distance < 8.0 && heightDiff > 0.2 -> {
                if (Math.random() < 0.05) {
                    println("[AutoWalk] Collision jumping, trying to reach higher target")
                }
                true
            }
            
            // Jump when moving slowly but target is higher (stuck on stairs) - but not for breakable blocks
            !targetIsBreakableBlock && distance < 6.0 && heightDiff > 0.3 && isMoving && 
            kotlin.math.abs(playerVel.x) < 0.15 && kotlin.math.abs(playerVel.z) < 0.15 -> {
                if (Math.random() < 0.05) {
                    println("[AutoWalk] Slow movement jumping - likely stuck on stairs")
                }
                true
            }
            
            else -> false
        }
        
        if (needsJump) {
            event.jump = true
        }
    }
}
