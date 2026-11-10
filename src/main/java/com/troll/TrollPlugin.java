package com.troll;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.IntFunction;

public class TrollPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey overlayTag;
    private final Random random = new Random();

    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> jumpTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> overlayTasks = new HashMap<>();
    private final Map<UUID, ItemStack> savedHelmets = new HashMap<>();
    /** Players who have the optional resource pack loaded (they get the custom images). */
    private final Set<UUID> packLoaded = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        overlayTag = new NamespacedKey(this, "overlay");
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("troll");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) stopOverlay(p);
        jumpTasks.values().forEach(BukkitTask::cancel);
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "Only operators can use this command.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /troll <jumpscare|keyboard|nonstopjump|loadingscreen> <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "jumpscare" -> jumpscare(target);
            case "keyboard" -> keyboard(target);
            case "nonstopjump" -> nonStopJump(target);
            case "loadingscreen" -> loadingScreen(target);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown troll: " + args[0]);
                return true;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Trolled " + target.getName() + " with " + args[0].toLowerCase() + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("jumpscare", "keyboard", "nonstopjump", "loadingscreen"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }

    // ------------------------------------------------------------ trolls

    private void jumpscare(Player p) {
        int total = getConfig().getInt("jumpscare-seconds", 4) * 20;
        if (packLoaded.contains(p.getUniqueId())) {
            // custom images, flickering between the 3 scary images
            int offset = random.nextInt(3);
            startOverlay(p, total, tick -> "jumpscare" + (1 + ((tick / 6 + offset) % 3)));
        } else {
            // no resource pack: vanilla Elder Guardian full-screen scare + darkness + nausea
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, total, 0, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, total, 1, false, false, false));
            for (int i = 0; i < 3; i++)
                Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) vanillaScare(p); }, i * 30L);
        }
        playScream(p);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) playScream(p); }, 25L);
    }

    private void vanillaScare(Player p) {
        Particle particle = null;
        for (String key : new String[]{"mob_appearance", "elder_guardian"}) {
            particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(key));
            if (particle != null) break;
        }
        if (particle != null) p.spawnParticle(particle, p.getLocation(), 1);
    }

    private void playScream(Player p) {
        Location loc = p.getLocation();
        if (getConfig().getBoolean("use-custom-sound", false) && packLoaded.contains(p.getUniqueId())) {
            // Sound volume is capped client-side, so layer several copies to make it LOUD.
            for (int i = 0; i < 6; i++) p.playSound(loc, "troll:scream", SoundCategory.MASTER, 10f, 1f);
            return;
        }
        Sound[] screams = {Sound.ENTITY_GHAST_SCREAM, Sound.ENTITY_ENDERMAN_SCREAM, Sound.ENTITY_WARDEN_ROAR};
        float[] pitches = {0.6f, 0.8f, 1.0f};
        for (Sound s : screams)
            for (float pitch : pitches)
                p.playSound(loc, s, SoundCategory.MASTER, 10f, pitch);
    }

    private void keyboard(Player p) {
        int secs = getConfig().getInt("keyboard-seconds", 60);
        freeze(p, secs);
        p.sendActionBar(ChatColor.RED + "Keyboard disconnected!");
        p.sendMessage(ChatColor.RED + "Your keyboard has been disconnected...");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && !isFrozen(p)) p.sendMessage(ChatColor.GREEN + "Keyboard reconnected.");
        }, secs * 20L + 2L);
    }

    private void nonStopJump(Player p) {
        BukkitTask old = jumpTasks.remove(p.getUniqueId());
        if (old != null) old.cancel();
        int total = getConfig().getInt("nonstopjump-seconds", 60) * 20;
        BukkitTask task = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || tick++ >= total) {
                    cancel();
                    jumpTasks.remove(p.getUniqueId());
                    return;
                }
                if (p.isOnGround() || p.getLocation().clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()) {
                    Vector v = p.getVelocity();
                    v.setY(0.42);
                    p.setVelocity(v);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
        jumpTasks.put(p.getUniqueId(), task);
    }

    private void loadingScreen(Player p) {
        int secs = getConfig().getInt("loadingscreen-seconds", 8);
        int total = secs * 20;
        freeze(p, secs);
        if (packLoaded.contains(p.getUniqueId())) {
            startOverlay(p, total, tick -> "loading" + Math.min(4, tick * 5 / total));
            return;
        }
        // no resource pack: black screen + white "MOJANG STUDIOS" title + progress bar
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, total, 0, false, false, false));
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!p.isOnline() || tick >= total) {
                    cancel();
                    if (p.isOnline()) { p.resetTitle(); p.removePotionEffect(PotionEffectType.BLINDNESS); }
                    return;
                }
                int filled = Math.min(20, tick * 20 / total);
                String bar = ChatColor.WHITE + "\u25AE".repeat(filled) + ChatColor.DARK_GRAY + "\u25AE".repeat(20 - filled);
                p.sendTitle(ChatColor.WHITE + "" + ChatColor.BOLD + "MOJANG STUDIOS", bar, 0, 15, 0);
                tick += 5;
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    // ------------------------------------------------------------ keyboard freeze

    private void freeze(Player p, int seconds) {
        frozenUntil.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    private boolean isFrozen(Player p) {
        Long until = frozenUntil.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) { frozenUntil.remove(p.getUniqueId()); return false; }
        return true;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isFrozen(e.getPlayer()) || e.getTo() == null) return;
        Location f = e.getFrom(), t = e.getTo();
        // WASD / space / sprint do nothing; looking around (mouse) and falling still work
        if (f.getX() != t.getX() || f.getZ() != t.getZ() || t.getY() > f.getY()) e.setCancelled(true);
    }
    @EventHandler public void onHotbar(PlayerItemHeldEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSneak(PlayerToggleSneakEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSprint(PlayerToggleSprintEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @SuppressWarnings("deprecation")
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }

    // ------------------------------------------------------------ full-screen overlay

    /** Puts an invisible "helmet" with a camera_overlay on the player's head; the texture covers the whole screen. */
    private ItemStack overlayItem(String texture) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        EquippableComponent eq = meta.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setCameraOverlay(new NamespacedKey("troll", "overlay/" + texture));
        meta.setEquippable(eq);
        meta.getPersistentDataContainer().set(overlayTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isOverlayItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(overlayTag, PersistentDataType.BYTE);
    }

    private void startOverlay(Player p, int totalTicks, IntFunction<String> frame) {
        stopOverlay(p);
        UUID id = p.getUniqueId();
        ItemStack original = p.getInventory().getHelmet();
        savedHelmets.put(id, original == null ? new ItemStack(Material.AIR) : original.clone());

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;
            String last = "";
            @Override public void run() {
                if (!p.isOnline() || tick >= totalTicks) { stopOverlay(p); return; }
                String key = frame.apply(tick);
                if (!key.equals(last)) {
                    p.getInventory().setHelmet(overlayItem(key));
                    last = key;
                }
                tick += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
        overlayTasks.put(id, task);
    }

    private void stopOverlay(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = overlayTasks.remove(id);
        if (t != null) t.cancel();
        ItemStack saved = savedHelmets.remove(id);
        if (saved != null && p.isOnline()) p.getInventory().setHelmet(saved.getType() == Material.AIR ? null : saved);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && savedHelmets.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isOverlayItem);
        stopOverlay(e.getEntity());
    }

    // ------------------------------------------------------------ join / quit / resource pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // clean up if the server crashed mid-troll
        if (isOverlayItem(p.getInventory().getHelmet())) p.getInventory().setHelmet(null);

        String url = getConfig().getString("resource-pack.url", "");
        if (url.isEmpty()) return;
        String sha1 = getConfig().getString("resource-pack.sha1", "");
        boolean force = getConfig().getBoolean("resource-pack.force", true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            if (sha1.length() == 40) p.setResourcePack(url, hex(sha1), "Required for server features", force);
            else p.setResourcePack(url);
        }, 20L);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> packLoaded.add(id);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> packLoaded.remove(id);
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        packLoaded.remove(p.getUniqueId());
        stopOverlay(p);
        frozenUntil.remove(p.getUniqueId());
        BukkitTask t = jumpTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
