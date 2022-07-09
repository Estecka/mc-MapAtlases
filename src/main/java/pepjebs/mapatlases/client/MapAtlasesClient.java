package pepjebs.mapatlases.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.lifecycle.MapAtlasesClientLifecycleEvents;
import pepjebs.mapatlases.lifecycle.MapAtlasesServerLifecycleEvents;
import pepjebs.mapatlases.networking.MapAtlasesInitAtlasS2CPacket;
import pepjebs.mapatlases.screen.MapAtlasesAtlasOverviewScreen;

public class MapAtlasesClient implements ClientModInitializer {

    private static final ThreadLocal<Integer> worldMapZoomLevel = new ThreadLocal<>();
    public static KeyBinding displayMapGUIBinding;
    public static String currentMapStateId = null;

    @Override
    public void onInitializeClient() {
        // Register client screen
        HandledScreens.register(MapAtlasesMod.ATLAS_OVERVIEW_HANDLER, MapAtlasesAtlasOverviewScreen::new);

        // Register client events
        ClientTickEvents.END_CLIENT_TICK.register(MapAtlasesClientLifecycleEvents::mapAtlasClientTick);
        ClientPlayNetworking.registerGlobalReceiver(MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_INIT,
                MapAtlasesClientLifecycleEvents::mapAtlasClientInit);
        ClientPlayNetworking.registerGlobalReceiver(MapAtlasesInitAtlasS2CPacket.MAP_ATLAS_SYNC,
                MapAtlasesClientLifecycleEvents::mapAtlasClientSync);
        ClientPlayNetworking.registerGlobalReceiver(MapAtlasesServerLifecycleEvents.MAP_ATLAS_ACTIVE_STATE_CHANGE, (
            MinecraftClient _client,
            ClientPlayNetworkHandler _handler,
            PacketByteBuf buf,
            PacketSender _sender) -> {
                String str = buf.readString();
                if (str.compareTo("null") == 0)
                    currentMapStateId = null;
                else
                    currentMapStateId = str;
            });

        // Register Keybind
        displayMapGUIBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.map_atlases.open_minimap",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.map_atlases.minimap"
        ));

        // Register ModelPredicate
        ModelPredicateProviderRegistry.register(
                MapAtlasesMod.MAP_ATLAS,
                new Identifier("atlas"),
                this::getPredicateForAtlas);
    }

    public static int getWorldMapZoomLevel() {
        if (worldMapZoomLevel.get() == null) return 1;
        return worldMapZoomLevel.get();
    }

    public static void setWorldMapZoomLevel(int i) {
        worldMapZoomLevel.set(i);
    }

    public float getPredicateForAtlas(
            ItemStack _stack,
            ClientWorld _world,
            LivingEntity entity,
            int _seed) {
        // Using ClientWorld will render default Atlas in inventories
        if (entity.world == null) return 0.0f;
        if (entity.world.getRegistryKey() == World.OVERWORLD) return 0.1f;
        if (entity.world.getRegistryKey() == World.NETHER) return 0.2f;
        if (entity.world.getRegistryKey() == World.END) return 0.3f;
        return 0.0f;
    }
}
