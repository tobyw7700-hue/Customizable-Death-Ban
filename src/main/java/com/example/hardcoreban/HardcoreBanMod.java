package com.example.hardcoreban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class HardcoreBanMod implements ModInitializer {

    public static final String MODID = "hardcoreban";

    // Item
    public static final Identifier REVIVE_ID = Identifier.of(MODID, "revive_totem");
    public static Item REVIVE_ITEM;

    // Server ref
    private static volatile MinecraftServer SERVER;

    // =========================================================
    // Config + storage files (ALL moved to config/HardcoreBan/)
    // =========================================================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path MOD_CONFIG_DIR;
    private static Path CONFIG_PATH;          // config.json
    private static Path BANNED_PATH;          // banned.json (name + untilMs)
    private static Path PENDING_POP_PATH;     // pending_revive_pop.json

    // config fields
    private static volatile String banDuration = "36h";
    private static volatile boolean DEBUG = false;

    // =========================================================
    // Banned store: name -> untilEpochMs
    // =========================================================

    private static final Map<String, Long> BANNED_UNTIL = new LinkedHashMap<>();

    // revived players waiting for “first join” totem pop
    private static final Set<String> PENDING_TOTEM_POP = new LinkedHashSet<>();

    // ban queue: we still queue commands so death hook stays lightweight
    private static final Queue<QueuedBan> BAN_QUEUE = new ConcurrentLinkedQueue<>();
    private record QueuedBan(String playerName, String duration, String reason) {}

    // op cache (reads ops.json directly)
    private static final Path OPS_JSON = Path.of("ops.json");
    private static volatile long opsCacheLastLoadMs = 0L;
    private static final Set<UUID> OPS_CACHE = new HashSet<>();
    private static final Type OPS_LIST_TYPE = new TypeToken<List<OpsEntry>>() {}.getType();

    private static final class OpsEntry {
        String uuid;
        String name;
        int level;
        boolean bypassesPlayerLimit;
    }

    // misc
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Type CONFIG_TYPE = new TypeToken<ConfigFile>() {}.getType();
    private static final Type BANNED_MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private static final Type STRING_SET_TYPE = new TypeToken<Set<String>>() {}.getType();

    private static final class ConfigFile {
        String banDuration = "36h";
        boolean debug = false;
    }

    // prune throttle
    private static volatile long lastPruneMs = 0L;

    @Override
    public void onInitialize() {
        // -------------------------
        // Setup config paths
        // -------------------------
        MOD_CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("HardcoreBan");
        CONFIG_PATH = MOD_CONFIG_DIR.resolve("config.json");
        BANNED_PATH = MOD_CONFIG_DIR.resolve("banned.json");
        PENDING_POP_PATH = MOD_CONFIG_DIR.resolve("pending_revive_pop.json");

        ensureConfigDir();
        loadConfig();              // loads banDuration + debug
        loadBannedStore();         // loads banned list with expiries
        loadPendingTotemPop();     // loads pending pop set

        // -------------------------
        // Register revive item (NO reflection, no randomness)
        // -------------------------
        REVIVE_ITEM = registerItemWithKey(REVIVE_ID, new Item.Settings().maxCount(1));

        // -------------------------
        // RIGHT CLICK HANDLER (no custom item class)
        // -------------------------
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);

            if (stack.isEmpty() || !stack.isOf(REVIVE_ITEM)) {
                return ActionResult.PASS;
            }

            if (world.isClient()) {
                return ActionResult.PASS;
            }

            if (!(player instanceof ServerPlayerEntity sp)) {
                return ActionResult.PASS;
            }

            // prune first, so GUI never shows expired bans
            pruneExpiredAndOnline(serverOrNull(), "rightclick");

            if (BANNED_UNTIL.isEmpty()) {
                sp.sendMessage(Text.literal("[HardcoreBan] No one is currently banned."), false);
                log("Right-click revive totem by=" + sp.getName().getString() + " but banned list empty");
                return ActionResult.PASS;
            }

            log("Right-click revive totem by=" + sp.getName().getString() + " opening GUI");
            openReviveGui(sp);
            return ActionResult.SUCCESS;
        });

        // -------------------------
        // When a player joins: if revived earlier, play totem pop ONCE
        // Also: if they appear in revive list, remove them (they're online => not banned)
        // -------------------------
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity sp = handler.getPlayer();
            if (sp == null) return;

            String name = sp.getName().getString();

            // If they're online, they aren't banned anymore; remove stale entry.
            boolean removed = false;
            if (BANNED_UNTIL.remove(name) != null) {
                removed = true;
                saveBannedStore();
                log("Join cleanup removed stale banned entry for online player=" + name);
            }

            if (removed) {
                // no-op, but keeps it obvious in logs
            }

            if (PENDING_TOTEM_POP.remove(name)) {
                log("First-join totem pop for revived player=" + name);
                triggerTotemPop(sp);
                savePendingTotemPop();
            }
        });

        // -------------------------
        // Server lifecycle
        // -------------------------
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            SERVER = server;
            pruneExpiredAndOnline(server, "server_start");
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVER = null);

        // -------------------------
        // Tick: run queued bans + periodic prune
        // -------------------------
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            runBanQueue(server);

            // prune occasionally (every ~30s)
            long now = System.currentTimeMillis();
            if (now - lastPruneMs > 30_000L) {
                lastPruneMs = now;
                pruneExpiredAndOnline(server, "tick");
            }
        });

        // -------------------------
        // Commands
        // -------------------------
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerHbCommand(dispatcher);
            registerReviveCommands(dispatcher);
        });
    }

    // =========================================================
    // Item registration helper (correct 1.21.x approach)
    // =========================================================

    private static Item registerItemWithKey(Identifier id, Item.Settings settings) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, id);

        // This is the correct modern way (no reflection, no randomness).
        Item item = new Item(settings.registryKey(key));

        // Registry.register overload accepts RegistryKey in modern versions.
        Registry.register(Registries.ITEM, key, item);
        return item;
    }

    // =========================================================
    // Commands
    // =========================================================

    private static void registerHbCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("hb")
                        .requires(HardcoreBanMod::isOpOrConsole)
                        // /hb (no args) -> show help + current
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                    "§6[HardcoreBan] §7Current ban duration: §e" + banDuration
                                            + "§7  | Usage: §e/hb <duration>§7 (e.g. 2d, 36h, 12h30m)"
                            ), false);
                            return 1;
                        })
                        .then(argument("duration", word())
                                .executes(ctx -> {
                                    String duration = getString(ctx, "duration");

                                    if (!duration.matches("(?i)\\d+[smhdy](\\d+[smhdy])*")) {
                                        ctx.getSource().sendError(Text.literal(
                                                "[HardcoreBan] Invalid duration. Examples: 15s, 3m, 24h, 7d, 5y3d9h3m8s"
                                        ));
                                        return 0;
                                    }

                                    banDuration = duration;
                                    saveConfig();

                                    ctx.getSource().sendFeedback(
                                            () -> Text.literal("[HardcoreBan] Ban duration set to " + banDuration),
                                            false
                                    );
                                    return 1;
                                }))
        );
    }

    private static void registerReviveCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        // OP ONLY (item is for everyone)
        dispatcher.register(
                literal("revive")
                        .requires(HardcoreBanMod::isOpOrConsole)
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(Text.literal("[HardcoreBan] Must be a player."));
                                return 0;
                            }
                            openReviveGui(player);
                            return 1;
                        })
        );

        dispatcher.register(
                literal("revivegui")
                        .requires(HardcoreBanMod::isOpOrConsole)
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(Text.literal("[HardcoreBan] Must be a player."));
                                return 0;
                            }
                            openReviveGui(player);
                            return 1;
                        })
        );

        dispatcher.register(
                literal("hardcoreban")
                        .requires(HardcoreBanMod::isOpOrConsole)
                        .then(literal("help").executes(ctx -> {
                            sendHelp(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("addbanned")
                                .then(argument("name", word()).executes(ctx -> {
                                    String n = getString(ctx, "name");
                                    if (n != null && !n.isBlank()) {
                                        // for testing: default 24h
                                        long until = System.currentTimeMillis() + (24L * 60L * 60L * 1000L);
                                        BANNED_UNTIL.put(n, until);
                                        saveBannedStore();
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("[HardcoreBan] Added: " + n), false);
                                    return 1;
                                })))
                        .then(literal("removebanned")
                                .then(argument("name", word()).executes(ctx -> {
                                    String n = getString(ctx, "name");
                                    if (n != null && !n.isBlank()) {
                                        BANNED_UNTIL.remove(n);
                                        saveBannedStore();
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("[HardcoreBan] Removed: " + n), false);
                                    return 1;
                                })))
                        .then(literal("debug")
                                .then(literal("on").executes(ctx -> {
                                    DEBUG = true;
                                    saveConfig();
                                    ctx.getSource().sendFeedback(() -> Text.literal("[HardcoreBan] Debug ON"), false);
                                    log("Debug turned ON by " + safeName(ctx.getSource()));
                                    return 1;
                                }))
                                .then(literal("off").executes(ctx -> {
                                    DEBUG = false;
                                    saveConfig();
                                    ctx.getSource().sendFeedback(() -> Text.literal("[HardcoreBan] Debug OFF"), false);
                                    log("Debug turned OFF by " + safeName(ctx.getSource()));
                                    return 1;
                                })))
        );
    }

    private static void sendHelp(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("§6[HardcoreBan] Commands:"), false);

        src.sendFeedback(() -> Text.literal("§e/hb §7- Shows current ban duration + usage."), false);
        src.sendFeedback(() -> Text.literal("§e/hb <duration>§7 - Sets tempban duration used on death."), false);
        src.sendFeedback(() -> Text.literal("§7   Example: §f/hb 2d §7or §f/hb 12h30m"), false);

        src.sendFeedback(() -> Text.literal("§e/revive§7 - Opens the revive GUI (OP only)."), false);
        src.sendFeedback(() -> Text.literal("§e/revivegui§7 - Same as /revive (OP only)."), false);

        src.sendFeedback(() -> Text.literal("§e/hardcoreban addbanned <name>§7 - Adds a name to the revive GUI list (testing)."), false);
        src.sendFeedback(() -> Text.literal("§e/hardcoreban removebanned <name>§7 - Removes a name from the revive GUI list."), false);

        src.sendFeedback(() -> Text.literal("§e/hardcoreban debug on|off§7 - Turns HardcoreBan debug logs on/off."), false);

        src.sendFeedback(() -> Text.literal("§6[HardcoreBan] Item usage:"), false);
        src.sendFeedback(() -> Text.literal("§eRight-click the Revive Totem§7 - Opens revive GUI for anyone (only if someone is banned)."), false);

        src.sendFeedback(() -> Text.literal("§6[HardcoreBan] Notes:"), false);
        src.sendFeedback(() -> Text.literal("§7- Expired bans are auto-removed from the revive list."), false);
        src.sendFeedback(() -> Text.literal("§7- If a player is online, they are removed from the revive list."), false);
    }

    // =========================================================
    // Permission check (reads ops.json directly)
    // =========================================================

    private static boolean isOpOrConsole(ServerCommandSource src) {
        if (src.getEntity() == null) return true;

        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return false;

        reloadOpsCacheIfNeeded();
        boolean ok = OPS_CACHE.contains(p.getUuid());

        log("requires() check player=" + p.getName().getString()
                + " uuid=" + p.getUuid()
                + " result=" + ok
                + " opsCacheSize=" + OPS_CACHE.size());

        return ok;
    }

    private static void reloadOpsCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - opsCacheLastLoadMs < 5000) return;
        opsCacheLastLoadMs = now;

        if (!Files.exists(OPS_JSON)) return;

        try (BufferedReader br = Files.newBufferedReader(OPS_JSON, StandardCharsets.UTF_8)) {
            List<OpsEntry> list = GSON.fromJson(br, OPS_LIST_TYPE);

            OPS_CACHE.clear();
            if (list != null) {
                for (OpsEntry e : list) {
                    if (e == null || e.uuid == null) continue;
                    if (e.level >= 4) OPS_CACHE.add(UUID.fromString(e.uuid));
                }
            }

            log("Reloaded ops.json entries=" + (list == null ? 0 : list.size()) + " level4UUIDs=" + OPS_CACHE.size());
        } catch (Exception ex) {
            OPS_CACHE.clear();
        }
    }

    // =========================================================
    // Public API (used by mixins / other classes)
    // =========================================================

    public static void queueTempBan(String playerName, String reason) {
        if (playerName == null || playerName.isBlank()) return;

        // store expiry locally so GUI can show timers + auto-clean
        long now = System.currentTimeMillis();
        long add = parseDurationToMillis(banDuration);
        long until = (add <= 0) ? (now + 24L * 60L * 60L * 1000L) : (now + add);

        BANNED_UNTIL.put(playerName, until);
        saveBannedStore();

        // queue BanHammer command
        BAN_QUEUE.add(new QueuedBan(playerName, banDuration, reason == null ? "" : reason));
    }

    // Keep this so your mixin compiles (even if you stop using it)
    public static String formatReason(String deathMessage, int x, int y, int z) {
        // You asked: do NOT show the detailed reason, just “{playername} has died”
        // So we keep a harmless format, but you can ignore it in the mixin.
        String time = LocalDateTime.now().format(TIME_FORMAT);
        return (deathMessage == null ? "Player has died" : deathMessage) + " | " + time;
    }

    public static void openReviveGui(ServerPlayerEntity opener) {
        openReviveMenu(opener);
    }

    // =========================================================
    // Revive menu (GUI) with heads + timer
    // =========================================================

    private static void openReviveMenu(ServerPlayerEntity opener) {
        MinecraftServer server = serverOrNull();

        // prune before showing
        pruneExpiredAndOnline(server, "open_gui");

        List<String> names = new ArrayList<>(BANNED_UNTIL.keySet());

        log("Opening revive GUI for " + opener.getName().getString()
                + " bannedCount=" + names.size());

        int rows = 6;
        int size = rows * 9;

        net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(size);
        List<String> slotToName = new ArrayList<>(Collections.nCopies(size, null));

        int count = Math.min(size, names.size());
        for (int i = 0; i < count; i++) {
            String name = names.get(i);
            inv.setStack(i, makeBannedPlayerHead(name, BANNED_UNTIL.getOrDefault(name, 0L)));
            slotToName.set(i, name);
        }

        opener.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, player) -> new ReviveMenuHandler(syncId, playerInv, inv, slotToName),
                Text.literal("Revive Menu")
        ));
    }

    private static ItemStack makeBannedPlayerHead(String name, long untilMs) {
        ItemStack viaCodec = tryBuildHeadViaCodec(name);
        ItemStack head = (viaCodec != null && !viaCodec.isEmpty()) ? viaCodec : new ItemStack(Items.PLAYER_HEAD);

        // timer text in name (simple + robust; avoids lore component mapping changes)
        String remaining = formatRemaining(untilMs);
        String title = remaining.isEmpty() ? name : (name + " §7(" + remaining + ")");
        head.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title));

        // fallback steve-head fix: set SkullOwner too (harmless even if codec worked)
        NbtCompound skullOwner = new NbtCompound();
        skullOwner.putString("Name", name);
        NbtCompound root = new NbtCompound();
        root.put("SkullOwner", skullOwner);
        head.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));

        return head;
    }

    private static ItemStack tryBuildHeadViaCodec(String name) {
        try {
            NbtCompound item = new NbtCompound();
            item.putString("id", "minecraft:player_head");
            item.putInt("count", 1);

            NbtCompound components = new NbtCompound();
            NbtCompound profile = new NbtCompound();
            profile.putString("name", name);
            components.put("minecraft:profile", profile);

            item.put("components", components);

            var dataResult = ItemStack.CODEC.parse(NbtOps.INSTANCE, item);
            var opt = dataResult.result();
            if (opt.isPresent()) return opt.get();

            log("ItemStack.CODEC parse failed for name=" + name + " error=" +
                    dataResult.error().map(e -> e.message()).orElse("unknown"));
            return null;
        } catch (Throwable t) {
            log("tryBuildHeadViaCodec exception: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static class ReviveMenuHandler extends GenericContainerScreenHandler {
        private final List<String> slotToName;

        public ReviveMenuHandler(int syncId,
                                 net.minecraft.entity.player.PlayerInventory playerInv,
                                 net.minecraft.inventory.SimpleInventory inv,
                                 List<String> slotToName) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.slotToName = slotToName;
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (slotIndex >= 0 && slotIndex < 54 && player instanceof ServerPlayerEntity sp) {
                String target = slotToName.get(slotIndex);
                if (target != null) {
                    log("GUI click by=" + sp.getName().getString() + " target=" + target);
                    reviveSelected(sp, target);
                    sp.closeHandledScreen();
                    return;
                }
            }
            super.onSlotClick(slotIndex, button, actionType, player);
        }
    }

    private static void reviveSelected(ServerPlayerEntity reviver, String targetName) {
        MinecraftServer server = SERVER;
        if (server == null) return;
        if (targetName == null || targetName.isBlank()) return;

        // If ban already expired, don't allow "revive"
        Long until = BANNED_UNTIL.get(targetName);
        long now = System.currentTimeMillis();
        if (until == null || until <= now) {
            // clean it out
            BANNED_UNTIL.remove(targetName);
            saveBannedStore();

            reviver.sendMessage(Text.literal("[HardcoreBan] That player is no longer banned."), false);
            log("Revive blocked: target=" + targetName + " not banned anymore");
            return;
        }

        // Totem pop for the reviver immediately
        triggerTotemPop(reviver);

        // Unban
        runCommand(server, "unban " + targetName);

        // Remove from banned store
        BANNED_UNTIL.remove(targetName);
        saveBannedStore();

        // Mark revived player to receive totem pop on first join back
        PENDING_TOTEM_POP.add(targetName);
        savePendingTotemPop();

        // Broadcast
        server.getPlayerManager().broadcast(Text.literal(targetName + " has been revived"), false);

        // Consume item on successful revive
        consumeOneReviveTotem(reviver);
    }

    private static void consumeOneReviveTotem(ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (!main.isEmpty() && main.isOf(REVIVE_ITEM)) {
            main.decrement(1);
            return;
        }

        ItemStack off = player.getOffHandStack();
        if (!off.isEmpty() && off.isOf(REVIVE_ITEM)) {
            off.decrement(1);
        }
    }

    // =========================================================
    // Ban queue runner (BanHammer /tempban)
    // =========================================================

    private static void runBanQueue(MinecraftServer server) {
        QueuedBan ban;
        while ((ban = BAN_QUEUE.poll()) != null) {
            // reason: you asked to keep it simple
            // (your mixin should pass "<name> has died")
            String command = "tempban " + ban.playerName + " " + ban.duration + " " + quote(ban.reason);
            runCommand(server, command);
        }
    }

    private static void runCommand(MinecraftServer server, String command) {
        try {
            ServerCommandSource source = server.getCommandSource().withSilent();
            server.getCommandManager().getDispatcher().execute(command, source);
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Command failed: " + command);
            e.printStackTrace();
        }
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    // =========================================================
    // Totem pop effect
    // =========================================================

    private static void triggerTotemPop(ServerPlayerEntity player) {
        if (player == null) return;

        if (!(player.getEntityWorld() instanceof ServerWorld sw)) return;

        // Vanilla "totem used" status
        sw.sendEntityStatus(player, (byte) 35);

        sw.spawnParticles(
                ParticleTypes.TOTEM_OF_UNDYING,
                player.getX(), player.getBodyY(0.5), player.getZ(),
                30,
                0.5, 0.6, 0.5,
                0.2
        );

        sw.playSound(
                null,
                player.getBlockPos(),
                SoundEvents.ITEM_TOTEM_USE,
                SoundCategory.PLAYERS,
                1.0f,
                1.0f
        );
    }

    // =========================================================
    // Pruning: remove expired + remove online players
    // =========================================================

    private static void pruneExpiredAndOnline(MinecraftServer server, String why) {
        long now = System.currentTimeMillis();

        boolean changed = false;

        // 1) remove expired
        Iterator<Map.Entry<String, Long>> it = BANNED_UNTIL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            long until = (e.getValue() == null ? 0L : e.getValue());
            if (until > 0L && until <= now) {
                log("Prune (" + why + ") expired removed name=" + e.getKey());
                it.remove();
                changed = true;
            }
        }

        // 2) remove online players (they cannot be banned if online)
        if (server != null && !BANNED_UNTIL.isEmpty()) {
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                String name = sp.getName().getString();
                if (BANNED_UNTIL.remove(name) != null) {
                    log("Prune (" + why + ") online removed name=" + name);
                    changed = true;
                }
            }
        }

        if (changed) saveBannedStore();
    }

    // =========================================================
    // Duration parsing + remaining display
    // =========================================================

    private static long parseDurationToMillis(String s) {
        if (s == null) return -1L;

        String str = s.trim().toLowerCase(Locale.ROOT);
        if (str.isEmpty()) return -1L;

        long total = 0L;
        int i = 0;

        while (i < str.length()) {
            // read number
            int start = i;
            while (i < str.length() && Character.isDigit(str.charAt(i))) i++;
            if (start == i) return -1L;

            long num;
            try {
                num = Long.parseLong(str.substring(start, i));
            } catch (NumberFormatException e) {
                return -1L;
            }

            if (i >= str.length()) return -1L;

            char unit = str.charAt(i++);
            long mul;
            switch (unit) {
                case 's' -> mul = 1000L;
                case 'm' -> mul = 60_000L;
                case 'h' -> mul = 3_600_000L;
                case 'd' -> mul = 86_400_000L;
                case 'y' -> mul = 31_536_000_000L; // 365d
                default -> { return -1L; }
            }

            total += num * mul;
        }

        return total;
    }

    private static String formatRemaining(long untilMs) {
        long now = System.currentTimeMillis();
        if (untilMs <= now) return "";

        long ms = untilMs - now;

        long s = ms / 1000L;
        long days = s / 86400L; s %= 86400L;
        long hours = s / 3600L; s %= 3600L;
        long mins = s / 60L;

        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    // =========================================================
    // Config + store load/save (config/HardcoreBan/)
    // =========================================================

    private static void ensureConfigDir() {
        try {
            Files.createDirectories(MOD_CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[HardcoreBan] Failed to create config dir: " + MOD_CONFIG_DIR);
            e.printStackTrace();
        }
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            saveConfig(); // write defaults
            return;
        }

        try (BufferedReader br = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            ConfigFile cfg = GSON.fromJson(br, CONFIG_TYPE);
            if (cfg != null) {
                if (cfg.banDuration != null && !cfg.banDuration.isBlank()) banDuration = cfg.banDuration;
                DEBUG = cfg.debug;
            }
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to load config: " + CONFIG_PATH);
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        ensureConfigDir();
        ConfigFile cfg = new ConfigFile();
        cfg.banDuration = banDuration;
        cfg.debug = DEBUG;

        try (BufferedWriter bw = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, bw);
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to save config: " + CONFIG_PATH);
            e.printStackTrace();
        }
    }

    private static void loadBannedStore() {
        if (!Files.exists(BANNED_PATH)) {
            saveBannedStore();
            return;
        }

        try (BufferedReader br = Files.newBufferedReader(BANNED_PATH, StandardCharsets.UTF_8)) {
            Map<String, Long> loaded = GSON.fromJson(br, BANNED_MAP_TYPE);
            BANNED_UNTIL.clear();
            if (loaded != null) BANNED_UNTIL.putAll(loaded);
            System.out.println("[HardcoreBan] Loaded banned entries=" + BANNED_UNTIL.size());
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to load banned store: " + BANNED_PATH);
            e.printStackTrace();
        }
    }

    private static void saveBannedStore() {
        ensureConfigDir();
        try (BufferedWriter bw = Files.newBufferedWriter(BANNED_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(BANNED_UNTIL, bw);
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to save banned store: " + BANNED_PATH);
            e.printStackTrace();
        }
    }

    private static void loadPendingTotemPop() {
        if (!Files.exists(PENDING_POP_PATH)) {
            savePendingTotemPop();
            return;
        }

        try (BufferedReader br = Files.newBufferedReader(PENDING_POP_PATH, StandardCharsets.UTF_8)) {
            Set<String> loaded = GSON.fromJson(br, STRING_SET_TYPE);
            PENDING_TOTEM_POP.clear();
            if (loaded != null) PENDING_TOTEM_POP.addAll(loaded);
            log("Loaded pending totem pop list size=" + PENDING_TOTEM_POP.size());
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to load pending pop store: " + PENDING_POP_PATH);
            e.printStackTrace();
        }
    }

    private static void savePendingTotemPop() {
        ensureConfigDir();
        try (BufferedWriter bw = Files.newBufferedWriter(PENDING_POP_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(PENDING_TOTEM_POP, bw);
        } catch (Exception e) {
            System.err.println("[HardcoreBan] Failed to save pending pop store: " + PENDING_POP_PATH);
            e.printStackTrace();
        }
    }

    // =========================================================
    // Debug helpers
    // =========================================================

    private static String safeName(ServerCommandSource src) {
        try {
            ServerPlayerEntity p = src.getPlayer();
            if (p != null) return p.getName().getString();
        } catch (Throwable ignored) {}
        return "CONSOLE";
    }

    private static void log(String msg) {
        if (!DEBUG) return;
        System.out.println("[HardcoreBan][DEBUG] " + msg);
    }

    private static MinecraftServer serverOrNull() {
        return SERVER;
    }
}
