package pepjebs.mapatlases.lifecycle;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.HeldItemChangeS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.networking.MapAtlasesInitAtlasS2CPacket;
import pepjebs.mapatlases.networking.MapAtlasesOpenGUIC2SPacket;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class MapAtlasesServerLifecycleEvents {

    // Used to prevent map auto-creation from spamming new maps when server is lagging
    private static final HashMap<String, Integer> lastMapCreationSize = new HashMap<>();

    // Value minimum of 64, since maps are 128
    private static final int NEW_MAP_CENTER_DISTANCE = 90;

    public static void openGuiEvent(
            MinecraftServer server,
            ServerPlayerEntity player,
            ServerPlayNetworkHandler _handler,
            PacketByteBuf buf,
            PacketSender _responseSender) {
        MapAtlasesOpenGUIC2SPacket p = new MapAtlasesOpenGUIC2SPacket();
        p.read(buf);
        server.execute(() -> {
            ItemStack atlas = p.atlas;
            int atlasIdx = player.inventory.main.size();
            for (int i = 0; i < player.inventory.main.size(); i++) {
                if (player.inventory.main.get(i).getItem() == atlas.getItem() &&
                        player.inventory.main.get(i).getTag() != null &&
                        atlas.getTag() != null &&
                        player.inventory.main.get(i).getTag().toString().compareTo(atlas.getTag().toString()) == 0) {
                    atlasIdx = i;
                    break;
                }
            }
            if (atlasIdx < PlayerInventory.getHotbarSize()) {
                player.inventory.selectedSlot = atlasIdx;
                player.networkHandler.sendPacket(new HeldItemChangeS2CPacket(atlasIdx));
                player.openHandledScreen((MapAtlasItem) atlas.getItem());
                player.getServerWorld().playSound(null, player.getBlockPos(),
                        MapAtlasesMod.ATLAS_OPEN_SOUND_EVENT, SoundCategory.PLAYERS, 1.0F, 1.0F);
            }
        });
    }

    public static void mapAtlasPlayerJoin(
            ServerPlayNetworkHandler serverPlayNetworkHandler,
            PacketSender _responseSender,
            MinecraftServer _server
    ) {
        ServerPlayerEntity player = serverPlayNetworkHandler.player;
        ItemStack atlas = player.inventory.main.stream()
                .filter(is -> is.isItemEqual(new ItemStack(MapAtlasesMod.MAP_ATLAS))).findAny().orElse(ItemStack.EMPTY);
        if (atlas.isEmpty()) return;
        List<MapState> mapStates = MapAtlasesAccessUtils.getAllMapStatesFromAtlas(player.getServerWorld(), atlas);
        for (MapState state : mapStates) {
            state.update(player, atlas);
            state.getPlayerSyncData(player);
            PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
            (new MapAtlasesInitAtlasS2CPacket(state)).write(packetByteBuf);
            player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                    MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_INIT,
                    packetByteBuf));
            MapAtlasesMod.LOGGER.info("Server Sent MapState: " + state.getId());
        }
    }

    public static void mapAtlasServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ItemStack atlas = player.inventory.main.stream()
                    .filter(is -> is.isItemEqual(new ItemStack(MapAtlasesMod.MAP_ATLAS))).findAny().orElse(ItemStack.EMPTY);
            if (!atlas.isEmpty()) {
                List<MapState> mapStates = MapAtlasesAccessUtils.getAllMapStatesFromAtlas(player.getServerWorld(), atlas);

                // Maps are 128x128
                int playX = player.getBlockPos().getX();
                int playZ = player.getBlockPos().getZ();
                int minDist = Integer.MAX_VALUE;
                int scale = -1;

                for (MapState state : mapStates) {
                    state.update(player, atlas);
                    ((FilledMapItem) Items.FILLED_MAP).updateColors(player.getServerWorld(), player, state);
                    ItemStack map = MapAtlasesAccessUtils.createMapItemStackFromStrId(state.getId());
                    Packet<?> p = null;
                    int tries = 0;
                    while (p == null && tries < 10) {
                        p = state.getPlayerMarkerPacket(map, player.getServerWorld(), player);
                        tries++;
                    }
                    if (p != null) {
                        PacketByteBuf packetByteBuf = new PacketByteBuf(Unpooled.buffer());
                        try {
                            p.write(packetByteBuf);
                            player.networkHandler.sendPacket(new CustomPayloadS2CPacket(
                                    MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_SYNC,
                                    packetByteBuf));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    int mapCX = state.xCenter;
                    int mapCZ = state.zCenter;
                    minDist = Math.min(minDist, (int) Math.hypot(playX-mapCX, playZ-mapCZ));
                    scale = state.scale;
                }

                if (atlas.getTag() == null) continue;
                List<Integer> mapIds = Arrays.stream(
                        atlas.getTag().getIntArray("maps")).boxed().collect(Collectors.toList());
                int mapSize = mapIds.size();
                int emptyCount = MapAtlasesAccessUtils.getEmptyMapCountFromItemStack(atlas);
                if (!lastMapCreationSize.containsKey(player.getName().getString()))
                    lastMapCreationSize.put(player.getName().getString(), -1);
                int lastMapSize = lastMapCreationSize.get(player.getName().getString());
                if (lastMapSize != mapSize
                        && minDist != -1 && scale != -1 && minDist > (NEW_MAP_CENTER_DISTANCE * (1 << scale)) && emptyCount > 0) {
                    atlas.getTag().putInt("empty", atlas.getTag().getInt("empty") - 1);
                    ItemStack newMap = FilledMapItem.createMap(
                            player.getServerWorld(),
                            MathHelper.floor(player.getX()),
                            MathHelper.floor(player.getZ()),
                            (byte) scale,
                            true,
                            false);
                    lastMapCreationSize.put(player.getName().getString(), mapIds.size());
                    mapIds.add(FilledMapItem.getMapId(newMap));
                    atlas.getTag().putIntArray("maps", mapIds);
                    player.getServerWorld().playSound(null, player.getBlockPos(),
                            MapAtlasesMod.ATLAS_CREATE_MAP_SOUND_EVENT,
                            SoundCategory.PLAYERS, 1.0F, 1.0F);
                }
            }
        }
    }
}