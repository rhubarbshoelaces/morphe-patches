package com.rubenmayayo.reddit

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import org.w3c.dom.Element
import java.util.logging.Logger

val COMPATIBILITY_BOOST = Compatibility(
    name = "Boost for Reddit",
    packageName = "com.rubenmayayo.reddit"
)

/** The subreddit overflow menu, one file per "main toolbar action" setting. */
private val SUBREDDIT_MENUS = listOf(
    "res/menu/menu_subreddit.xml",
    "res/menu/menu_subreddit_refresh.xml",
    "res/menu/menu_subreddit_search.xml"
)

/** Decoded resources spell ids as `@id/...`, the bundled XML as `@+id/...`. */
private const val RANDOM_NSFW_ITEM = "action_go_to_random_nsfw"

/** Sits between "Filter" (40) and "Clear read" (50). */
private const val RANDOM_NSFW_ORDER = "45"

/**
 * Adds a second Random NSFW entry to the overflow menu, directly above "Clear read".
 * The original stays inside the "Go to" submenu. Both carry the same id, so
 * onCreateOptionsMenu and the existing click handler serve either one.
 */
private val randomNsfwMenuPatch = resourcePatch(
    description = "Adds a Random NSFW shortcut to the overflow menu, above Clear read.",
) {
    execute {
        SUBREDDIT_MENUS.forEach { menuFile ->
            document(menuFile).use { document ->
                val items = document.getElementsByTagName("item")
                val item = (0 until items.length)
                    .map { items.item(it) as Element }
                    .firstOrNull { it.getAttribute("android:id").substringAfterLast('/') == RANDOM_NSFW_ITEM }
                    ?: error("$menuFile has no $RANDOM_NSFW_ITEM item")

                val shortcut = item.cloneNode(true) as Element
                shortcut.setAttribute("android:orderInCategory", RANDOM_NSFW_ORDER)
                // onPrepareOptionsMenu reveals the item it finds by id, which is the
                // one still in the submenu, so this copy has to be visible already.
                shortcut.setAttribute("android:visible", "true")
                document.documentElement.appendChild(shortcut)
            }
        }
    }
}

val fixRandomSubredditPatch = bytecodePatch(
    name = "Fix Random Subreddit Button",
    description = "Reroutes all Random buttons to pick from a custom pre-loaded list of thousands of subreddits.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BOOST)
    dependsOn(randomNsfwMenuPatch)

    extendWith("extension.mpe")

    execute {
        val logger = Logger.getLogger(this::class.java.name)

        // 1. Drawer Menu Fingerprint
        val drawerFingerprint = Fingerprint(
            returnType = "V",
            parameters = listOf("Lva/b;"),
            strings = listOf("random", "randnsfw")
        )

        // 2. Top Action Bar Menu Fingerprint
        val topMenuFingerprint = Fingerprint(
            returnType = "Z",
            parameters = listOf("Landroid/view/MenuItem;"),
            strings = listOf("random", "randnsfw")
        )

        // 3. Search Bar Suggestions Fingerprint (Q1)
        val searchSuggestionsFingerprint = Fingerprint(
            name = "Q1",
            returnType = "V",
            strings = listOf("random", "randnsfw")
        )

        listOf(drawerFingerprint, topMenuFingerprint, searchSuggestionsFingerprint).forEach { fingerprint ->
            val method = fingerprint.methodOrNull

            if (method == null) {
                logger.warning("Skipped: Could not match method for fingerprint: ${fingerprint.name ?: fingerprint.returnType}")
                return@forEach
            }

            // --- 1. Fix "randnsfw" ---
            val nsfwIndex = method.instructions.indexOfFirst {
                it is ReferenceInstruction && it.reference is StringReference && (it.reference as StringReference).string == "randnsfw"
            }

            if (nsfwIndex != -1) {
                val safeRegister = (method.instructions[nsfwIndex] as OneRegisterInstruction).registerA

                var z2Index = -1
                for (i in nsfwIndex until method.instructions.size) {
                    val inst = method.instructions[i]
                    if (inst.opcode.name == "invoke-virtual" && inst is ReferenceInstruction && inst.reference.toString().contains("->z2(")) {
                        z2Index = i
                        break
                    }
                }

                if (z2Index != -1) {
                    method.replaceInstruction(
                        z2Index,
                        "invoke-static {}, Lrhubarbshoelaces/patches/boost/extension/RandomSubredditProvider;->getRandomNsfwSubreddit()Ljava/lang/String;"
                    )
                    method.addInstructions(
                        z2Index + 1,
                        """
                        move-result-object v$safeRegister
                        invoke-virtual {p0, v$safeRegister}, Lcom/rubenmayayo/reddit/ui/activities/e;->R2(Ljava/lang/String;)V
                        """.trimIndent()
                    )
                } else {
                    method.addInstructions(
                        nsfwIndex + 1,
                        """
                        invoke-static {}, Lrhubarbshoelaces/patches/boost/extension/RandomSubredditProvider;->getRandomNsfwSubreddit()Ljava/lang/String;
                        move-result-object v$safeRegister
                        """.trimIndent()
                    )
                }
            }

            // --- 2. Fix "random" ---
            val randomIndex = method.instructions.indexOfFirst {
                it is ReferenceInstruction && it.reference is StringReference && (it.reference as StringReference).string == "random"
            }

            if (randomIndex != -1) {
                val safeRegister = (method.instructions[randomIndex] as OneRegisterInstruction).registerA

                var z2Index = -1
                for (i in randomIndex until method.instructions.size) {
                    val inst = method.instructions[i]
                    if (inst.opcode.name == "invoke-virtual" && inst is ReferenceInstruction && inst.reference.toString().contains("->z2(")) {
                        z2Index = i
                        break
                    }
                }

                if (z2Index != -1) {
                    method.replaceInstruction(
                        z2Index,
                        "invoke-static {}, Lrhubarbshoelaces/patches/boost/extension/RandomSubredditProvider;->getRandomSubreddit()Ljava/lang/String;"
                    )
                    method.addInstructions(
                        z2Index + 1,
                        """
                        move-result-object v$safeRegister
                        invoke-virtual {p0, v$safeRegister}, Lcom/rubenmayayo/reddit/ui/activities/e;->R2(Ljava/lang/String;)V
                        """.trimIndent()
                    )
                } else {
                    method.addInstructions(
                        randomIndex + 1,
                        """
                        invoke-static {}, Lrhubarbshoelaces/patches/boost/extension/RandomSubredditProvider;->getRandomSubreddit()Ljava/lang/String;
                        move-result-object v$safeRegister
                        """.trimIndent()
                    )
                }
            }

            // --- 3. Combine the "Go to random..." string in Q1 ---
            if (fingerprint.name == "Q1") {
                val getStringIndices = method.instructions.mapIndexedNotNull { index, inst ->
                    if (inst.opcode.name == "invoke-virtual" && inst is ReferenceInstruction && inst.reference.toString().contains("->getString(I)Ljava/lang/String;")) index else null
                }

                getStringIndices.reversed().forEach { idx ->
                    val inst = method.instructions[idx] as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
                    val resReg = inst.registerD

                    method.addInstruction(
                        idx,
                        "const v$resReg, 0x7f1305e6"
                    )

                    // The original move-result-object is now at idx + 2
                    val moveResultIdx = idx + 2
                    val nextInst = method.instructions.getOrNull(moveResultIdx)
                    if (nextInst != null && nextInst.opcode.name == "move-result-object") {
                        val destReg = (nextInst as OneRegisterInstruction).registerA
                        method.addInstructions(
                            moveResultIdx + 1,
                            """
                            new-instance v6, Ljava/lang/StringBuilder;
                            invoke-direct {v6}, Ljava/lang/StringBuilder;-><init>()V
                            invoke-virtual {v6, v$destReg}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                            const-string v$destReg, " random..."
                            invoke-virtual {v6, v$destReg}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                            invoke-virtual {v6}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                            move-result-object v$destReg
                            """.trimIndent()
                        )
                    }
                }
            }
        }
    }
}