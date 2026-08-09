package dev.xeaf.pearlstasisfix;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PearlStasisFix extends JavaPlugin implements Listener {

    private final ConcurrentHashMap<UUID, Entity> activeStasis = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PearlStasisFix enabled! Trapdoor-proof stasis chambers active.");
    }

    @EventHandler
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;

        pearl.getScheduler().runAtFixedRate(this, (task) -> {
            if (!pearl.isValid()) {
                task.cancel();
                return;
            }
            if (pearl.getLocation().getBlock().getType() == Material.BUBBLE_COLUMN) {
                createStasisDummy(player, pearl);
                task.cancel();
            }
        }, null, 20L, 10L);
    }

    private void createStasisDummy(Player player, EnderPearl realPearl) {
        Location stasisLoc = realPearl.getLocation();

        // Snap X and Z to center to avoid walls
        stasisLoc.setX(stasisLoc.getBlockX() + 0.5);
        // Snap Y near the very top of the block so it touches closed trapdoors
        stasisLoc.setY(stasisLoc.getBlockY() + 0.9);
        stasisLoc.setZ(stasisLoc.getBlockZ() + 0.5);

        Chunk chunk = stasisLoc.getChunk();
        chunk.addPluginChunkTicket(this);

        Bukkit.getRegionScheduler().execute(this, stasisLoc, () -> {
            ItemDisplay dummy = stasisLoc.getWorld().spawn(stasisLoc, ItemDisplay.class);
            dummy.setItemStack(new ItemStack(Material.ENDER_PEARL));
            dummy.setBillboard(Display.Billboard.CENTER);
            dummy.setPersistent(false);

            activeStasis.put(player.getUniqueId(), dummy);
            realPearl.remove();

            monitorStasisTrigger(player.getUniqueId(), dummy, chunk);
        });
    }

    private void monitorStasisTrigger(UUID playerId, Entity dummy, Chunk chunk) {
        Location loc = dummy.getLocation();

        // We stretch the hitbox slightly upward (Y + 0.8) to act as a collision "antenna"
        // This guarantees it catches trapdoors closing in the block directly above it
        BoundingBox pearlBox = new BoundingBox(
                loc.getX() - 0.2,
                loc.getY() - 0.2,
                loc.getZ() - 0.2,
                loc.getX() + 0.2,
                loc.getY() + 0.8,
                loc.getZ() + 0.2
        );

        Bukkit.getRegionScheduler().runAtFixedRate(this, loc, (task) -> {
            if (!dummy.isValid()) {
                cleanUp(playerId, chunk);
                task.cancel();
                return;
            }

            boolean collisionDetected = false;
            Block center = loc.getBlock();

            // Scan the 3x3x3 grid around the pearl
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = center.getRelative(x, y, z);

                        if (b.getType().isSolid() && b.getBoundingBox().overlaps(pearlBox)) {
                            collisionDetected = true;
                            break;
                        }
                    }
                    if (collisionDetected) break;
                }
                if (collisionDetected) break;
            }

            if (collisionDetected) {
                triggerTeleport(playerId, loc);
                dummy.remove();
                cleanUp(playerId, chunk);
                task.cancel();
            }
        }, 10L, 2L);
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
    }
}
