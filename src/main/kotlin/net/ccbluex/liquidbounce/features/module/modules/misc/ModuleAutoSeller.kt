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

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.ChatReceiveEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import kotlin.random.Random

/**
 * AutoSeller module
 * 
 * Automatically sells full stacks of items through auction house (/ah sell)
 * Manages storage overflow by removing items from auction house when storage is full
 */
@Suppress("TooManyFunctions")
object ModuleAutoSeller : ClientModule("AutoSeller", Category.MISC) {

    // Timing settings  
    private val ahCooldown by int("AhCooldown", 10000, 5000..30000, "ms")
    
    // Manual price input
    private val manualPrice by text("ManualPrice", "")
    
    // Price settings for different items
    private object PriceSettings : ToggleableConfigurable(this, "PriceSettings", true) {
        val carrotPrice by int("CarrotPrice", 100, 1..10000)
        val potatoPrice by int("PotatoPrice", 100, 1..10000) 
        val wheatPrice by int("WheatPrice", 80, 1..10000)
        val beetrootPrice by int("BeetrootPrice", 120, 1..10000)
        val netherWartPrice by int("NetherWartPrice", 200, 1..10000)
        val cocoaBeansPrice by int("CocoaBeansPrice", 150, 1..10000)
        val pumpkinPrice by int("PumpkinPrice", 300, 1..10000)
        val melonSlicePrice by int("MelonSlicePrice", 50, 1..10000)
        val sugarCanePrice by int("SugarCanePrice", 80, 1..10000)
        val cactusPrice by int("CactusPrice", 70, 1..10000)
        val bambooPrice by int("BambooPrice", 60, 1..10000)
        val kelpPrice by int("KelpPrice", 40, 1..10000)
    }

    // Debug and monitoring settings
    private val debug by boolean("Debug", false)
    
    init {
        tree(PriceSettings)
    }

    // State management
    private var lastSellTime = 0L
    private var lastAhOpenTime = 0L
    private var isManagingAh = false
    private var pendingItemType: String? = null
    private var waitingForResponse = false
    private var waitingStartTime = 0L
    private var storageFullDetected = false
    
    // Public property for other modules to check if AutoSeller is actively selling
    val isActivelySelling: Boolean
        get() = waitingForResponse || isManagingAh || hasItemsToSell()
    
    // Supported items and their data
    private val supportedItems = mapOf(
        Items.CARROT to ItemData("carrot") { PriceSettings.carrotPrice },
        Items.POTATO to ItemData("potato") { PriceSettings.potatoPrice },
        Items.WHEAT to ItemData("wheat") { PriceSettings.wheatPrice },
        Items.BEETROOT to ItemData("beetroot") { PriceSettings.beetrootPrice },
        Items.NETHER_WART to ItemData("nether_wart") { PriceSettings.netherWartPrice },
        Items.COCOA_BEANS to ItemData("cocoa_beans") { PriceSettings.cocoaBeansPrice },
        Items.PUMPKIN to ItemData("pumpkin") { PriceSettings.pumpkinPrice },
        Items.MELON_SLICE to ItemData("melon_slice") { PriceSettings.melonSlicePrice },
        Items.SUGAR_CANE to ItemData("sugar_cane") { PriceSettings.sugarCanePrice },
        Items.CACTUS to ItemData("cactus") { PriceSettings.cactusPrice },
        Items.BAMBOO to ItemData("bamboo") { PriceSettings.bambooPrice },
        Items.KELP to ItemData("kelp") { PriceSettings.kelpPrice }
    )

    private data class ItemData(val name: String, val priceGetter: () -> Int)

    val repeatable = tickHandler {
        if (!PriceSettings.enabled) {
            return@tickHandler
        }

        val currentTime = System.currentTimeMillis()
        
        // Check if we've been waiting too long for response (timeout after 10 seconds)
        if (waitingForResponse && currentTime - waitingStartTime > 10000) {
            if (debug) {
                println("[AutoSeller] Response timeout, resetting waiting state")
            }
            waitingForResponse = false
            waitingStartTime = 0L
        }
        
        // Don't do anything if we're managing AH or waiting for response
        if (isManagingAh || waitingForResponse) {
            return@tickHandler
        }

        // Check for full stacks to sell
        findFullStacksToSell()?.let { sellData ->
            // Add small delay only if we just sold something recently
            if (currentTime - lastSellTime > 1000) { // Minimum 1 second between sells
                if (debug) {
                    val location = if (sellData.isInHotbar) "hotbar" else "inventory"
                    val msg = "[AutoSeller] Attempting to sell ${sellData.itemData.name} from $location slot " + 
                            "${sellData.slot}"
                    println(msg)
                }
                val sellParams = SellParams(
                    sellData.slot, sellData.item, sellData.itemData.name, 
                    sellData.itemData.priceGetter(), sellData.isInHotbar
                )
                sellItem(sellParams)
                lastSellTime = currentTime
            } else if (debug) {
                println("[AutoSeller] Too soon to sell again, waiting...")
            }
        }
    }

    private data class SellData(
        val slot: Int, 
        val item: net.minecraft.item.Item, 
        val itemData: ItemData, 
        val isInHotbar: Boolean = false
    )
    private data class SellParams(
        val slot: Int, 
        val item: net.minecraft.item.Item, 
        val itemName: String, 
        val price: Int, 
        val isInHotbar: Boolean
    )

    private fun findFullStacksToSell(): SellData? {
        // For each supported item, count total stacks and only sell if we have more than 1 stack
        for ((item, itemData) in supportedItems) {
            val stackCount = countFullStacks(item)
            if (stackCount > 1) {
                // Find a stack to sell (we have more than 1, so we can sell one)
                findFullStackToSell(item, itemData)?.let { return it }
            }
        }
        return null
    }
    
    private fun countFullStacks(item: net.minecraft.item.Item): Int {
        val inventory = player.inventory
        var stackCount = 0
        
        // Count in hotbar (0-8)
        for (slot in 0 until 9) {
            val stack = inventory.getStack(slot)
            if (stack.item == item && stack.count >= stack.maxCount) {
                stackCount++
            }
        }
        
        // Count in main inventory (9-35)
        for (slot in 9 until inventory.main.size) {
            val stack = inventory.main[slot]
            if (stack.item == item && stack.count >= stack.maxCount) {
                stackCount++
            }
        }
        
        if (debug && stackCount > 0) {
            val itemData = supportedItems[item]
            println("[AutoSeller] Found $stackCount full stacks of ${itemData?.name ?: item}")
        }
        
        return stackCount
    }
    
    private fun findFullStackToSell(item: net.minecraft.item.Item, itemData: ItemData): SellData? {
        // First check hotbar slots (0-8)
        findFullStackInHotbar(item, itemData)?.let { return it }
        
        // Then check main inventory slots (9-35)
        return findFullStackInInventory(item, itemData)
    }
    
    private fun findFullStackInHotbar(item: net.minecraft.item.Item, itemData: ItemData): SellData? {
        val inventory = player.inventory
        
        for (slot in 0 until 9) {
            val stack = inventory.getStack(slot)
            if (stack.item == item && stack.count >= stack.maxCount) {
                if (debug) {
                    val msg = "[AutoSeller] Found full stack in hotbar: ${stack.count}x " +
                            "${itemData.name} in slot $slot"
                    println(msg)
                }
                return SellData(slot, stack.item, itemData, true)
            }
        }
        
        return null
    }
    
    private fun findFullStackInInventory(item: net.minecraft.item.Item, itemData: ItemData): SellData? {
        val inventory = player.inventory
        
        for (slot in 9 until inventory.main.size) {
            val stack = inventory.main[slot]
            if (stack.item == item && stack.count >= stack.maxCount) {
                if (debug) {
                    val msg = "[AutoSeller] Found full stack in inventory: ${stack.count}x " +
                            "${itemData.name} in slot $slot"
                    println(msg)
                }
                return SellData(slot, stack.item, itemData, false)
            }
        }
        
        return null
    }
    
    private fun hasItemsToSell(): Boolean {
        if (!enabled || !PriceSettings.enabled) return false
        return findFullStacksToSell() != null
    }

    private fun sellItem(params: SellParams) {
        if (params.isInHotbar) {
            selectHotbarSlot(params.slot, params.itemName)
        } else {
            moveItemToHand(params.slot, params.item, params.itemName)
        }

        val finalCheck = getFinalItemCheck(params.slot, params.item, params.isInHotbar)
        if (!validateFinalCheck(finalCheck, params.item)) return

        executeSellCommand(params.price, params.itemName, finalCheck, params.slot, params.isInHotbar)
    }
    
    private fun selectHotbarSlot(slot: Int, itemName: String) {
        if (debug) {
            println("[AutoSeller] Switching to hotbar slot $slot with $itemName")
        }
        player.inventory.selectedSlot = slot
        Thread.sleep(100)
    }
    
    private fun moveItemToHand(slot: Int, item: net.minecraft.item.Item, itemName: String): Boolean {
        if (!ensureItemInHand(slot, item, itemName)) {
            if (debug) {
                println("[AutoSeller] Failed to get $itemName in hand, skipping sell")
            }
            return false
        }
        return true
    }
    
    private fun getFinalItemCheck(
        slot: Int, 
        item: net.minecraft.item.Item, 
        isInHotbar: Boolean
    ): net.minecraft.item.ItemStack {
        return if (isInHotbar) {
            player.inventory.getStack(slot)
        } else {
            player.mainHandStack
        }
    }
    
    private fun validateFinalCheck(
        finalCheck: net.minecraft.item.ItemStack, 
        item: net.minecraft.item.Item
    ): Boolean {
        if (finalCheck.isEmpty || finalCheck.item != item || finalCheck.count < 64) {
            if (debug) {
                val held = "${finalCheck.item} (${finalCheck.count})"
                println("[AutoSeller] Final check failed - not holding correct item. Holding: $held")
            }
            return false
        }
        return true
    }
    
    private fun executeSellCommand(
        price: Int, 
        itemName: String, 
        finalCheck: net.minecraft.item.ItemStack, 
        slot: Int, 
        isInHotbar: Boolean
    ) {
        val finalPrice = getEffectivePrice(price)
        val command = "ah sell $finalPrice"
        
        network.sendCommand(command)
        waitingForResponse = true
        waitingStartTime = System.currentTimeMillis()
        pendingItemType = itemName
        
        if (debug) {
            val location = if (isInHotbar) "hotbar slot $slot" else "hand"
            println("[AutoSeller] Sent command: /$command for ${finalCheck.count}x $itemName from $location")
        }
    }

    private fun ensureItemInHand(slot: Int, targetItem: net.minecraft.item.Item, itemName: String): Boolean {
        if (debug) {
            println("[AutoSeller] Ensuring $itemName is in hand from slot $slot")
        }
        
        // Check if we already have the correct item
        if (isCorrectItemInHand(targetItem, itemName)) {
            return true
        }

        // Clear hand if holding wrong item
        clearHandIfNeeded(itemName)

        // Pick up the target item
        val result = pickupTargetItem(slot, targetItem, itemName)
        if (debug) {
            println("[AutoSeller] Pickup result: $result")
        }
        return result
    }

    private fun isCorrectItemInHand(targetItem: net.minecraft.item.Item, itemName: String): Boolean {
        val heldItem = player.mainHandStack
        if (!heldItem.isEmpty && heldItem.item == targetItem && heldItem.count >= 64) {
            if (debug) {
                println("[AutoSeller] Already holding full stack of $itemName (${heldItem.count})")
            }
            return true
        }
        return false
    }

    private fun clearHandIfNeeded(itemName: String) {
        val heldItem = player.mainHandStack
        if (!heldItem.isEmpty) {
            if (debug) {
                val current = "${heldItem.item} (${heldItem.count})"
                println("[AutoSeller] Putting back $current before picking up $itemName")
            }
            putItemBackInInventory()
        }
    }

    private fun pickupTargetItem(slot: Int, targetItem: net.minecraft.item.Item, itemName: String): Boolean {
        val screenHandler = player.currentScreenHandler
        if (screenHandler == null) {
            if (debug) {
                println("[AutoSeller] No screen handler available")
            }
            return false
        }

        // Convert inventory slot to screen slot (inventory starts at slot 9)
        val screenSlot = slot + 9
        if (screenSlot >= screenHandler.slots.size) {
            if (debug) {
                println("[AutoSeller] Invalid slot: $screenSlot (max: ${screenHandler.slots.size})")
            }
            return false
        }

        val inventoryStack = screenHandler.slots[screenSlot].stack
        if (!isValidStackForPickup(inventoryStack, targetItem, itemName, screenSlot)) {
            return false
        }

        return performPickupAndVerify(screenHandler, screenSlot, targetItem, itemName, inventoryStack)
    }

    private fun isValidStackForPickup(
        stack: net.minecraft.item.ItemStack,
        targetItem: net.minecraft.item.Item,
        itemName: String,
        slot: Int
    ): Boolean {
        if (stack.item == targetItem && stack.count >= 64) {
            return true
        }

        if (debug) {
            val current = "${stack.item} (${stack.count})"
            println("[AutoSeller] Slot $slot doesn't contain full stack of $itemName - contains $current")
        }
        return false
    }

    private fun performPickupAndVerify(
        screenHandler: ScreenHandler,
        slot: Int,
        targetItem: net.minecraft.item.Item,
        itemName: String,
        inventoryStack: net.minecraft.item.ItemStack
    ): Boolean {
        if (debug) {
            println("[AutoSeller] Attempting to pick up $itemName from slot $slot (${inventoryStack.count} items)")
        }
        
        // Move full stack to hand
        interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)

        // Wait for the action to complete
        Thread.sleep(300)
        
        val success = verifyPickupSuccess(targetItem, itemName)
        if (debug) {
            if (success) {
                println("[AutoSeller] Successfully picked up $itemName")
            } else {
                println("[AutoSeller] Failed to pick up $itemName")
            }
        }
        
        return success
    }

    private fun verifyPickupSuccess(targetItem: net.minecraft.item.Item, itemName: String): Boolean {
        val newHeldItem = player.mainHandStack
        if (!newHeldItem.isEmpty && newHeldItem.item == targetItem && newHeldItem.count >= 64) {
            if (debug) {
                println("[AutoSeller] Successfully holding ${newHeldItem.count}x $itemName")
            }
            return true
        }

        if (debug) {
            val current = "${newHeldItem.item} (${newHeldItem.count})"
            println("[AutoSeller] Failed to pick up $itemName - now holding: $current")
        }
        return false
    }

    private fun putItemBackInInventory() {
        val screenHandler = player.currentScreenHandler ?: return
        
        if (debug) {
            val heldItem = player.mainHandStack
            println("[AutoSeller] Putting back ${heldItem.item} (${heldItem.count}) to inventory")
        }
        
        // Find empty slot or slot with same item to stack
        for (slot in 9 until screenHandler.slots.size) { // Skip hotbar slots 0-8
            val slotStack = screenHandler.slots[slot].stack
            if (slotStack.isEmpty) {
                // Found empty slot, put item here
                interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                Thread.sleep(100)
                return
            }
        }
        
        // If no empty slot found, just drop the item or put in any available slot
        // This is a fallback - normally shouldn't happen
        if (debug) {
            println("[AutoSeller] No empty inventory slot found, keeping item in hand")
        }
    }


    private fun openAhForCleanup() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAhOpenTime < ahCooldown) {
            if (debug) {
                println("[AutoSeller] AH cooldown active, waiting...")
            }
            return
        }
        
        network.sendCommand("ah")
        isManagingAh = true
        lastAhOpenTime = currentTime
        
        if (debug) {
            println("[AutoSeller] Opening auction house for cleanup")
        }
    }

    @Suppress("unused")
    private val chatHandler = handler<ChatReceiveEvent> { event ->
        if (!enabled || !PriceSettings.enabled) return@handler
        
        val message = event.message
        
        // Check for successful sale
        if (message.contains("Предмет") && message.contains("выставлен на продажу!")) {
            if (debug) {
                println("[AutoSeller] Item successfully listed for sale!")
            }
            waitingForResponse = false
            waitingStartTime = 0L
            pendingItemType = null
            storageFullDetected = false
        }
        
        // Check for storage full error
        else if (message.contains("Освободите хранилище или уберите предметы с продажи")) {
            if (debug) {
                println("[AutoSeller] Storage full detected, need to clean up auction house")
            }
            waitingForResponse = false
            waitingStartTime = 0L
            storageFullDetected = true
            
            // Start cleanup process with delay
            Thread {
                Thread.sleep(Random.nextLong(2000, 4000)) // 2-4 second delay
                if (enabled && storageFullDetected) {
                    openAhForCleanup()
                }
            }.start()
        }
        
        // Check for AFK mode error - activate AutoFarm for 10 seconds
        else if (message.contains("Данная команда недоступна в режиме AFK")) {
            if (debug) {
                println("[AutoSeller] AFK mode detected, activating AutoFarm for 10 seconds")
            }
            waitingForResponse = false
            waitingStartTime = 0L
            
            // Activate AutoFarm for 10 seconds to exit AFK mode
            Thread {
                try {
                    // Enable AutoFarm if it's not already enabled
                    if (!net.ccbluex.liquidbounce.features.module.modules.world.autofarm.ModuleAutoFarm.enabled) {
                        net.ccbluex.liquidbounce.features.module.modules.world.autofarm.ModuleAutoFarm.enabled = true
                        if (debug) {
                            println("[AutoSeller] Enabled AutoFarm to exit AFK mode")
                        }
                    }
                    
                    // Wait 10 seconds
                    Thread.sleep(10000)
                    
                    if (debug) {
                        println("[AutoSeller] 10 seconds passed, will retry selling")
                    }
                    
                } catch (e: Exception) {
                    if (debug) {
                        println("[AutoSeller] AutoFarm activation failed: ${e.message}")
                    }
                }
            }.start()
        }
        
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        if (!enabled || !PriceSettings.enabled) return@handler
        
        val screen = event.screen
        if (screen is HandledScreen<*>) {
            val title = screen.title.string
            if (debug) {
                println("[AutoSeller] Opened screen: $title")
            }
            
            // Handle auction house menu
            if (title.contains("Функции") || title.contains("Аукцион") || title.contains("1861")) {
                if (storageFullDetected) {
                    Thread {
                        Thread.sleep(Random.nextLong(1000, 2000)) // Wait 1-2 seconds
                        handleAuctionHouseMenu(screen.screenHandler)
                    }.start()
                }
            }
            
            // Handle storage cleanup menu  
            else if (title.contains("Хранилище")) {
                Thread {
                    Thread.sleep(Random.nextLong(800, 1500)) // Wait 0.8-1.5 seconds
                    handleStorageMenu(screen.screenHandler)
                }.start()
            }
        }
        
        // Handle screen closing
        if (screen == null && isManagingAh) {
            Thread {
                Thread.sleep(Random.nextLong(2000, 3000)) // Wait 2-3 seconds before finishing
                isManagingAh = false
                storageFullDetected = false
                if (debug) {
                    println("[AutoSeller] Screen closed, finished managing auction house")
                }
            }.start()
        }
    }

    private fun handleAuctionHouseMenu(screenHandler: ScreenHandler) {
        if (!isManagingAh) return
        
        // Look for ender chest (storage) in the auction house menu
        for (slot in 0 until screenHandler.slots.size) {
            val stack = screenHandler.slots[slot].stack
            if (stack.item == Items.ENDER_CHEST) {
                if (debug) {
                    println("[AutoSeller] Found ender chest in slot $slot, clicking on it")
                }
                
                // Click on ender chest
                interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
                return
            }
        }
        
        if (debug) {
            println("[AutoSeller] Ender chest not found in auction house menu")
        }
    }

    private fun handleStorageMenu(screenHandler: ScreenHandler) {
        if (!isManagingAh || pendingItemType == null) return
        
        val targetItem = findTargetItemForPendingType()
        if (targetItem == null) return
        
        val itemsRemoved = removeItemsFromStorage(screenHandler, targetItem)
        logStorageCleanupResult(itemsRemoved)
        scheduleMenuClose()
    }

    private fun findTargetItemForPendingType(): net.minecraft.item.Item? {
        val targetItem = supportedItems.entries.find { it.value.name == pendingItemType }?.key
        if (targetItem == null && debug) {
            println("[AutoSeller] Could not find target item for type: $pendingItemType")
        }
        return targetItem
    }

    private fun removeItemsFromStorage(screenHandler: ScreenHandler, targetItem: net.minecraft.item.Item): Int {
        var itemsRemoved = 0
        for (slot in 0 until screenHandler.slots.size) {
            val stack = screenHandler.slots[slot].stack
            if (stack.item == targetItem && !stack.isEmpty) {
                removeItemFromSlot(screenHandler, slot)
                itemsRemoved++
                if (itemsRemoved >= 3) break
            }
        }
        return itemsRemoved
    }

    private fun removeItemFromSlot(screenHandler: ScreenHandler, slot: Int) {
        if (debug) {
            val stack = screenHandler.slots[slot].stack
            println("[AutoSeller] Removing ${stack.count}x ${pendingItemType} from storage slot $slot")
        }
        
        interaction.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.PICKUP, player)
        Thread.sleep(Random.nextLong(200, 500))
    }

    private fun logStorageCleanupResult(itemsRemoved: Int) {
        if (debug) {
            println("[AutoSeller] Removed $itemsRemoved stacks of $pendingItemType from storage")
        }
    }

    private fun scheduleMenuClose() {
        Thread {
            Thread.sleep(Random.nextLong(1000, 2000))
            player.closeHandledScreen()
        }.start()
    }
    
    private fun getEffectivePrice(defaultPrice: Int): Int {
        if (manualPrice.isNotEmpty()) {
            return try {
                val price = manualPrice.toInt()
                if (price > 0) price else defaultPrice
            } catch (e: NumberFormatException) {
                if (debug) {
                    println("[AutoSeller] Invalid manual price format: '$manualPrice', using default: $defaultPrice")
                }
                defaultPrice
            }
        }
        return defaultPrice
    }

    override fun disable() {
        isManagingAh = false
        waitingForResponse = false
        waitingStartTime = 0L
        storageFullDetected = false
        pendingItemType = null
    }
}
