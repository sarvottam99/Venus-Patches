package app.venus.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.PatchBuilder

private const val INSTANCE = "Lcom/facebook/react/runtime/ReactInstance;"
internal val discord = Compatibility(
    packageName = "com.discord",
    name = "Discord",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0x5865F2,
    targets = listOf(AppTarget(
        version = "348.10 - Stable",
        versionCode = 348010,
        minSdk = 26,
        description = "Pinned Discord 348.10 bundle; Android device validation still required."
    ))
)

/** Section headings in Morphe's patch list. */
internal const val PLUGINS = "Plugins"
internal const val PRIVACY = "Privacy"

/** Groups a patch under a heading in Morphe. A patcher without categories just shows a flat list. */
internal fun PatchBuilder<*>.section(name: String) {
    try { category(name) } catch (_: LinkageError) { }
}

/** The runtime version recorded next to the injected prelude, read from bootstrap.js itself. */
private fun runtimeRevision(source: String): String =
    Regex("""const revision\s*=\s*"([^"]+)"""").find(source)?.groupValues?.get(1)
        ?: throw PatchException("Venus runtime revision missing")

// Reset per run: Manager can reuse a loaded bundle for multiple selections.
private var pickerSelected = false
private var voiceSelected = false
private var copyBiosSelected = false
private var dashlessSelected = false
private var favouriteAnythingSelected = false
private var freeNitroSelected = false
private val additionalSelections = mutableSetOf<String>()
internal val discordBundleGuard = rawResourcePatch {
    execute { HbcPrivacy.verifyOriginal(get("assets/index.android.bundle")) }
}

private val runtimeAssets = rawResourcePatch {
    dependsOn(discordBundleGuard)
    execute {
        additionalSelections.clear()
        pickerSelected = false
        voiceSelected = false
        copyBiosSelected = false
        dashlessSelected = false
        favouriteAnythingSelected = false
        freeNitroSelected = false
    }
    finalize {
        val bootstrap = object {}.javaClass.getResourceAsStream("/venus/bootstrap.js")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw PatchException("Venus runtime asset missing from bundle")
        val asset = get("assets/venus/bootstrap.js", false)
        asset.parentFile.mkdirs()
        val selected = bootstrap.replace(
            "/*__FEATURES__*/",
            "{picker:$pickerSelected,voice:$voiceSelected,copyBios:$copyBiosSelected," +
                "dashless:$dashlessSelected,favouriteAnything:$favouriteAnythingSelected,freeNitro:$freeNitroSelected," +
                listOf("noTyping", "quickDelete", "noDelete", "jumpToTop", "hiddenChannels", "pastelize", "platformIndicators", "reviewDB", "readAll", "quests", "systemFont",
                    "systemEmoji", "hideGift", "hideQuestDock", "gestures", "hideCall", "autoTranslate")
                    .joinToString(",") { "$it:${it in additionalSelections}" } + "}"
        )
        asset.writeText(selected)
        // One main bundle load: the prelude runs inside the existing HBC98 global entry,
        // so RN cannot mark a separate bootstrap bundle ready or flush native calls early.
        val injected = HbcPrelude.inject(get("assets/index.android.bundle"), selected)
        get("assets/venus/injection.json", false).writeText(
            "{\"revision\":\"${runtimeRevision(bootstrap)}\",\"prefixSize\":${injected.prefixSize}," +
                "\"originalCodeSize\":${injected.originalCodeSize},\"codeOffset\":${injected.codeOffset}}"
        )
    }
}

private object BundleLoader : Fingerprint(
    definingClass = INSTANCE,
    name = "loadJSBundle",
    returnType = "V",
    parameters = listOf("Lcom/facebook/react/bridge/JSBundleLoader;")
)

internal val packagedDiscordBundle = bytecodePatch {
    dependsOn(discordBundleGuard)
    execute {
        val method = BundleLoader.method
        val owner = BundleLoader.originalClassDef
        if (owner.fields.none {
            it.name == "context" && it.type == "Lcom/facebook/react/runtime/BridgelessReactContext;"
        }) throw PatchException("Discord's React Native context ABI changed")
        val loader = Fingerprint(
            definingClass = "Lcom/facebook/react/bridge/JSBundleLoader;",
            name = "createAssetLoader",
            parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z")
        ).originalMethod
        if (loader.returnType != "Lcom/facebook/react/bridge/JSBundleLoader;")
            throw PatchException("Asset loader factory ABI changed")
        pinPackagedDiscordBundle(method)
    }
}


internal fun pinPackagedDiscordBundle(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    // Pin JS execution to the inspected packaged bundle, avoiding incompatible OTA cache bundles.
    // 348.10 has two scratch locals; p1 is deliberately replaced with an asset loader.
    if ((method.implementation?.registerCount ?: 0) - 2 < 2)
        throw PatchException("Bundle loader no longer has two safe scratch registers")
    method.addInstructions(0, """
        iget-object v0, p0, $INSTANCE->context:Lcom/facebook/react/runtime/BridgelessReactContext;
        const-string v1, "assets://index.android.bundle"
        const/4 p1, 0x0
        invoke-static {v0, v1, p1}, Lcom/facebook/react/bridge/JSBundleLoader;->createAssetLoader(Landroid/content/Context;Ljava/lang/String;Z)Lcom/facebook/react/bridge/JSBundleLoader;
        move-result-object p1
    """)
}

@Suppress("unused")
val venusSettings = bytecodePatch(
    name = "Venus settings",
    description = "Adds a Venus section to Discord's settings, where you can turn each plugin on or off. Needed by every plugin."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(runtimeAssets, packagedDiscordBundle, passkeyErrorHint)
}

@Suppress("unused")
val fileSizeOnPicker = rawResourcePatch(
    name = "File size on picker",
    description = "Shows file sizes on photos and videos when you attach them."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { pickerSelected = true }
}

@Suppress("unused")
val copyBios = rawResourcePatch(
    name = "CopyBios",
    description = "Lets you select and copy profile bios. Links still work."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { copyBiosSelected = true }
}

@Suppress("unused")
val dashless = rawResourcePatch(
    name = "Dashless",
    description = "Shows spaces instead of dashes in channel names. Only changes how they look."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { dashlessSelected = true }
}

@Suppress("unused")
val favouriteAnything = rawResourcePatch(
    name = "FavouriteAnything",
    description = "Lets you favourite any image or video from the media viewer."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { favouriteAnythingSelected = true }
}

@Suppress("unused")
val freeNitro = rawResourcePatch(
    name = "FreeNitro",
    description = "Sends emojis and stickers you can't use as image links. These are links, not real Nitro emojis or stickers."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { freeNitroSelected = true }
}

@Suppress("unused")
val customVoiceMessages = bytecodePatch(
    name = "Custom voice messages",
    description = "Sends an audio file as a real voice message, with Discord's own waveform. Needs Android 10 or newer. Turn it on in Venus settings."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    extendWith("extensions/voice.mpe")
    execute {
        voiceSelected = true
        voiceBridge(Fingerprint(
            definingClass = FILE_MODULE,
            name = "getSize",
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", PROMISE)
        ).method)
    }
}

internal const val FILE_MODULE = "Lcom/discord/file_manager/FileModule;"
internal const val PROMISE = "Lcom/facebook/react/bridge/Promise;"
/** Instructions [voiceBridge] adds before Discord's own getSize code, counting the nop its label lands on. */
internal const val VOICE_BRIDGE_SIZE = 11

/**
 * Existing TurboModule schemas cannot expose arbitrary new ReactMethod functions, so voice messages
 * dispatch a private URI prefix through getSize's Promise; normal size requests fall through.
 *
 * Each Discord method gets at most one Venus prefix. Morphe re-encodes an earlier prefix's branches
 * when a second one is inserted in front of it, and in 1.5.0 that left getSize's branches pointing
 * into the middle of an instruction: Android rejected FileModule and Discord froze on its icon.
 */
internal fun voiceBridge(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    if ((method.implementation?.registerCount ?: 0) - 3 < 2)
        throw PatchException("File bridge no longer has two safe scratch registers")
    requireUnpatched(method, "venus-")
    method.addInstructions(0, """
        const-string v0, "venus-voice-v1:"
        invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :venus_original_size
        invoke-virtual {p0}, Lcom/facebook/react/bridge/ReactContextBaseJavaModule;->getReactApplicationContext()Lcom/facebook/react/bridge/ReactApplicationContext;
        move-result-object v0
        invoke-static {p1, p2, v0}, Lapp/venus/extension/VoiceProcessor;->dispatch(Ljava/lang/String;${PROMISE}Landroid/content/Context;)Z
        move-result v1
        if-eqz v1, :venus_original_size
        return-void
        :venus_original_size
        nop
    """)
}

/** Fails the patch, instead of freezing Discord, if another Venus prefix already sits in this method. */
internal fun requireUnpatched(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, marker: String) {
    val strings = method.implementation?.instructions?.mapNotNull {
        ((it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference
            as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string
    } ?: emptyList()
    val calls = method.implementation?.instructions?.mapNotNull {
        ((it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference
            as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)?.definingClass
    } ?: emptyList()
    if (strings.any { it.startsWith(marker) } || calls.any { it.startsWith("Lapp/venus/") })
        throw PatchException("${method.definingClass}->${method.name} already has a Venus guard; each method may only get one")
}

// Each feature is its own selectable patch, bundled offline in the Venus runtime.
private fun bundledPlugin(key: String, title: String, summary: String) = rawResourcePatch(
    name = title,
    description = summary
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute { additionalSelections += key }
}

@Suppress("unused")
val noTyping = bundledPlugin("noTyping", "No typing", "Hides \"typing...\" from others when you type. You still see when they type.")

@Suppress("unused")
val quickDelete = bundledPlugin("quickDelete", "QuickDelete", "Skips the \"are you sure?\" when deleting messages or link previews. Turn it on in Venus settings.")

@Suppress("unused")
val noDelete = bundledPlugin("noDelete", "NoDelete", "Keeps deleted messages visible, outlined in red, and shows what edited messages said before. You can save both for good and choose how many to keep. Turn it on in Venus settings.")

@Suppress("unused")
val jumpToTop = bundledPlugin("jumpToTop", "JumpToTop", "Adds a button to jump to the first message in a chat, channel or thread.")

@Suppress("unused")
val hiddenChannels = bundledPlugin("hiddenChannels", "Hidden Channels", "Shows channels you can't open, with a lock and when they were created and last used. It can't show their messages. Turn it on in Venus settings.")

@Suppress("unused")
val pastelize = bundledPlugin("pastelize", "Pastelize", "Gives names and mentions without a role color a soft pastel color, picked from each person's user ID so it never changes.")

@Suppress("unused")
val platformIndicators = bundledPlugin("platformIndicators", "PlatformIndicators", "Shows whether people are on desktop, mobile, web, console or VR, on profiles, in lists and in DMs.")

@Suppress("unused")
val reviewDB = bundledPlugin("reviewDB", "ReviewDB", "Read and write reviews of users and servers, using the community ReviewDB service (manti.vendicated.dev). Turn it on in Venus settings.")

@Suppress("unused")
val readAll = bundledPlugin("readAll", "Read All", "Adds a Read all button to the server list, under the Direct Messages button. Choose whether it clears servers, DMs or both.")

internal const val FONT_MANAGER = "Lcom/facebook/react/common/assets/ReactFontManager;"
internal const val FONT_STYLE = "Lcom/facebook/react/common/assets/ReactFontManager\$TypefaceStyle;"
internal const val SYSTEM_FONT = "Lapp/venus/font/SystemFont;"
/** Instructions [useSystemFont] adds before Discord's own font lookup, counting the nop its label lands on. */
internal const val SYSTEM_FONT_GUARD_SIZE = 9

/**
 * Every Discord text, React Native or native chat, gets its font from this one ReactFontManager method.
 * When Force system font is on, Discord's own families come back as the phone's font; everything else,
 * and every lookup while it's off, runs Discord's code untouched. 6 registers, 4 of them parameters: v0-v1 are free.
 */
internal fun useSystemFont(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val implementation = method.implementation ?: throw PatchException("Font lookup has no code")
    if (method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", FONT_STYLE, "Landroid/content/res/AssetManager;") ||
        method.returnType != "Landroid/graphics/Typeface;" || implementation.registerCount - 4 < 2)
        throw PatchException("ReactFontManager.getTypeface ABI changed")
    method.addInstructions(0, """
        sget-boolean v0, $SYSTEM_FONT->enabled:Z
        if-eqz v0, :venus_discord_font
        invoke-virtual {p2}, $FONT_STYLE->getNearestStyle()I
        move-result v0
        invoke-static {p1, v0}, $SYSTEM_FONT->typeface(Ljava/lang/String;I)Landroid/graphics/Typeface;
        move-result-object v0
        if-eqz v0, :venus_discord_font
        return-object v0
        :venus_discord_font
        nop
    """)
}

/** Reads the saved Force system font setting once, right after Application.onCreate's super call. */
internal fun readSystemFontSetting(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val first = method.implementation?.instructions?.firstOrNull()
    val target = (first as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference?.toString()
    if (first?.opcode != com.android.tools.smali.dexlib2.Opcode.INVOKE_SUPER || target != "Landroid/app/Application;->onCreate()V")
        throw PatchException("MainApplication.onCreate no longer starts with super.onCreate()")
    method.addInstructions(1, "invoke-static {p0}, $SYSTEM_FONT->init(Landroid/content/Context;)V")
}

/** Instructions [systemFontBridge] adds before Discord's own fileExists code, counting the nop its label lands on. */
internal const val SYSTEM_FONT_BRIDGE_SIZE = 9

/**
 * Lets Venus settings reopen Discord after the font changes. Uses fileExists, not getSize: voice messages
 * already guard getSize, and a second prefix in one method breaks the first one's branches (see [voiceBridge]).
 * Venus only ever asks fileExists about absolute paths, so the private prefix can't match a real request.
 */
internal fun systemFontBridge(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    if (method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", PROMISE) || method.returnType != "V" ||
        (method.implementation?.registerCount ?: 0) - 3 < 2)
        throw PatchException("FileModule.fileExists ABI changed")
    requireUnpatched(method, "venus-")
    method.addInstructions(0, """
        const-string v0, "venus-font-v1:"
        invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :venus_font_exists
        invoke-static {p1, p2}, $SYSTEM_FONT->dispatch(Ljava/lang/String;$PROMISE)Z
        move-result v1
        if-eqz v1, :venus_font_exists
        return-void
        :venus_font_exists
        nop
    """)
}

@Suppress("unused")
val forceSystemFont = bytecodePatch(
    name = "Force system font",
    description = "Lets Discord use your phone's font instead of gg sans. Turn it on in Venus settings. Needs Android 9 or newer."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    extendWith("extensions/font.mpe")
    execute {
        additionalSelections += "systemFont"
        useSystemFont(Fingerprint(
            definingClass = FONT_MANAGER,
            name = "getTypeface",
            returnType = "Landroid/graphics/Typeface;",
            parameters = listOf("Ljava/lang/String;", FONT_STYLE, "Landroid/content/res/AssetManager;")
        ).method)
        systemFontBridge(Fingerprint(
            definingClass = FILE_MODULE,
            name = "fileExists",
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", PROMISE)
        ).method)
        readSystemFontSetting(Fingerprint(
            definingClass = "Lcom/discord/MainApplication;",
            name = "onCreate",
            returnType = "V",
            parameters = listOf()
        ).method)
    }
}

internal const val SURROGATES = "Lcom/discord/emoji/Surrogates;"
internal const val SYSTEM_EMOJI = "Lapp/venus/emoji/SystemEmoji;"
/** Instructions [useSystemEmoji] adds before Discord's own emoji picture lookup, counting the nop its label lands on. */
internal const val SYSTEM_EMOJI_GUARD_SIZE = 7

/**
 * Native chat, reactions and the emoji picker all get a Unicode emoji's picture from this one method,
 * which returns Discord's bundled asset:///emoji-….png. When Force system emojis is on, it returns the
 * phone's drawing instead; an emoji the phone can't draw, and every lookup while it's off, runs Discord's
 * code untouched. 3 registers, 1 of them the parameter: v0-v1 are free.
 */
internal fun useSystemEmoji(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val implementation = method.implementation ?: throw PatchException("Emoji picture lookup has no code")
    if (method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;") || method.returnType != "Ljava/lang/String;" ||
        implementation.registerCount - 1 < 2)
        throw PatchException("Surrogates.toAssetUrl ABI changed")
    requireUnpatched(method, "venus-")
    method.addInstructions(0, """
        sget-boolean v0, $SYSTEM_EMOJI->enabled:Z
        if-eqz v0, :venus_discord_emoji
        invoke-static {p0}, $SYSTEM_EMOJI->url(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :venus_discord_emoji
        return-object v0
        :venus_discord_emoji
        nop
    """)
}

/** Instructions [systemEmojiBridge] adds before Discord's own getVideoDimensions code, counting the nop its label lands on. */
internal const val SYSTEM_EMOJI_BRIDGE_SIZE = 7

/**
 * Lets Venus settings reopen Discord after the emoji setting changes. getVideoDimensions only ever rejects on
 * Android ("only supported on iOS") and Discord's JS never calls it, so a request with the private prefix
 * goes to Venus and anything else still gets Discord's own answer. No other Venus guard uses this method.
 */
internal fun systemEmojiBridge(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    if (method.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", PROMISE) || method.returnType != "V" ||
        (method.implementation?.registerCount ?: 0) - 3 < 1)
        throw PatchException("FileModule.getVideoDimensions ABI changed")
    requireUnpatched(method, "venus-")
    method.addInstructions(0, """
        const-string v0, "venus-emoji-v1:"
        invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :venus_emoji_dimensions
        invoke-static {p1, p2}, $SYSTEM_EMOJI->dispatch(Ljava/lang/String;$PROMISE)V
        return-void
        :venus_emoji_dimensions
        nop
    """)
}

/** Reads the saved Force system emojis setting once, right after Application.onCreate's super call. */
internal fun readSystemEmojiSetting(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val first = method.implementation?.instructions?.firstOrNull()
    val target = (first as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference?.toString()
    if (first?.opcode != com.android.tools.smali.dexlib2.Opcode.INVOKE_SUPER || target != "Landroid/app/Application;->onCreate()V")
        throw PatchException("MainApplication.onCreate no longer starts with super.onCreate()")
    method.addInstructions(1, "invoke-static {p0}, $SYSTEM_EMOJI->init(Landroid/content/Context;)V")
}

@Suppress("unused")
val forceSystemEmojis = bytecodePatch(
    name = "Force system emojis",
    description = "Shows your phone's emojis instead of Discord's, in chat, reactions and the emoji picker. Emojis your phone doesn't have stay Discord's."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    extendWith("extensions/emoji.mpe")
    execute {
        additionalSelections += "systemEmoji"
        useSystemEmoji(Fingerprint(
            definingClass = SURROGATES,
            name = "toAssetUrl-impl",
            returnType = "Ljava/lang/String;",
            parameters = listOf("Ljava/lang/String;")
        ).method)
        systemEmojiBridge(Fingerprint(
            definingClass = FILE_MODULE,
            name = "getVideoDimensions",
            returnType = "V",
            parameters = listOf("Ljava/lang/String;", PROMISE)
        ).method)
        readSystemEmojiSetting(Fingerprint(
            definingClass = "Lcom/discord/MainApplication;",
            name = "onCreate",
            returnType = "V",
            parameters = listOf()
        ).method)
    }
}

@Suppress("unused")
val hideGiftButton = bundledPlugin("hideGift", "Hide gift button",
    "Removes the Nitro gift button from the message bar.")

@Suppress("unused")
val messageGestures = bundledPlugin("gestures", "Message tap gestures",
    "Double-tap a message to reply, or to edit your own. Tap your own message three times to delete it. Based on Geastures+ by kmmiio99o. Turn it on in Venus settings.")

@Suppress("unused")
val autoTranslate = bundledPlugin("autoTranslate", "Auto-translate",
    "Automatically translates new messages in configured channels using fixed FROM and TO languages.")

@Suppress("unused")
val hideCallButton = bundledPlugin("hideCall", "Hide call button",
    "Removes the Call button from people's profiles. The Message button stretches across to fill the space.")

@Suppress("unused")
val hideQuestDock = bundledPlugin("hideQuestDock", "Hide Quests promotion",
    "Hides the Quests promotion above your profile bar, under the server list.")

internal const val USER_AGENT_INTERCEPTOR = "Lcom/discord/client_info/ClientUserAgent\$DiscordUserAgentInterceptor;"
/** The start of the Windows desktop User-Agent that Quest Completer sends Play and Activity heartbeats with. */
internal const val QUEST_DESKTOP_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/"
/** Instructions [keepDesktopQuestAgent] adds before Discord's own code, counting the nop its label lands on. */
internal const val QUEST_AGENT_GUARD_SIZE = 14

/**
 * Discord's OkHttp interceptor replaces every request's User-Agent with Discord-Android/348010;RNA.
 * A request that already says it's the Windows desktop app passes through untouched; everything else is unchanged.
 * Locals v0-v2 are free: the interceptor has 5 registers and 2 of them are parameters.
 */
internal fun keepDesktopQuestAgent(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val implementation = method.implementation ?: throw PatchException("User-Agent interceptor has no code")
    if (method.parameterTypes.map { it.toString() } != listOf("Lokhttp3/Interceptor\$Chain;") ||
        method.returnType != "Lokhttp3/Response;" || implementation.registerCount - 2 < 3)
        throw PatchException("Discord's User-Agent interceptor ABI changed")
    method.addInstructions(0, """
        invoke-interface {p1}, Lokhttp3/Interceptor${'$'}Chain;->i()Lokhttp3/Request;
        move-result-object v0
        const-string v1, "User-Agent"
        invoke-virtual {v0, v1}, Lokhttp3/Request;->a(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v1
        if-eqz v1, :venus_android_agent
        const-string v2, "$QUEST_DESKTOP_AGENT"
        invoke-virtual {v1, v2}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v1
        if-eqz v1, :venus_android_agent
        invoke-interface {p1, v0}, Lokhttp3/Interceptor${'$'}Chain;->a(Lokhttp3/Request;)Lokhttp3/Response;
        move-result-object v0
        return-object v0
        :venus_android_agent
        nop
    """)
}

@Suppress("unused")
val questCompleter = bytecodePatch(
    name = "Quest Completer",
    description = "Completes video, Play and Activity Quests in the background when Discord is open, with no screen or tap. You still claim rewards yourself. Discord may pause Quests on accounts that do this."
) {
    compatibleWith(discord)
    section(PLUGINS)
    dependsOn(venusSettings)
    execute {
        additionalSelections += "quests"
        // Play and Activity time only counts when it comes from the desktop app.
        keepDesktopQuestAgent(Fingerprint(
            definingClass = USER_AGENT_INTERCEPTOR,
            name = "intercept",
            returnType = "Lokhttp3/Response;",
            parameters = listOf("Lokhttp3/Interceptor\$Chain;")
        ).method)
    }
}
