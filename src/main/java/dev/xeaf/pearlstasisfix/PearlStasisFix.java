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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EntityEquipment;
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

    // Small armor stands are roughly this tall from feet to the top of the head slot.
    // Used purely to line the visible helmet item up near the top of the target block.
    private static final double ARMOR_STAND_HEAD_HEIGHT = 0.85;

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

        getLogger().info("PearlStasisFix enabled! Trapdoor-proof, clickable, Bedrock-visible stasis chambers active.");
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

    private void persistStasisRecord(UUID playerId, Location anchorLoc) {
        String path = playerId.toString();
        dataConfig.set(path + ".world", anchorLoc.getWorld().getName());
        dataConfig.set(path + ".x", anchorLoc.getX());
        dataConfig.set(path + ".y", anchorLoc.getY());
        dataConfig.set(path + ".z", anchorLoc.getZ());
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
        Location anchorLoc = new Location(world, x, y, z);

        // Request the chunk to load. If the dummy entity survived (it's marked persistent),
        // ChunkLoadEvent below will find it and resume monitoring automatically. As a
        // fallback - in case the entity didn't survive for whatever reason - we check a
        // few ticks later and respawn it from the saved coordinates if still missing.
        world.getChunkAtAsync(anchorLoc.getBlockX() >> 4, anchorLoc.getBlockZ() >> 4, true).thenAccept(chunk ->
                Bukkit.getRegionScheduler().runDelayed(this, anchorLoc, (task) -> {
                    if (activeStasis.containsKey(playerId)) {
                        return; // ChunkLoadEvent already reattached it
                    }
                    getLogger().info("Stasis entity for " + playerId + " wasn't found on disk, respawning from saved coordinates.");
                    chunk.addPluginChunkTicket(this);
                    Bukkit.getRegionScheduler().execute(this, anchorLoc, () -> {
                        ArmorStand dummy = world.spawn(toVisualSpawnLocation(anchorLoc), ArmorStand.class);
                        configureDummy(dummy, playerId);
                        activeStasis.put(playerId, dummy);
                        monitorStasisTrigger(playerId, dummy, chunk, anchorLoc);
                    });
                }, 5L)
        );
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof ArmorStand dummy)) continue;
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

            // Reconstruct the anchor (top-of-block) location from the entity's actual
            // position, since we only saved the visual offset for this entity itself.
            Location anchorLoc = dummy.getLocation().clone();
            anchorLoc.setY(dummy.getLocation().getY() + ARMOR_STAND_HEAD_HEIGHT);

            monitorStasisTrigger(ownerId, dummy, chunk, anchorLoc);
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
        // The anchor is used purely for game-logic (collision antenna math, persistence) -
        // it stays pinned to the top of the target block regardless of entity type.
        Location anchorLoc = peakLoc.clone();
        anchorLoc.setX(anchorLoc.getBlockX() + 0.5);
        anchorLoc.setY(anchorLoc.getBlockY() + 0.9);
        anchorLoc.setZ(anchorLoc.getBlockZ() + 0.5);

        Chunk chunk = anchorLoc.getChunk();
        chunk.addPluginChunkTicket(this);

        UUID playerId = player.getUniqueId();

        Bukkit.getRegionScheduler().execute(this, anchorLoc, () -> {
            ArmorStand dummy = anchorLoc.getWorld().spawn(toVisualSpawnLocation(anchorLoc), ArmorStand.class);
            configureDummy(dummy, playerId);

            activeStasis.put(playerId, dummy);
            persistStasisRecord(playerId, anchorLoc);
            realPearl.remove();

            monitorStasisTrigger(playerId, dummy, chunk, anchorLoc);
        });
    }

    /** Feet position that lines the armor stand's head/helmet up near the anchor's Y. */
    private Location toVisualSpawnLocation(Location anchorLoc) {
        Location visual = anchorLoc.clone();
        visual.setY(anchorLoc.getY() - ARMOR_STAND_HEAD_HEIGHT);
        return visual;
    }

    private void configureDummy(ArmorStand dummy, UUID playerId) {
        dummy.setVisible(false);
        dummy.setSmall(true);
        dummy.setBasePlate(false);
        dummy.setArms(false);
        dummy.setGravity(false);
        dummy.setInvulnerable(true);
        dummy.setCollidable(false);
        dummy.setSilent(true);
        dummy.setMarker(false); // keep a real hitbox so it's clickable
        // Persistent so it's saved as part of normal chunk/entity data and survives restarts.
        dummy.setPersistent(true);

        EntityEquipment equipment = dummy.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(Material.ENDER_PEARL));
        }

        dummy.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        dummy.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerId.toString());
    }

    // ------------------------------------------------------------------
    // Retrieval trigger #1: click the dummy directly (right-click or attack).
    // ------------------------------------------------------------------

    @EventHandler
    public void onDummyRightClick(PlayerInteractEntityEvent event) {
        if (isStasisDummy(event.getRightClicked())) {
            event.setCancelled(true); // don't let vanilla armor stand equip/swap logic run
            handleDummyClick(event.getRightClicked());
        }
    }

    @EventHandler
    public void onDummyAttack(EntityDamageByEntityEvent event) {
        if (isStasisDummy(event.getEntity())) {
            event.setCancelled(true); // the stand is invulnerable anyway, but stop knockback/particles too
            handleDummyClick(event.getEntity());
        }
    }

    private boolean isStasisDummy(Entity entity) {
        return entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void handleDummyClick(Entity dummy) {
        String ownerStr = dummy.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerStr == null) return;

        UUID ownerId;
        try {
            ownerId = UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        Location destination = dummy.getLocation();
        Chunk chunk = destination.getChunk();

        triggerTeleport(ownerId, destination);
        dummy.remove();
        cleanUp(ownerId, chunk);
    }

    // ------------------------------------------------------------------
    // Retrieval trigger #2: watch for a NEW solid block appearing around the dummy
    // (e.g. someone toggling a trapdoor via redstone) to know when to teleport back.
    // ------------------------------------------------------------------

    private void monitorStasisTrigger(UUID playerId, Entity dummy, Chunk chunk, Location anchorLoc) {
        Block center = anchorLoc.getBlock();

        // Stretch the hitbox slightly upward (Y + 0.8) to act as a collision "antenna"
        // This guarantees it catches trapdoors closing in the block directly above it
        BoundingBox pearlBox = new BoundingBox(
                anchorLoc.getX() - 0.2,
                anchorLoc.getY() - 0.2,
                anchorLoc.getZ() - 0.2,
                anchorLoc.getX() + 0.2,
                anchorLoc.getY() + 0.8,
                anchorLoc.getZ() + 0.2
        );

        // Rolling edge-detection: track which relative positions are solid on THIS poll
        // vs. the PREVIOUS poll, and only trigger when a position transitions from
        // non-solid to solid. This correctly ignores the chamber's own cap (e.g. a
        // trapdoor that's already closed when monitoring starts, holding the pearl in
        // place) without permanently blacklisting that position - so the player can
        // still open and re-close that very same trapdoor later to trigger retrieval.
        final Set<Long> previousSolid = new HashSet<>();
        final boolean[] initialized = {false};

        Bukkit.getRegionScheduler().runAtFixedRate(this, anchorLoc, (task) -> {
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
                triggerTeleport(playerId, anchorLoc);
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
