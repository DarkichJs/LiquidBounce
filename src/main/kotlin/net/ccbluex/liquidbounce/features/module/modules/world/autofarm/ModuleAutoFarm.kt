@file:Suppress("LargeClass")

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
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleAutoSeller
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlock
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceUpperBlockSide
import net.ccbluex.liquidbounce.utils.block.*
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.entity.getNearestPoint
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.inventory.hasInventorySpace
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.block.*
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.ItemEntity
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext

/**
 * AutoFarm module
 *
 * Automatically farms stuff for you.
 */
@Suppress("TooManyFunctions")
object ModuleAutoFarm : ClientModule("AutoFarm", Category.WORLD) {
    // TODO Fix this entire module-
    private val range by float("Range", 6F, 1F..20F)
    private val wallRange by float("WallRange", 0f, 0F..20F).onChange {
        minOf(it, range)
    }

    // The ticks to wait after interacting with something
    private val interactDelay by intRange("InteractDelay", 2..3, 1..15, "ticks")

//    private val extraSearchRange by float("extraSearchRange", 0F, 0F..3F)

    private val disableOnFullInventory by boolean("DisableOnFullInventory", false)

    private object AutoPlaceCrops : ToggleableConfigurable(this, "AutoPlace", true) {
        val swapBackDelay by intRange("swapBackDelay", 1..2, 1..20, "ticks")
    }

    private val fortune by boolean("UseFortune", true)

    internal object AutoChest : ToggleableConfigurable(this, "AutoChest", true) {
        val hopperRange by float("HopperRange", 6f, 2f..16f)
        val keepSeeds by int("KeepSeeds", 32, 1..64)
        val dropDelay by int("DropDelay", 500, 100..2000)
    }

    internal object AutoBoneMeal : ToggleableConfigurable(this, "AutoBoneMeal", false) {
        val searchRange by float("SearchRange", 3f, 1f..6f)
        val boneMealDelay by int("BoneMealDelay", 2000, 500..5000)
        val resetInterval by int("ResetInterval", 30000, 10000..120000)
    }


    internal object CropFilter : ToggleableConfigurable(this, "CropFilter", false) {
        val wheat by boolean("Wheat", true)
        val carrot by boolean("Carrot", true)
        val potato by boolean("Potato", true)
        val beetroot by boolean("Beetroot", true)
        val netherWart by boolean("NetherWart", true)
        val pumpkin by boolean("Pumpkin", true)
        val melon by boolean("Melon", true)
        val sugarCane by boolean("SugarCane", true)
        val cactus by boolean("Cactus", true)
        val kelp by boolean("Kelp", true)
        val bamboo by boolean("Bamboo", true)
        val cocoa by boolean("Cocoa", true)
    }

    internal object AutoGarden : ToggleableConfigurable(this, "AutoGarden", false) {
        val targetWheat by boolean("TargetWheat", true)
        val targetCarrot by boolean("TargetCarrot", true)
        val targetPotato by boolean("TargetPotato", true)
        val targetBeetroot by boolean("TargetBeetroot", false)
        val targetNetherWart by boolean("TargetNetherWart", false)
        val targetPumpkin by boolean("TargetPumpkin", true)
        val targetMelon by boolean("TargetMelon", true)
        val targetCocoa by boolean("TargetCocoa", true)
        val targetSweetBerries by boolean("TargetSweetBerries", true)
    }

    private val autoWalk = tree(AutoFarmAutoWalk)

    // Hopper management variables
    internal var hopperTarget: BlockPos? = null
    private var isDepositingToHopper = false
    internal var foundHoppers: List<BlockPos> = emptyList()
    private var blockedHoppers: MutableSet<BlockPos> = mutableSetOf()
    private var lastDropAttemptTime = 0L
    private var failedDropCount = 0

    // Bone meal management variables
    private var lastBoneMealTime = 0L
    private var boneMealedPositions = mutableSetOf<BlockPos>()
    private var lastBoneMealResetTime = 0L


    init {
        tree(AutoPlaceCrops)
        tree(AutoFarmVisualizer)
        tree(AutoChest)
        tree(AutoBoneMeal)
        tree(CropFilter)
        tree(AutoGarden)
    }

    val rotations = tree(RotationsConfigurable(this))

    val itemsForFarmland = arrayOf(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.CARROT, Items.POTATO)
    val itemsForSoulsand = arrayOf(Items.NETHER_WART)
    val itemsForJungleLogs = arrayOf(Items.COCOA_BEANS)

    internal val filteredFarmlandItems: Array<net.minecraft.item.Item>
        get() = when {
            AutoGarden.enabled -> {
                val filtered = mutableListOf<net.minecraft.item.Item>()
                if (AutoGarden.targetWheat) filtered.add(Items.WHEAT_SEEDS)
                if (AutoGarden.targetBeetroot) filtered.add(Items.BEETROOT_SEEDS)
                if (AutoGarden.targetCarrot) filtered.add(Items.CARROT)
                if (AutoGarden.targetPotato) filtered.add(Items.POTATO)
                filtered.toTypedArray()
            }
            CropFilter.enabled -> {
                val filtered = mutableListOf<net.minecraft.item.Item>()
                if (CropFilter.wheat) filtered.add(Items.WHEAT_SEEDS)
                if (CropFilter.beetroot) filtered.add(Items.BEETROOT_SEEDS)
                if (CropFilter.carrot) filtered.add(Items.CARROT)
                if (CropFilter.potato) filtered.add(Items.POTATO)
                filtered.toTypedArray()
            }
            else -> itemsForFarmland
        }

    internal val filteredSoulsandItems: Array<net.minecraft.item.Item>
        get() = when {
            AutoGarden.enabled -> if (AutoGarden.targetNetherWart) itemsForSoulsand else emptyArray()
            CropFilter.enabled -> if (CropFilter.netherWart) itemsForSoulsand else emptyArray()
            else -> itemsForSoulsand
        }

    internal val filteredJungleLogItems: Array<net.minecraft.item.Item>
        get() = when {
            AutoGarden.enabled -> {
                val result = if (AutoGarden.targetCocoa) itemsForJungleLogs else emptyArray()
                // Debug only when AutoGarden is enabled and occasionally to avoid spam
                if (Math.random() < 0.01) { // 1% chance to avoid spam
                    val items = result.contentToString()
                    println("[AutoFarm] AutoGarden enabled, targetCocoa=${AutoGarden.targetCocoa}, items=$items")
                }
                result
            }
            CropFilter.enabled -> itemsForJungleLogs // В обычном режиме разрешаем кокао
            else -> itemsForJungleLogs
        }

    private val itemForFarmland
        get() = Slots.Hotbar.findClosestSlot(items = filteredFarmlandItems)
    private val itemForSoulSand
        get() = Slots.Hotbar.findClosestSlot(items = filteredSoulsandItems)
    private val itemForJungleLog
        get() = Slots.Hotbar.findClosestSlot(items = filteredJungleLogItems).also { slot ->
            // Debug only occasionally to avoid spam
            if (Math.random() < 0.01 && filteredJungleLogItems.isNotEmpty()) { // 1% chance to avoid spam
                val items = filteredJungleLogItems.contentToString()
                println("[AutoFarm] Looking for jungle log items: $items, found slot: $slot")
            }
        }

    // Hopper management functions
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private fun findNearestHopper(): BlockPos? {
        if (!AutoChest.enabled) {
            foundHoppers = emptyList()
            return null
        }

        val hopperRange = AutoChest.hopperRange.toInt()
        val rangeSquared = (AutoChest.hopperRange * AutoChest.hopperRange).toDouble()
        val playerPos = player.blockPos

        // Search in a cube around player
        val hoppersInRange = mutableListOf<BlockPos>()
        var blocksChecked = 0
        var hoppersFound = 0

        for (x in -hopperRange..hopperRange) {
            for (y in -hopperRange..hopperRange) {
                for (z in -hopperRange..hopperRange) {
                    val pos = playerPos.add(x, y, z)
                    val state = pos.getState()
                    blocksChecked++

                    if (state != null) {
                        val block = state.block
                        val isHopper = block == Blocks.HOPPER

                        if (isHopper) {
                            hoppersFound++
                            val distance = player.squaredDistanceTo(pos.toCenterPos())

                            if (distance <= rangeSquared) {
                                hoppersInRange.add(pos)
                            }
                        }
                    }
                }
            }
        }

        // Store for visualization
        foundHoppers = hoppersInRange.toList()

        // Debug info - very rarely to avoid spam
        if (Math.random() < 0.005) { // 0.5% chance to avoid spam
            notification("Hopper Search",
                "Found ${hoppersInRange.size} hoppers in range",
                NotificationEvent.Severity.INFO)
        }

        // Filter out blocked hoppers and return the nearest available one
        val availableHoppers = hoppersInRange.filter { it !in blockedHoppers }

        return if (availableHoppers.isNotEmpty()) {
            availableHoppers.minByOrNull { player.squaredDistanceTo(it.toCenterPos()) }
        } else {
            // If all hoppers are blocked, clear the blocked list and try again
            if (hoppersInRange.isNotEmpty()) {
                notification("Hopper Reset",
                    "All hoppers were blocked, clearing blocklist", NotificationEvent.Severity.INFO)
                blockedHoppers.clear()
                hoppersInRange.minByOrNull { player.squaredDistanceTo(it.toCenterPos()) }
            } else {
                null
            }
        }
    }

    private fun shouldDepositToHopper(): Boolean {
        if (!AutoChest.enabled || isDepositingToHopper) return false

        val inventoryFull = !hasInventorySpace()

        // Always deposit if inventory is full
        if (inventoryFull) {
            return true
        }

        // Check if we have too many items to deposit
        val inventory = player.inventory
        var totalSeeds = 0
        var hasExcessLoot = false

        for (slot in 0 until inventory.main.size) {
            val stack = inventory.main[slot]
            if (stack.isEmpty) continue

            when (stack.item) {
                in filteredFarmlandItems, in filteredSoulsandItems -> {
                    totalSeeds += stack.count
                }
                Items.WHEAT, Items.CARROT, Items.POTATO, Items.POISONOUS_POTATO,
                Items.BEETROOT, Items.NETHER_WART, Items.SWEET_BERRIES -> {
                    hasExcessLoot = true
                }
            }
        }

        val shouldDeposit = hasExcessLoot || totalSeeds > AutoChest.keepSeeds

        return shouldDeposit
    }

    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private fun getItemsToDeposit(): List<Pair<Int, Int>> {
        val itemsToDeposit = mutableListOf<Pair<Int, Int>>()
        val inventory = player.inventory ?: return emptyList()
        if (inventory.main == null) return emptyList()

        val isInventoryFull = !hasInventorySpace()
        var seedsKept = 0
        val maxSeedsToKeep = AutoChest.keepSeeds

        // Check inventory status without notification spam

        // Smart logic: deposit crops, manage seeds properly, protect tools
        for (slot in 0 until inventory.main.size) {
            val stack = inventory.main[slot]
            if (stack.isEmpty) continue

            // Skip tools and important items
            if (isImportantItem(stack)) {
                continue // Never drop tools, weapons, armor, etc.
            }

            when {
                // Always deposit harvested crops (food items)
                stack.item in listOf(Items.WHEAT, Items.CARROT, Items.POTATO,
                    Items.POISONOUS_POTATO, Items.BEETROOT, Items.NETHER_WART,
                    Items.PUMPKIN, Items.MELON_SLICE, Items.SUGAR_CANE, Items.CACTUS,
                    Items.KELP, Items.BAMBOO, Items.COCOA_BEANS, Items.SWEET_BERRIES) -> {
                    itemsToDeposit.add(slot to stack.count)
                    // Will deposit crop - no notification needed
                }

                // Handle seeds - keep exact amount specified in settings
                stack.item in filteredFarmlandItems || stack.item in filteredSoulsandItems -> {
                    val canKeep = maxSeedsToKeep - seedsKept

                    if (canKeep <= 0) {
                        // We already have enough seeds, drop entire stack
                        itemsToDeposit.add(slot to stack.count)
                        // Dropping excess seeds - no notification needed
                    } else if (stack.count > canKeep) {
                        // Keep only what we need, drop the rest
                        val toDrop = stack.count - canKeep
                        itemsToDeposit.add(slot to toDrop)
                        seedsKept += canKeep
                        // Dropping excess seeds - no notification needed
                    } else {
                        // Keep the entire stack
                        seedsKept += stack.count
                        // Keeping needed seeds - no notification needed
                    }
                }

                // If inventory is full, deposit other safe items
                isInventoryFull -> {
                    // Only deposit non-essential items when inventory is full
                    if (!isEssentialItem(stack)) {
                        itemsToDeposit.add(slot to stack.count)
                        // Depositing other items when full - no notification needed
                    }
                }
            }
        }

        // Return items to deposit without notification spam

        return itemsToDeposit
    }

    private fun isImportantItem(stack: net.minecraft.item.ItemStack): Boolean {
        val item = stack.item

        // Check if it's a tool (has durability and is meant for breaking/mining)
        if (stack.isDamageable && stack.maxDamage > 0) {
            val itemName = item.name.string.lowercase()

            // Check for tools
            val isTools = itemName.contains("sword") || itemName.contains("axe") ||
                         itemName.contains("pickaxe") || itemName.contains("shovel") ||
                         itemName.contains("hoe")

            // Check for armor
            val isArmor = itemName.contains("helmet") || itemName.contains("chestplate") ||
                         itemName.contains("leggings") || itemName.contains("boots")

            // Check for weapons
            val isWeapons = itemName.contains("bow") || itemName.contains("crossbow") ||
                           itemName.contains("trident")

            // Check for utility items
            val isUtility = itemName.contains("shears") || itemName.contains("flint_and_steel")

            if (isTools || isArmor || isWeapons || isUtility) {
                return true
            }
        }

        // Check for specific important items
        return when (item) {
            Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT,
            Items.NETHERITE_INGOT, Items.ENCHANTED_BOOK, Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
            Items.TOTEM_OF_UNDYING, Items.ELYTRA -> true
            else -> false
        }
    }

    private fun isEssentialItem(stack: net.minecraft.item.ItemStack): Boolean {
        // Essential items that should never be dropped even when inventory is full
        return isImportantItem(stack) ||
               stack.item in filteredFarmlandItems ||
               stack.item in filteredSoulsandItems
    }

    @Suppress("CognitiveComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun handleHopperDeposit() {
        // Function called - no need for notification

        if (!AutoChest.enabled || hopperTarget == null) {
            return
        }

        // Safety checks to prevent null pointer exceptions
        if (player.inventory == null || player.inventory.main == null) {
            return
        }

        val hopperPos = hopperTarget!!
        val distance = player.squaredDistanceTo(hopperPos.toCenterPos())

        // Check if we're close enough to the hopper (within 1.5 blocks)
        if (distance > 2.25) {
            return
        }

        val itemsToDeposit = getItemsToDeposit()
        // Check items to deposit without spamming notifications

        if (itemsToDeposit.isEmpty()) {
            // Finished depositing - resume farming
            isDepositingToHopper = false
            hopperTarget = null
            currentTarget = null
            failedDropCount = 0

            // Clear blocked hoppers after successful completion
            if (blockedHoppers.isNotEmpty()) {
                blockedHoppers.clear()
                notification("Hopper Reset",
                    "Cleared blocked hoppers after successful deposit", NotificationEvent.Severity.INFO)
            }

            notification("Hopper Done", "Finished depositing - resuming farming", NotificationEvent.Severity.SUCCESS)
            return
        }

        // Look down at the hopper when dropping items
        val downwardRotation = Rotation(
            player.yaw, // Keep current yaw (horizontal direction)
            90f // Look straight down (pitch = 90 degrees)
        )

        RotationManager.setRotationTarget(
            downwardRotation,
            configurable = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this@ModuleAutoFarm
        )

        // Add anti-kick protection - configurable delay between drops
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDropAttemptTime < AutoChest.dropDelay) {
            return
        }

        // Drop items one by one (process one item per call to avoid issues)
        for ((slot, _) in itemsToDeposit.take(1)) { // Process one slot per tick
            val stack = player.inventory.main[slot]

            if (stack.isEmpty) {
                continue
            }

            // Dropping item - no notification spam needed

            // Drop from any inventory slot - but only 1 item at a time to avoid kick
            val itemStack = player.inventory.main[slot]
            if (!itemStack.isEmpty) {
                if (slot < 9) {
                    // Hotbar slots (0-8) - use selected slot method for single item
                    val originalSlot = player.inventory.selectedSlot
                    player.inventory.selectedSlot = slot

                    // Always drop only 1 item at a time
                    player.dropSelectedItem(false) // Drop single item only

                    // Restore original selected slot
                    player.inventory.selectedSlot = originalSlot
                } else {
                    // Main inventory slots (9-35) - use inventory manipulation for single item
                    val stackToDrop = itemStack.copy()
                    stackToDrop.count = 1 // Always drop only 1 item

                    // Remove 1 item from inventory
                    if (itemStack.count == 1) {
                        player.inventory.removeStack(slot)
                    } else {
                        player.inventory.main[slot] = itemStack.copyWithCount(itemStack.count - 1)
                    }

                    // Drop single item
                    player.dropItem(stackToDrop, true)
                }

                // Update last drop time after successful drop
                lastDropAttemptTime = currentTime
                break // Only drop one item per call to avoid kick
            }

            break // Only drop one item per call
        }
    }

    var currentTarget: BlockPos? = null

    // Walk target stability variables
    private var lastWalkTargetUpdate = 0L
    private const val WALK_TARGET_UPDATE_COOLDOWN = 500L // 0.5 second cooldown





    val repeatable = tickHandler {

        // Handle hopper deposits when we're close to the hopper
        if (isDepositingToHopper) {
            handleHopperDeposit()
            return@tickHandler
        }

        // Return if the blink module is enabled
        if (ModuleBlink.running) {
            return@tickHandler
        }

        // Pause AutoFarm when AutoSeller is actively selling
        if (ModuleAutoSeller.enabled && ModuleAutoSeller.isActivelySelling) {
            return@tickHandler
        }


        // PRIORITY 1: Handle hopper deposits if AutoChest is enabled
        if (AutoChest.enabled) {
            val shouldDeposit = shouldDepositToHopper()
            val inventoryFull = !hasInventorySpace()
            val needsDeposit = shouldDeposit || inventoryFull

            // Remove frequent debug info to avoid notification spam

            if (needsDeposit) {
                hopperTarget = findNearestHopper()
                if (hopperTarget != null) {
                    isDepositingToHopper = true
                    currentTarget = hopperTarget
                    autoWalk.updateWalkTarget()

                    // Only show when first starting to go to hopper
                    notification("Going to Hopper",
                        "Target hopper at ${hopperTarget!!.x}, ${hopperTarget!!.y}, ${hopperTarget!!.z}",
                        NotificationEvent.Severity.SUCCESS)

                    return@tickHandler
                } else {
                    notification("Inventory Full", "No hopper found to deposit items", NotificationEvent.Severity.INFO)
                    // If inventory is full and no hopper found, disable if setting is enabled
                    if (inventoryFull && disableOnFullInventory) {
                        notification("Inventory is Full",
                            "AutoFarm has been disabled", NotificationEvent.Severity.ERROR)
                        disable()
                        enabled = false
                        return@tickHandler
                    }
                    // Continue farming even without hopper if disableOnFullInventory is false
                }
            }
        } else {
            // PRIORITY 2: When AutoChest is disabled, clear hopper targeting and only disable on full inventory
            // if setting is enabled
            if (hopperTarget != null || isDepositingToHopper) {
                hopperTarget = null
                isDepositingToHopper = false
                foundHoppers = emptyList()
                blockedHoppers.clear()
            }

            val inventoryFull = !hasInventorySpace()
            if (inventoryFull && disableOnFullInventory) {
                notification("Inventory is Full", "AutoFarm has been disabled", NotificationEvent.Severity.ERROR)
                disable()
                enabled = false
                return@tickHandler
            }
            // Continue farming even with full inventory if disableOnFullInventory is false
        }

        // PRIORITY 3: Only update farming targets if NOT going to hopper
        if (!isDepositingToHopper) {
            // Handle bone meal application if enabled
            if (AutoBoneMeal.enabled) {
                handleBoneMealApplication()
            }

            // Debug: Show when we're updating targets
            if (Math.random() < 0.05) { // 5% chance to avoid spam
                val targetInfo = currentTarget?.let { "${it.x},${it.y},${it.z}" } ?: "null"
                println("[AutoFarm] Updating farming targets, currentTarget=$targetInfo")
            }

            updateTarget()

            // Update hopper list for visualization
            if (AutoChest.enabled) {
                val nearestHopper = findNearestHopper()
                if (nearestHopper != null) {
                    val distance = player.squaredDistanceTo(nearestHopper.toCenterPos())
                    // Remove frequent debug to avoid spam
                }
            }
        } else {
            // When going to hopper, just update visualization
            if (AutoChest.enabled) {
                findNearestHopper()
            }

            // Remove frequent debug to avoid spam
        }

        // Handle hopper deposit when we're close enough - no interaction needed, just drop items
        // This is handled by handleHopperDeposit() above when isDepositingToHopper is true

        // If there is no currentTarget (a block close enough to be interacted with) walk if wanted
        currentTarget ?: run {
            // Only update walk target if NOT depositing to hopper and cooldown has passed
            if (!isDepositingToHopper) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastWalkTargetUpdate >= WALK_TARGET_UPDATE_COOLDOWN) {
                    val walkUpdated = autoWalk.updateWalkTarget()
                    lastWalkTargetUpdate = currentTime
                    if (Math.random() < 0.02) { // 2% chance to avoid spam
                        val msg = "[AutoFarm] No current target, called autoWalk.updateWalkTarget(), " +
                                "result: $walkUpdated"
                        println(msg)
                    }
                }
            } else {
                if (Math.random() < 0.02) {
                    println("[AutoFarm] Skipping walk update - depositing to hopper")
                }
            }
            return@tickHandler
        }

        // Only stop walking if we have a current target that's not null
        if (currentTarget != null) {
            autoWalk.stopWalk() // Stop walking if we found a target close enough to interact with it
            if (Math.random() < 0.05) {
                val targetInfo = "${currentTarget!!.x},${currentTarget!!.y},${currentTarget!!.z}"
                println("[AutoFarm] Stopping walk - found interaction target at $targetInfo")
            }
        } else {
            if (Math.random() < 0.02) {
                println("[AutoFarm] No currentTarget, continuing AutoWalk")
            }
        }

        val currentRotation = RotationManager.serverRotation

        val rayTraceResult = world.raycast(
            RaycastContext(
                player.eyePos,
                player.eyePos.add(currentRotation.directionVector.multiply(range.toDouble())),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player
            )
        )

        if (rayTraceResult == null || rayTraceResult.type != HitResult.Type.BLOCK) {
            return@tickHandler
        }

        val blockPos = rayTraceResult.blockPos
        var state = blockPos.getState() ?: return@tickHandler


        if (isTargeted(state, blockPos)) {
            // Check if we're close enough for reliable breaking (4 blocks max)
            val distanceToTarget = player.pos.distanceTo(blockPos.toCenterPos())
            val tooFarForBreaking = distanceToTarget > 4.0

            if (tooFarForBreaking) {
                // Too far for reliable breaking - walk closer
                autoWalk.walkTarget = blockPos.toCenterPos()
                autoWalk.updateWalkTarget()
                return@tickHandler
            }

            if (fortune) {
                // Swap to a fortune item to increase drops
                Slots.Hotbar.maxByOrNull { it.itemStack.getEnchantment(Enchantments.FORTUNE) }
                    ?.takeIf { it.itemStack.getEnchantment(Enchantments.FORTUNE) >= 1 }
                    ?.let {
                        SilentHotbar.selectSlotSilently(this, it, 2)
                    }
            }

            val direction = rayTraceResult.side

            val previousProgress = interaction.blockBreakingProgress
            if (interaction.updateBlockBreakingProgress(blockPos, direction)) {
                player.swingHand(Hand.MAIN_HAND)
                // Reset failed attempts on successful interaction start
            } else {
                // Block breaking didn't start - this might be a failed interaction
                if (previousProgress == 0) {
                    println("[AutoFarm] Failed to start breaking block at $blockPos")
                }
            }

            if (interaction.blockBreakingProgress == -1) {
                waitTicks(interactDelay.random())
            }
        } else {
            // For planting - check if we're targeting a jungle log directly or farmland below
            val plantingPos = if (isJungleLog(state.block)) {
                // Direct jungle log targeting for cocoa planting
                blockPos
            } else {
                // Traditional farmland check (below the targeted air block)
                blockPos.offset(rayTraceResult.side).down()
            }

            val plantingState = plantingPos.getState() ?: return@tickHandler

            val canPlant = isFarmBlockWithAir(plantingState, plantingPos) ||
                          isJungleLogWithAirAround(plantingState, plantingPos)

            // Check if we're close enough for reliable planting (2 blocks max)
            val distanceToPlantingPos = player.pos.distanceTo(plantingPos.toCenterPos())
            val tooFarForPlanting = distanceToPlantingPos > 2.0

            if (canPlant) {
                if (tooFarForPlanting) {
                    // Too far for reliable planting - walk closer
                    autoWalk.walkTarget = plantingPos.toCenterPos()
                    autoWalk.updateWalkTarget()
                    return@tickHandler
                }

                // Close enough for planting
                val item = when {
                    plantingState.block is FarmlandBlock -> itemForFarmland
                    plantingState.block is SoulSandBlock -> itemForSoulSand
                    isJungleLog(plantingState.block) -> itemForJungleLog
                    else -> null
                }

                item ?: return@tickHandler

                if (item != null) {
                    SilentHotbar.selectSlotSilently(this, item, AutoPlaceCrops.swapBackDelay.random())
                }

                // For jungle logs (cocoa), we need to click on the side, not use doPlacement
                if (isJungleLog(plantingState.block)) {
                    // Use right-click interaction for cocoa planting
                    player.swingHand(Hand.MAIN_HAND)


                    // Create BlockHitResult for the jungle log side
                    val jungleLogHitResult = createJungleLogHitResult(plantingPos, rayTraceResult)
                    if (jungleLogHitResult != null) {
                        interaction.interactBlock(player, Hand.MAIN_HAND, jungleLogHitResult)
                        waitTicks(2)
                    }
                } else {
                    doPlacement(rayTraceResult)
                }

                waitTicks(interactDelay.random())
            }
        }
    }


    // Searches for any blocks within the radius that need to be destroyed, such as crops.
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun updateTargetToBreakable(radius: Float, radiusSquared: Float, eyesPos: Vec3d): Boolean {
        val blocksToBreak = eyesPos.searchBlocksInCuboid(radius) { pos, state ->
            !state.isAir && isTargeted(state, pos) &&
                    getNearestPoint(eyesPos, Box(pos)).squaredDistanceTo(eyesPos) <= radiusSquared
        }.sortedBy { it.first.getCenterDistanceSquared() }


        for ((pos, state) in blocksToBreak) {

            val raytraceResult = raytraceBlock(
                player.eyePos,
                pos,
                state,
                range = range.toDouble(),
                wallsRange = wallRange.toDouble()
            )

            if (raytraceResult == null) {
                continue
            }

            val (rotation, _) = raytraceResult

            // set currentTarget to the new target
            currentTarget = pos
            // Reset micro-movement counters for new target
            // aim at target
            RotationManager.setRotationTarget(
                rotation,
                configurable = rotations,
                priority = Priority.IMPORTANT_FOR_USAGE_1,
                provider = this@ModuleAutoFarm
            )

            return true // We got a free angle at the block? No need to see more of them.
        }
        return false
    }

    // Searches for any blocks suitable for placing crops or nether wart on
    // returns ture if it found a target
    @Suppress("LongMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private fun updateTargetToPlaceable(radius: Float, radiusSquared: Float, eyesPos: Vec3d): Boolean {
        val hotbarItems = Slots.Hotbar.items

        val allowFarmland = hotbarItems.any { it in filteredFarmlandItems }
        val allowSoulsand = hotbarItems.any { it in filteredSoulsandItems }
        val allowJungleLog = hotbarItems.any { it in filteredJungleLogItems }


        if (!allowFarmland && !allowSoulsand && !allowJungleLog) return false

        val blocksToPlace =
            eyesPos.searchBlocksInCuboid(radius) { pos, state ->
                !state.isAir && (
                    isFarmBlockWithAir(state, pos, allowFarmland, allowSoulsand) ||
                    (allowJungleLog && isJungleLogWithAirAround(state, pos))
                ) && getNearestPoint(eyesPos, Box(pos)).squaredDistanceTo(eyesPos) <= radiusSquared
            }.map { it.first }.sortedBy { it.getCenterDistanceSquared() }

        for (pos in blocksToPlace) {
            val state = pos.getState() ?: continue

            val rotation = if (isJungleLog(state.block)) {
                // For cocoa, raytrace to an available side
                val jungleLogResult = raytraceJungleLogSide(
                    player.eyePos,
                    range = range.toDouble(),
                    wallsRange = wallRange.toDouble(),
                    pos
                ) ?: continue

                jungleLogResult.first
            } else {
                // For regular crops, plant on the upper side
                val upperSideResult = raytraceUpperBlockSide(
                    player.eyePos,
                    range = range.toDouble(),
                    wallsRange = wallRange.toDouble(),
                    pos
                ) ?: continue

                upperSideResult.rotation
            }


            // set currentTarget to the new target
            currentTarget = pos
            // Reset micro-movement counters for new target
            // aim at target
            RotationManager.setRotationTarget(
                rotation,
                configurable = rotations,
                priority = Priority.IMPORTANT_FOR_USAGE_1,
                provider = this@ModuleAutoFarm
            )

            return true // We got a free angle at the block? No need to see more of them.
        }
        return false
    }

    // Finds either a breakable target (such as crops, cactus, etc.)
    // or a placeable target (such as a farmblock or soulsand with air above).
    // It will prefer a breakable target
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private fun updateTarget() {
        currentTarget = null

        val radius = range
        val radiusSquared = radius * radius
        val eyesPos = player.eyePos

        // Can we find a breakable target?
        if (updateTargetToBreakable(radius, radiusSquared, eyesPos)) {
            // Debug: Show what type of target was found for breaking
            currentTarget?.let { target ->
                val state = target.getState()
                if (state?.block is CocoaBlock) {
                    println("[AutoFarm] Found breakable cocoa at ${target.x}, ${target.y}, ${target.z}")
                }
            }
            return
        }

        // If no standard targets found, try with increased radius for cocoa blocks
        if (AutoGarden.enabled && AutoGarden.targetCocoa) {
            val increasedRadius = radius + 0.5f
            val increasedRadiusSquared = increasedRadius * increasedRadius
            println("[AutoFarm] No standard targets found, trying increased radius for cocoa: $increasedRadius")
            if (updateTargetToBreakable(increasedRadius, increasedRadiusSquared, eyesPos)) {
                currentTarget?.let { target ->
                    val state = target.getState()
                    if (state?.block is CocoaBlock) {
                        println("[AutoFarm] Found cocoa with increased radius at ${target.x}, ${target.y}, ${target.z}")
                    }
                }
                return
            }
        }

        // Can we find a placeable target?
        if (AutoPlaceCrops.enabled && updateTargetToPlaceable(radius, radiusSquared, eyesPos)) {
            // Debug: Show what type of target was found for placing
            currentTarget?.let { target ->
                val state = target.getState()
                if (state?.block != null && isJungleLog(state.block)) {
                    println("[AutoFarm] Found jungle log for planting at ${target.x}, ${target.y}, ${target.z}")
                }
            }
            return
        }
    }


    @Suppress("ReturnCount")
    private fun handleBoneMealApplication() {
        val currentTime = System.currentTimeMillis()

        // Check if enough time has passed since last bone meal application
        if (currentTime - lastBoneMealTime < AutoBoneMeal.boneMealDelay) return

        // Reset bone mealed positions periodically to allow re-treatment
        if (currentTime - lastBoneMealResetTime > AutoBoneMeal.resetInterval) {
            boneMealedPositions.clear()
            lastBoneMealResetTime = currentTime
        }

        val boneMealSlot = player.inventory.main.indexOfFirst { it.item == Items.BONE_MEAL }
        if (boneMealSlot == -1) return

        val cropsNeedingBoneMeal = findCropsNeedingBoneMeal()
        if (cropsNeedingBoneMeal.isEmpty()) return

        // Filter out already bone mealed positions for even distribution
        val unprocessedCrops = cropsNeedingBoneMeal.filter { it !in boneMealedPositions }

        val targetCrop = if (unprocessedCrops.isNotEmpty()) {
            // Prioritize unprocessed crops for even distribution
            unprocessedCrops.minByOrNull { player.squaredDistanceTo(it.toCenterPos()) }
        } else {
            // If all crops have been processed, start over with closest crop
            boneMealedPositions.clear()
            cropsNeedingBoneMeal.minByOrNull { player.squaredDistanceTo(it.toCenterPos()) }
        } ?: return

        val searchRangeSquared = AutoBoneMeal.searchRange * AutoBoneMeal.searchRange
        if (player.squaredDistanceTo(targetCrop.toCenterPos()) > searchRangeSquared) return

        SilentHotbar.selectSlotSilently(this, boneMealSlot, 5)

        // Create BlockHitResult manually for bone meal application
        val blockHitResult = net.minecraft.util.hit.BlockHitResult(
            targetCrop.toCenterPos(),
            net.minecraft.util.math.Direction.UP,
            targetCrop,
            false
        )

        player.swingHand(Hand.MAIN_HAND)
        interaction.interactBlock(player, Hand.MAIN_HAND, blockHitResult)

        // Mark this position as bone mealed and update timing
        boneMealedPositions.add(targetCrop)
        lastBoneMealTime = currentTime
    }

    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private fun findCropsNeedingBoneMeal(): List<BlockPos> {
        val searchRange = AutoBoneMeal.searchRange.toInt()
        val crops = mutableListOf<BlockPos>()
        val playerPos = player.blockPos

        for (x in -searchRange..searchRange) {
            for (y in -searchRange..searchRange) {
                for (z in -searchRange..searchRange) {
                    val pos = playerPos.add(x, y, z)
                    val state = pos.getState() ?: continue

                    when (val block = state.block) {
                        is CropBlock -> {
                            if (!block.isMature(state) && canBoneMealCrop(block)) {
                                crops.add(pos)
                            }
                        }
                        is StemBlock -> {
                            if (state.get(StemBlock.AGE) < 7) {
                                crops.add(pos)
                            }
                        }
                        is NetherWartBlock -> {
                            if (state.get(NetherWartBlock.AGE) < 3) {
                                crops.add(pos)
                            }
                        }
                        is CocoaBlock -> {
                            // Cocoa can be bone mealed
                            if (AutoGarden.targetCocoa && state.get(CocoaBlock.AGE) < 2) {
                                crops.add(pos)
                            }
                        }
                    }
                }
            }
        }

        return crops
    }

    private fun canBoneMealCrop(crop: CropBlock): Boolean {
        return when (crop) {
            Blocks.WHEAT -> AutoGarden.targetWheat
            Blocks.CARROTS -> AutoGarden.targetCarrot
            Blocks.POTATOES -> AutoGarden.targetPotato
            Blocks.BEETROOTS -> AutoGarden.targetBeetroot
            else -> true // Allow bone meal on other crops by default
        }
    }



    fun isTargeted(state: BlockState, pos: BlockPos): Boolean {
        return when {
            AutoGarden.enabled -> isTargetedByAutoGarden(state)
            CropFilter.enabled -> isTargetedByCropFilter(state, pos)
            else -> isTargetedByDefault(state, pos)
        }
    }

    private fun isTargetedByAutoGarden(state: BlockState): Boolean {
        return when (val block = state.block) {
            is CropBlock -> isAutoGardenCropReady(block, state)
            is NetherWartBlock -> AutoGarden.targetNetherWart && state.get(NetherWartBlock.AGE) >= 3
            is PumpkinBlock -> AutoGarden.targetPumpkin
            Blocks.MELON -> AutoGarden.targetMelon
            is CocoaBlock -> AutoGarden.targetCocoa && state.get(CocoaBlock.AGE) >= 2
            is SweetBerryBushBlock -> AutoGarden.targetSweetBerries && state.get(SweetBerryBushBlock.AGE) >= 3
            else -> false
        }
    }

    private fun isAutoGardenCropReady(block: CropBlock, state: BlockState): Boolean {
        val shouldTarget = when (block) {
            Blocks.WHEAT -> AutoGarden.targetWheat
            Blocks.CARROTS -> AutoGarden.targetCarrot
            Blocks.POTATOES -> AutoGarden.targetPotato
            Blocks.BEETROOTS -> AutoGarden.targetBeetroot
            else -> false
        }

        return shouldTarget && block.isMature(state)
    }

    private fun isTargetedByCropFilter(state: BlockState, pos: BlockPos): Boolean {
        return when (val block = state.block) {
            is PumpkinBlock -> CropFilter.pumpkin
            Blocks.MELON -> CropFilter.melon
            is CropBlock -> isCropFilterCropReady(block, state)
            is NetherWartBlock -> CropFilter.netherWart && state.get(NetherWartBlock.AGE) >= 3
            is CocoaBlock -> CropFilter.cocoa && state.get(CocoaBlock.AGE) >= 2
            is SugarCaneBlock -> CropFilter.sugarCane && isAboveLast<SugarCaneBlock>(pos)
            is CactusBlock -> CropFilter.cactus && isAboveLast<CactusBlock>(pos)
            is KelpPlantBlock -> CropFilter.kelp && isAboveLast<KelpPlantBlock>(pos)
            is BambooBlock -> CropFilter.bamboo && isAboveLast<BambooBlock>(pos)
            else -> false
        }
    }

    private fun isCropFilterCropReady(block: CropBlock, state: BlockState): Boolean {
        if (!block.isMature(state)) return false

        return when (block) {
            Blocks.WHEAT -> CropFilter.wheat
            Blocks.CARROTS -> CropFilter.carrot
            Blocks.POTATOES -> CropFilter.potato
            Blocks.BEETROOTS -> CropFilter.beetroot
            else -> true
        }
    }

    private fun isTargetedByDefault(state: BlockState, pos: BlockPos): Boolean {
        return when (val block = state.block) {
            is PumpkinBlock -> true
            Blocks.MELON -> true
            is CropBlock -> block.isMature(state)
            is NetherWartBlock -> state.get(NetherWartBlock.AGE) >= 3
            is CocoaBlock -> state.get(CocoaBlock.AGE) >= 2
            is SugarCaneBlock -> isAboveLast<SugarCaneBlock>(pos)
            is CactusBlock -> isAboveLast<CactusBlock>(pos)
            is KelpPlantBlock -> isAboveLast<KelpPlantBlock>(pos)
            is BambooBlock -> isAboveLast<BambooBlock>(pos)
            else -> false
        }
    }

    /**
     * checks if the block is either a farmland or soulsand block and has air above it
     */
    private fun isFarmBlockWithAir(
        state: BlockState,
        pos: BlockPos,
        allowFarmland: Boolean = true,
        allowSoulsand: Boolean = true
    ): Boolean {
        return isFarmBlock(state, allowFarmland, allowSoulsand) && hasAirAbove(pos)
    }

    fun hasAirAbove(pos: BlockPos) = pos.up().getState()?.isAir == true


    /**
     * checks if the block is a jungle log and has air on sides for cocoa planting
     */
    private fun isJungleLogWithAirAround(state: BlockState, pos: BlockPos): Boolean {
        if (!isJungleLog(state.block)) return false

        // Check if there's air on any horizontal side for cocoa placement
        val directions = arrayOf(
            net.minecraft.util.math.Direction.NORTH,
            net.minecraft.util.math.Direction.SOUTH,
            net.minecraft.util.math.Direction.EAST,
            net.minecraft.util.math.Direction.WEST
        )

        return directions.any { direction ->
            val adjacentPos = pos.offset(direction)
            val adjacentState = adjacentPos.getState()
            adjacentState?.isAir == true
        }
    }

    /**
     * checks if the block is a jungle log
     */
    private fun isJungleLog(block: net.minecraft.block.Block): Boolean {
        return block == Blocks.JUNGLE_LOG || block == Blocks.STRIPPED_JUNGLE_LOG ||
               block == Blocks.JUNGLE_WOOD || block == Blocks.STRIPPED_JUNGLE_WOOD
    }

    /**
     * Raytrace to a horizontal side of a jungle log block for cocoa planting
     */
    @Suppress("UnusedParameter")
    private fun raytraceJungleLogSide(
        eyePos: Vec3d,
        range: Double,
        wallsRange: Double,
        pos: BlockPos
    ): Pair<Rotation, Vec3d>? {
        val directions = arrayOf(
            net.minecraft.util.math.Direction.NORTH,
            net.minecraft.util.math.Direction.SOUTH,
            net.minecraft.util.math.Direction.EAST,
            net.minecraft.util.math.Direction.WEST
        )

        // Try each horizontal direction to find an available side
        for (direction in directions) {
            val adjacentPos = pos.offset(direction)
            val adjacentState = adjacentPos.getState()

            // Check if this side has air for cocoa placement
            if (adjacentState?.isAir == true) {
                // Calculate target point on the side of the jungle log
                val targetVec = pos.toCenterPos().add(
                    direction.offsetX * 0.5,
                    0.0,  // Center height
                    direction.offsetZ * 0.5
                )

                // Create rotation to look at this side
                val rotation = getRotationTo(eyePos, targetVec)

                // Check if we can reach this side
                val distance = eyePos.distanceTo(targetVec)
                if (distance <= range) {
                    return Pair(rotation, targetVec)
                }
            }
        }

        return null
    }

    /**
     * Calculate rotation to look at a target position
     */
    private fun getRotationTo(from: Vec3d, to: Vec3d): Rotation {
        val diff = to.subtract(from)
        val distance = kotlin.math.sqrt(diff.x * diff.x + diff.z * diff.z)

        val yaw = (kotlin.math.atan2(diff.z, diff.x) * 180.0 / kotlin.math.PI - 90.0).toFloat()
        val pitch = (-kotlin.math.atan2(diff.y, distance) * 180.0 / kotlin.math.PI).toFloat()

        return Rotation(yaw, pitch)
    }

    /**
     * Create BlockHitResult for clicking on jungle log side for cocoa planting
     */
    @Suppress("UnusedParameter")
    private fun createJungleLogHitResult(
        jungleLogPos: BlockPos,
        rayTraceResult: net.minecraft.util.hit.BlockHitResult
    ): net.minecraft.util.hit.BlockHitResult? {
        val directions = arrayOf(
            net.minecraft.util.math.Direction.NORTH,
            net.minecraft.util.math.Direction.SOUTH,
            net.minecraft.util.math.Direction.EAST,
            net.minecraft.util.math.Direction.WEST
        )

        // Find an available side with air
        for (direction in directions) {
            val adjacentPos = jungleLogPos.offset(direction)
            val adjacentState = adjacentPos.getState()

            if (adjacentState?.isAir == true) {
                // Create hit result for this side of the jungle log
                val hitPos = jungleLogPos.toCenterPos().add(
                    direction.offsetX * 0.5,
                    0.0,
                    direction.offsetZ * 0.5
                )

                return net.minecraft.util.hit.BlockHitResult(
                    hitPos,
                    direction,
                    jungleLogPos,
                    false
                )
            }
        }

        return null
    }

    private fun isFarmBlock(state: BlockState, allowFarmland: Boolean, allowSoulsand: Boolean): Boolean {
        return when (state.block) {
            is FarmlandBlock -> allowFarmland
            is SoulSandBlock -> allowSoulsand
            else -> false
        }
    }

    private inline fun <reified T : Block> isAboveLast(pos: BlockPos): Boolean {
        return pos.down().getBlock() is T && pos.down(2).getBlock() !is T
    }

    override fun enable() {
        ChunkScanner.subscribe(AutoFarmBlockTracker)
    }

    override fun disable() {
        ChunkScanner.unsubscribe(AutoFarmBlockTracker)
        currentTarget = null
        // Reset walk target stability state
        lastWalkTargetUpdate = 0L
    }

}
