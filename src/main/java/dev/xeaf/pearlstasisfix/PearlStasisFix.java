package dev.xeaf.pearlstasisfix;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PearlStasisFix extends JavaPlugin implements Listener {

    private final ConcurrentHashMap<UUID, Entity> activeStasis = new ConcurrentHashMap<>();

    private NamespacedKey markerKey;
    private NamespacedKey ownerKey;

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "stasis-marker");
        ownerKey = new NamespacedKey(this, "stasis-owner");

        getServer().getPluginManager().registerEvents(this, this);

        loadDataFile();
        reattachAllPersistedStasis();

        getLogger().info("PearlStasisFix enabled! Trapdoor-proof stasis chambers active, and they now survive restarts.");
    }

    @Override
    public void onDisable() {
        saveDataFile();
    }

    // ------------------------------------------------------------------
    // Persistence (survives server restarts)
    // ------------------------------------------------------------------

    private void loadDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "stasis.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create stasis.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save stasis.yml: " + e.getMessage());
        }
    }

    private void persistStasisRecord(UUID playerId, Location loc) {
        String path = playerId.toString();
        dataConfig.set(path + ".world", loc.getWorld().getName());
        dataConfig.set(path + ".x", loc.getX());
        dataConfig.set(path + ".y", loc.getY());
        dataConfig.set(path + ".z", loc.getZ());
        saveDataFile();
    }

    private void removeStasisRecord(UUID playerId) {
        dataConfig.set(playerId.toString(), null);
        saveDataFile();
    }

    /**
     * Called on startup. For every stasis record we have saved to disk, request the
     * chunk it lives in to load. If the world containing it isn't loaded yet, we skip
     * it here and pick it back up in onWorldLoad once that world comes online.
     */
    private void reattachAllPersistedStasis() {
        for (String key : new HashSet<>(dataConfig.getKeys(false))) {
            reattachRecord(key);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        for (String key : new HashSet<>(dataConfig.getKeys(false))) {
            if (worldName.equals(dataConfig.getString(key + ".world"))) {
                reattachRecord(key);
            }
        }
    }

    private void reattachRecord(String playerIdStr) {
        UUID playerId;
        try {
            playerId = UUID.fromString(playerIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (activeStasis.containsKey(playerId)) {
            return; // already tracking this one
        }

        String worldName = dataConfig.getString(playerIdStr + ".world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return; // world not loaded yet - onWorldLoad will retry this later
        }

        double x = dataConfig.getDouble(playerIdStr + ".x");
        double y = dataConfig.getDouble(playerIdStr + ".y");
        double z = dataConfig.getDouble(playerIdStr + ".z");
        Location loc = new Location(world, x, y, z);

        // Request the chunk to load. If the dummy entity survived (it's marked persistent),
        // ChunkLoadEvent below will find it and resume monitoring automatically. As a
        // fallback - in case the entity didn't survive for whatever reason - we check a
        // few ticks later and respawn it from the saved coordinates if still missing.
        world.getChunkAtAsync(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, true).thenAccept(chunk ->
                Bukkit.getRegionScheduler().runDelayed(this, loc, (task) -> {
                    if (activeStasis.containsKey(playerId)) {
                        return; // ChunkLoadEvent already reattached it
                    }
                    getLogger().info("Stasis entity for " + playerId + " wasn't found on disk, respawning from saved coordinates.");
                    chunk.addPluginChunkTicket(this);
                    Bukkit.getRegionScheduler().execute(this, loc, () -> {
                        ItemDisplay dummy = world.spawn(loc, ItemDisplay.class);
                        configureDummy(dummy, playerId);
                        activeStasis.put(playerId, dummy);
                        monitorStasisTrigger(playerId, dummy, chunk);
                    });
                }, 5L)
        );
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof ItemDisplay dummy)) continue;
            if (!dummy.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) continue;

            String ownerStr = dummy.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerStr == null) continue;

            UUID ownerId;
            try {
                ownerId = UUID.fromString(ownerStr);
            } catch (IllegalArgumentException e) {
                continue;
            }

            if (activeStasis.containsKey(ownerId)) continue; // already being monitored

            Chunk chunk = event.getChunk();
            chunk.addPluginChunkTicket(this);
            activeStasis.put(ownerId, dummy);
            monitorStasisTrigger(ownerId, dummy, chunk);
        }
    }

    // ------------------------------------------------------------------
    // Pearl tracking: let the bubble column carry it up naturally, and lock it in
    // the instant it starts falling back down (the top of its arc).
    // ------------------------------------------------------------------

    @EventHandler
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;

        // Only arm once the pearl has actually entered a bubble column - this is what
        // tells us "this is a stasis chamber throw", not just an ordinary pearl arcing
        // through the air.
        final AtomicBoolean armed = new AtomicBoolean(false);
        final double[] lastY = {Double.NEGATIVE_INFINITY};
        final Location[] peak = {null};

        pearl.getScheduler().runAtFixedRate(this, (task) -> {
            if (!pearl.isValid()) {
                task.cancel();
                return;
            }

            Location loc = pearl.getLocation();

            if (!armed.get()) {
                if (loc.getBlock().getType() == Material.BUBBLE_COLUMN) {
                    armed.set(true);
                    lastY[0] = loc.getY();
                    peak[0] = loc.clone();
                }
                return;
            }

            // Most reliable signal: it's physically pinned against a solid ceiling
            // (closed trapdoor, solid block, etc.) and literally cannot rise any further.
            // Check this BEFORE the Y-decrease check, since a solid cap can cause a tiny
            // bounce-back the very tick it lands, which would otherwise get misread as
            // "started falling" one block early.
            if (loc.getBlock().getRelative(0, 1, 0).getType().isSolid()) {
                createStasisDummy(player, pearl, loc);
                task.cancel();
                return;
            }

            // Small tolerance so tiny per-tick jitter isn't mistaken for descent.
            if (loc.getY() >= lastY[0] - 0.02) {
                // Still rising (or holding steady) - remember this as the highest point so far.
                if (loc.getY() > lastY[0]) {
                    lastY[0] = loc.getY();
                    peak[0] = loc.clone();
                }
                return;
            }

            // It's now clearly falling - that's the top of the (uncapped) arc. Lock it in.
            createStasisDummy(player, pearl, peak[0]);
            task.cancel();
        }, null, 1L, 1L);
    }

    private void createStasisDummy(Player player, EnderPearl realPearl, Location peakLoc) {
        Location stasisLoc = peakLoc.clone();

        // Snap X and Z to center to avoid walls
        stasisLoc.setX(stasisLoc.getBlockX() + 0.5);
        // Snap Y near the very top of the block so it touches closed trapdoors
        stasisLoc.setY(stasisLoc.getBlockY() + 0.9);
        stasisLoc.setZ(stasisLoc.getBlockZ() + 0.5);

        Chunk chunk = stasisLoc.getChunk();
        chunk.addPluginChunkTicket(this);

        UUID playerId = player.getUniqueId();

        Bukkit.getRegionScheduler().execute(this, stasisLoc, () -> {
            ItemDisplay dummy = stasisLoc.getWorld().spawn(stasisLoc, ItemDisplay.class);
            configureDummy(dummy, playerId);

            activeStasis.put(playerId, dummy);
            persistStasisRecord(playerId, stasisLoc);
            realPearl.remove();

            monitorStasisTrigger(playerId, dummy, chunk);
        });
    }

    private void configureDummy(ItemDisplay dummy, UUID playerId) {
        dummy.setItemStack(new ItemStack(Material.ENDER_PEARL));
        dummy.setBillboard(Display.Billboard.CENTER);
        // Persistent so it's saved as part of normal chunk/entity data and survives restarts.
        dummy.setPersistent(true);
        dummy.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        dummy.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerId.toString());
    }

    // ------------------------------------------------------------------
    // Retrieval: watch for a NEW solid block appearing around the dummy (e.g. someone
    // toggling a trapdoor via redstone) to know when to teleport the player back.
    // ------------------------------------------------------------------

    private void monitorStasisTrigger(UUID playerId, Entity dummy, Chunk chunk) {
        Location loc = dummy.getLocation();
        Block center = loc.getBlock();

        // Stretch the hitbox slightly upward (Y + 0.8) to act as a collision "antenna"
        // This guarantees it catches trapdoors closing in the block directly above it
        BoundingBox pearlBox = new BoundingBox(
                loc.getX() - 0.2,
                loc.getY() - 0.2,
                loc.getZ() - 0.2,
                loc.getX() + 0.2,
                loc.getY() + 0.8,
                loc.getZ() + 0.2
        );

        // Rolling edge-detection: track which relative positions are solid on THIS poll
        // vs. the PREVIOUS poll, and only trigger when a position transitions from
        // non-solid to solid. This correctly ignores the chamber's own cap (e.g. a
        // trapdoor that's already closed when monitoring starts, holding the pearl in
        // place) without permanently blacklisting that position - so the player can
        // still open and re-close that very same trapdoor later to trigger retrieval.
        final Set<Long> previousSolid = new HashSet<>();
        final boolean[] initialized = {false};

        Bukkit.getRegionScheduler().runAtFixedRate(this, loc, (task) -> {
            if (!dummy.isValid()) {
                cleanUp(playerId, chunk);
                task.cancel();
                return;
            }

            Set<Long> currentSolid = new HashSet<>();
            boolean newCollisionDetected = false;

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = center.getRelative(x, y, z);
                        long key = offsetKey(x, y, z);

                        boolean solidNow = b.getType().isSolid() && b.getBoundingBox().overlaps(pearlBox);
                        if (solidNow) {
                            currentSolid.add(key);
                            if (initialized[0] && !previousSolid.contains(key)) {
                                newCollisionDetected = true;
                            }
                        }
                    }
                }
            }

            previousSolid.clear();
            previousSolid.addAll(currentSolid);
            initialized[0] = true;

            if (newCollisionDetected) {
                triggerTeleport(playerId, loc);
                dummy.remove();
                cleanUp(playerId, chunk);
                task.cancel();
            }
        }, 10L, 2L);
    }

    private long offsetKey(int x, int y, int z) {
        return ((long) (x + 1) << 8) | ((long) (y + 1) << 4) | (long) (z + 1);
    }

    private void triggerTeleport(UUID playerId, Location destination) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.teleportAsync(destination).thenAccept(success -> {
                if (success) {
                    player.sendMessage("§aYou have been returned to your stasis chamber!");
                }
            });
        }
    }

    private void cleanUp(UUID playerId, Chunk chunk) {
        activeStasis.remove(playerId);
        chunk.removePluginChunkTicket(this);
        removeStasisRecord(playerId);
    }
}
