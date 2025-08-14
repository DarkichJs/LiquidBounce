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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import kotlin.random.Random

/**
 * AutoBuyer module
 * 
 * Automatically buys seeds when you have 2+ stacks of crops
 * Always keeps 1 stack for planting, sells the rest
 */
object ModuleAutoBuyer : ClientModule("AutoBuyer", Category.MISC) {

    // Item selection
    private val cropType by enumChoice("CropType", CropType.CARROT)
    
    // Auto start settings
    private val autoStart by boolean("AutoStart", true)
    private val minStacks by int("MinStacks", 2, 2..10)
    
    // Debug and timing
    private val debug by boolean("Debug", false)
    private val actionDelay by int("ActionDelay", 500, 100..2000, "ms")

    enum class CropType(override val choiceName: String, val item: net.minecraft.item.Item) : NamedChoice {
        CARROT("Carrot", Items.CARROT),
        POTATO("Potato", Items.POTATO),
        WHEAT("Wheat", Items.WHEAT_SEEDS),
        BEETROOT("Beetroot", Items.BEETROOT)
    }

    // State management
    private var isProcessing = false
    private var lastActionTime = 0L
    private var stacksToSell = 0
    private var currentCycle = 0
    private var waitingForMenuClose = false

    val repeatable = tickHandler {
        if (!enabled) return@tickHandler
        
        val currentTime = System.currentTimeMillis()
        
        // Check if we should auto-start
        if (!isProcessing && autoStart) {
            val stackCount = countStacks(cropType.item)
            if (stackCount >= minStacks) {
                startBuyingProcess(stackCount)
            }
        }
        
        // Prevent spam actions
        if (currentTime - lastActionTime < actionDelay) {
            return@tickHandler
        }
    }

    private fun countStacks(item: net.minecraft.item.Item): Int {
        val inventory = player.inventory
        var stackCount = 0
        
        // Count stacks in main inventory (including hotbar)
        for (i in 0 until inventory.main.size) {
            val stack = inventory.main[i]
            if (stack.item == item && stack.count >= 32) { // Consider 32+ as a stack for crops
                stackCount++
            }
        }
        
        if (debug && stackCount > 0) {
            chat(regular("Found "), variable("$stackCount"), regular(" stacks of "), 
                 variable(cropType.choiceName))
        }
        
        return stackCount
    }

    private fun startBuyingProcess(stackCount: Int) {
        if (isProcessing) {
            if (debug) {
                chat(regular("AutoBuyer already processing, skipping"))
            }
            return
        }
        
        stacksToSell = stackCount - 1 // Keep 1 stack for planting
        currentCycle = 0
        isProcessing = true
        waitingForMenuClose = false
        
        if (debug) {
            chat(regular("Starting AutoBuyer: "), variable("$stackCount"), 
                 regular(" stacks found, will sell "), variable("$stacksToSell"), 
                 regular(" stacks (one by one)"))
        }
        
        startNextCycle()
    }
    
    private fun startNextCycle() {
        if (currentCycle >= stacksToSell) {
            finishProcess()
            return
        }
        
        currentCycle++
        if (debug) {
            chat(regular("Starting cycle "), variable("$currentCycle"), 
                 regular(" of "), variable("$stacksToSell"))
        }
        
        // Send /buyer command for this cycle
        network.sendCommand("buyer")
        lastActionTime = System.currentTimeMillis()
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        if (!enabled || !isProcessing) return@handler
        
        val screen = event.screen
        if (screen is HandledScreen<*>) {
            val title = screen.title.string
            if (debug) {
                chat(regular("Opened screen: "), variable(title))
            }
            
            // Handle buyer menu - check for Russian "Выбери секцию"
            if (title.contains("Buyer") || title.contains("Покупатель") || title.contains("Выбери секцию")) {
                Thread {
                    Thread.sleep(Random.nextLong(800, 1500))
                    handleBuyerMenu(screen.screenHandler)
                }.start()
            }
            
            // Handle cooked items menu - check for Russian "Скупщик еды"
            else if (title.contains("cooked") || title.contains("приготовленные") || title.contains("Скупщик еды")) {
                Thread {
                    Thread.sleep(Random.nextLong(1500, 2500)) // Increased delay for menu loading
                    handleCookedMenu(screen.screenHandler)
                }.start()
            }
        }
        
        // Handle screen closing
        if (screen == null && isProcessing && waitingForMenuClose) {
            waitingForMenuClose = false
            if (debug) {
                chat(regular("Menu closed, cycle "), variable("$currentCycle"), regular(" completed"))
            }
            
            // Start next cycle after a delay
            Thread {
                Thread.sleep(Random.nextLong(2000, 3000)) // Wait before starting next cycle
                startNextCycle()
            }.start()
        }
    }

    private fun handleBuyerMenu(screenHandler: ScreenHandler) {
        if (!isProcessing) return
        
        // Look for cooked food button
        for (slot in 0 until screenHandler.slots.size) {
            val stack = screenHandler.slots[slot].stack
            if (!stack.isEmpty) {
                // Check if it's any cooked food item
                val isCookedFood = when (stack.item) {
                    Items.COOKED_MUTTON, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, 
                    Items.COOKED_CHICKEN, Items.COOKED_SALMON, Items.COOKED_COD -> true
                    else -> false
                }
                
                if (isCookedFood) {
                    if (debug) {
                        chat(regular("Found cooked food ("), variable("${stack.item}"), 
                             regular(") in slot "), variable("$slot"), regular(", clicking on it"))
                    }
                    
                    interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                    lastActionTime = System.currentTimeMillis()
                    return
                }
                
                // Also check by display name
                val displayName = stack.name.string.lowercase()
                if (displayName.contains("mutton") || displayName.contains("баранина") || 
                    displayName.contains("еда") || displayName.contains("food") ||
                    displayName.contains("мясо") || displayName.contains("beef") ||
                    displayName.contains("pork") || displayName.contains("chicken")) {
                    
                    if (debug) {
                        chat(regular("Found food by name '"), variable(displayName), 
                             regular("' in slot "), variable("$slot"))
                    }
                    
                    interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                    lastActionTime = System.currentTimeMillis()
                    return
                }
            }
        }
        
        if (debug) {
            chat(regular("Food button not found in buyer menu"))
            // Debug: show all items in menu  
            for (slot in 0 until screenHandler.slots.size) {
                val stack = screenHandler.slots[slot].stack
                if (!stack.isEmpty) {
                    val name = stack.name.string
                    chat(regular("  Slot "), variable("$slot"), regular(": "), variable("${stack.item}"), 
                         regular(" ('"), variable(name), regular("')"))
                }
            }
        }
        
        // If food button not found, finish process to prevent infinite loop
        finishProcess()
    }

    private fun handleCookedMenu(screenHandler: ScreenHandler) {
        if (!isProcessing || waitingForMenuClose) return
        
        val targetItem = cropType.item
        
        // Look for our target crop in the menu
        for (slot in 0 until screenHandler.slots.size) {
            val stack = screenHandler.slots[slot].stack
            if (!stack.isEmpty) {
                // Check by item type first
                if (stack.item == targetItem) {
                    if (debug) {
                        chat(regular("Found "), variable(cropType.choiceName), 
                             regular(" by item type in slot "), variable("$slot"), 
                             regular(" - selling 1 stack (cycle "), variable("$currentCycle"), regular(")"))
                    }
                    
                    // Click on the item to sell ONE stack
                    interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                    lastActionTime = System.currentTimeMillis()
                    
                    // Mark that we're waiting for menu to close before starting next cycle
                    waitingForMenuClose = true
                    
                    // Close menu after a short delay
                    Thread {
                        Thread.sleep(Random.nextLong(1000, 1500))
                        mc.execute {
                            player.closeHandledScreen()
                        }
                    }.start()
                    return
                }
                
                // Also check by display name for Russian names
                val displayName = stack.name.string.lowercase()
                val isTargetCrop = when (cropType) {
                    CropType.CARROT -> displayName.contains("морковь") || displayName.contains("carrot")
                    CropType.POTATO -> displayName.contains("картофель") || displayName.contains("potato") 
                    CropType.WHEAT -> displayName.contains("пшеница") || displayName.contains("wheat")
                    CropType.BEETROOT -> displayName.contains("свекла") || displayName.contains("beetroot") || displayName.contains("свёкла")
                }
                
                if (isTargetCrop) {
                    if (debug) {
                        chat(regular("Found "), variable(cropType.choiceName), 
                             regular(" by name '"), variable(displayName), 
                             regular("' in slot "), variable("$slot"), 
                             regular(" - selling 1 stack (cycle "), variable("$currentCycle"), regular(")"))
                    }
                    
                    // Click on the item to sell ONE stack
                    interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                    lastActionTime = System.currentTimeMillis()
                    
                    // Mark that we're waiting for menu to close before starting next cycle
                    waitingForMenuClose = true
                    
                    // Close menu after a short delay
                    Thread {
                        Thread.sleep(Random.nextLong(1000, 1500))
                        mc.execute {
                            player.closeHandledScreen()
                        }
                    }.start()
                    return
                }
            }
        }
        
        if (debug) {
            chat(regular(cropType.choiceName), regular(" not found in cooked menu"))
            // Debug: show all items in menu
            for (slot in 0 until screenHandler.slots.size) {
                val stack = screenHandler.slots[slot].stack
                if (!stack.isEmpty) {
                    val name = stack.name.string
                    chat(regular("  Slot "), variable("$slot"), regular(": "), variable("${stack.item}"), 
                         regular(" ('"), variable(name), regular("')"))
                }
            }
        }
    }


    private fun finishProcess() {
        if (debug) {
            chat(regular("AutoBuyer process finished - sold "), variable("$currentCycle"), 
                 regular(" stacks"))
        }
        
        isProcessing = false
        stacksToSell = 0
        currentCycle = 0
        waitingForMenuClose = false
        lastActionTime = System.currentTimeMillis()
        
        // Close menu if still open
        Thread {
            Thread.sleep(Random.nextLong(1000, 2000))
            mc.execute {
                if (player.currentScreenHandler != player.playerScreenHandler) {
                    player.closeHandledScreen()
                }
            }
        }.start()
    }

    override fun enable() {
        super.enable()
        if (debug) {
            chat(regular("AutoBuyer enabled for "), variable(cropType.choiceName))
        }
    }

    override fun disable() {
        super.disable()
        isProcessing = false
        stacksToSell = 0
        currentCycle = 0
        waitingForMenuClose = false
        if (debug) {
            chat(regular("AutoBuyer disabled"))
        }
    }
}