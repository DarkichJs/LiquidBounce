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

import net.ccbluex.liquidbounce.event.events.ChatReceiveEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import kotlin.random.Random

/**
 * ClanInvest module
 * 
 * Automatically checks balance and invests in clan if balance is above threshold
 */
object ModuleClanInvest : ClientModule("ClanInvest", Category.MISC) {

    // Settings
    private val checkDelay by intRange("CheckDelay", 30000..60000, 10000..300000, "ms")
    private val minBalance by text("MinBalance", "1000000")
    private val investAmount by text("InvestAmount", "500000")
    private val debug by boolean("Debug", false)
    
    // State management
    private var lastCheckTime = 0L
    private var waitingForBalance = false
    private var currentBalance = 0.0
    
    val repeatable = tickHandler {
        val currentTime = System.currentTimeMillis()
        
        // Don't do anything if we're waiting for balance response
        if (waitingForBalance) {
            return@tickHandler
        }
        
        // Check balance periodically
        if (currentTime - lastCheckTime > checkDelay.random()) {
            checkBalance()
            lastCheckTime = currentTime
        }
    }
    
    private fun checkBalance() {
        if (debug) {
            println("[ClanInvest] Checking balance...")
        }
        
        network.sendCommand("balance")
        waitingForBalance = true
    }
    
    private fun processBalance(balance: Double) {
        currentBalance = balance
        val minBalanceValue = parseAmount(minBalance)
        
        if (debug) {
            println("[ClanInvest] Current balance: $$balance, Min required: $$minBalanceValue")
        }
        
        if (balance > minBalanceValue) {
            val investAmountValue = parseAmount(investAmount)
            if (investAmountValue > 0 && balance >= investAmountValue) {
                investInClan(investAmountValue)
            } else if (investAmountValue > 0 && balance < investAmountValue) {
                if (debug) {
                    println("[ClanInvest] Not enough money to invest: have $$balance, need $$investAmountValue")
                }
            } else if (debug) {
                println("[ClanInvest] Invalid invest amount: $investAmount")
            }
        } else if (debug) {
            println("[ClanInvest] Balance too low for investment")
        }
    }
    
    private fun investInClan(amount: Double) {
        val amountInt = amount.toInt()
        val command = "clan invest $amountInt"
        
        if (debug) {
            println("[ClanInvest] Investing $$amountInt in clan")
        }
        
        // Add small delay before investing
        Thread {
            Thread.sleep(Random.nextLong(1000, 3000))
            network.sendCommand(command)
        }.start()
    }
    
    private fun parseAmount(amountStr: String): Double {
        return try {
            // Remove any formatting like commas
            val cleanAmount = amountStr.replace(",", "").replace("$", "")
            cleanAmount.toDouble()
        } catch (e: NumberFormatException) {
            if (debug) {
                println("[ClanInvest] Failed to parse amount: $amountStr")
            }
            0.0
        }
    }
    
    @Suppress("unused")
    private val chatHandler = handler<ChatReceiveEvent> { event ->
        if (!enabled) return@handler
        
        val message = event.message
        
        // Check for balance response: "[$] Ваш баланс: $302,628.39"
        if (message.contains("[$]") && message.contains("Ваш баланс:")) {
            waitingForBalance = false
            
            // Extract balance amount
            val balanceRegex = """Ваш баланс:\s*\$?([\d,]+(?:\.\d{2})?)""".toRegex()
            val match = balanceRegex.find(message)
            
            if (match != null) {
                val balanceStr = match.groupValues[1]
                val balance = parseAmount(balanceStr)
                if (balance > 0) {
                    processBalance(balance)
                } else if (debug) {
                    println("[ClanInvest] Failed to parse balance from: $message")
                }
            } else if (debug) {
                println("[ClanInvest] Balance format not recognized: $message")
            }
        }
        
        // Check for clan invest success/failure messages
        else if (message.contains("clan") && message.contains("invest")) {
            if (debug) {
                println("[ClanInvest] Clan invest response: $message")
            }
        }
    }
    
    override fun disable() {
        waitingForBalance = false
    }
}
