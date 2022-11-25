package pepjebs.mapatlases.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.*;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapIcon;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3f;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.client.ui.MapAtlasesHUD;
import pepjebs.mapatlases.mixin.MapRendererMixin;
import pepjebs.mapatlases.utils.MapStateIntrfc;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;

import java.util.*;
import java.util.stream.Collectors;

public class MapAtlasesAtlasOverviewScreen extends HandledScreen<ScreenHandler> {

    public static final Identifier ATLAS_FOREGROUND =
            new Identifier("map_atlases:textures/gui/screen/atlas_foreground.png");
    public static final Identifier ATLAS_BACKGROUND =
            new Identifier("map_atlases:textures/gui/screen/atlas_background.png");
    public static final Identifier PAGE_SELECTED =
            new Identifier("map_atlases:textures/gui/screen/page_selected.png");
    public static final Identifier PAGE_UNSELECTED =
            new Identifier("map_atlases:textures/gui/screen/page_unselected.png");
    public static final Identifier PAGE_OVERWORLD =
            new Identifier("map_atlases:textures/gui/screen/overworld_atlas_page.png");
    public static final Identifier PAGE_NETHER =
            new Identifier("map_atlases:textures/gui/screen/nether_atlas_page.png");
    public static final Identifier PAGE_END =
            new Identifier("map_atlases:textures/gui/screen/end_atlas_page.png");
    public static final Identifier PAGE_OTHER =
            new Identifier("map_atlases:textures/gui/screen/unknown_atlas_page.png");
    public static final Identifier MAP_ICON_TEXTURE =
            new Identifier("textures/map/map_icons.png");
    private static final int ZOOM_BUCKET = 4;
    private static final int PAN_BUCKET = 25;

    private final ItemStack atlas;
    public final String centerMapId;
    public Map<Integer, Pair<String,List<Integer>>> idsToCenters;
    private int mouseXOffset = 0;
    private int mouseYOffset = 0;
    private double rawMouseXMoved = 0;
    private double rawMouseYMoved = 0;
    private int zoomValue = ZOOM_BUCKET;
    private String registryWorldSelected;
    private final String initialWorldSelected;

    public MapAtlasesAtlasOverviewScreen(ScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        atlas = ((MapAtlasesAtlasOverviewScreenHandler) handler).atlas;
        idsToCenters = ((MapAtlasesAtlasOverviewScreenHandler) handler).idsToCenters;
        centerMapId = ((MapAtlasesAtlasOverviewScreenHandler) handler).centerMapId;
        registryWorldSelected = MapAtlasesAccessUtils.getPlayerDimKey(inventory.player);
        initialWorldSelected = MapAtlasesAccessUtils.getPlayerDimKey(inventory.player);
        // Play open sound
        inventory.player.playSound(
                MapAtlasesMod.ATLAS_OPEN_SOUND_EVENT, SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawBackground(matrices, delta, mouseX, mouseY);
    }

    private int getAtlasBgScaledSize() {
        if (client == null) return 16;
        if (MapAtlasesMod.CONFIG != null) {
            return (int) Math.floor(
                    MapAtlasesMod.CONFIG.forceWorldMapScaling/100.0 * client.getWindow().getScaledHeight());
        }
        return (int) Math.floor(.8 * client.getWindow().getScaledHeight());
    }

    private Map<Integer, Pair<String, List<Integer>>> getCurrentIdsToCenters() {
        return idsToCenters.entrySet().stream()
                .filter(t -> t.getValue().getFirst().compareTo(registryWorldSelected) == 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (m1, m2) -> m1));
    }

    @Override
    protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
        if (client == null || client.player == null || client.world == null) return;
        var currentIdsToCenters = getCurrentIdsToCenters();
        // Handle zooming
        int atlasBgScaledSize = getAtlasBgScaledSize();
        double drawnMapBufferSize = atlasBgScaledSize / 18.0;
        int atlasDataScaledSize = (int) (atlasBgScaledSize - (2 * drawnMapBufferSize));
        int zoomLevel = round(zoomValue, ZOOM_BUCKET) / ZOOM_BUCKET;
        zoomLevel = Math.max(zoomLevel, 0);
        int zoomLevelDim = (2 * zoomLevel) + 1;
        MapAtlasesClient.setWorldMapZoomLevel(zoomLevelDim * MapAtlasesMod.CONFIG.worldMapIconScale);
        // a function of worldMapScaling, zoomLevel, and textureSize
        float mapTextureScale = (float)(atlasDataScaledSize/(128.0*zoomLevelDim));
        // Draw map background
        double y = (height / 2.0)-(atlasBgScaledSize/2.0);
        double x = (width / 2.0)-(atlasBgScaledSize/2.0);
        RenderSystem.setShaderTexture(0, ATLAS_BACKGROUND);
        drawTexture(
                matrices,
                (int) x,
                (int) y,
                0,
                0,
                atlasBgScaledSize,
                atlasBgScaledSize,
                atlasBgScaledSize,
                atlasBgScaledSize
        );
        // Draw dimension selectors
        drawDimensionSelectors(matrices, x, y, atlasBgScaledSize);
        // Draw MapIcons selectors
        if (atlas == null) {
            return;
        }
        Map<String, MapState> mapInfos = MapAtlasesAccessUtils.getAllMapInfoFromAtlas(client.world, atlas);
        String currentMapId = MapAtlasesClient.currentMapStateId == null
            ? this.centerMapId
            : MapAtlasesClient.currentMapStateId;
        drawMapIconSelectors(matrices, x, y, atlasBgScaledSize);
        MapState activeState = client.world.getMapState(currentMapId);
        int activeMapId = MapAtlasesAccessUtils.getMapIntFromString(currentMapId);
        // Draw maps, putting active map in middle of grid
        if (!currentIdsToCenters.containsKey(activeMapId)) {
            if (currentIdsToCenters.isEmpty()) {
                return;
            }
            activeMapId = currentIdsToCenters.keySet().stream()
                    .filter(stringListPair -> {
                        var state = client.world.getMapState(
                                MapAtlasesAccessUtils.getMapStringFromInt(stringListPair));
                        if (state == null) return false;
                        return !((MapStateIntrfc) state).getFullIcons().entrySet().stream()
                                .filter(e -> isMeaningfulMapIcon(e.getValue().getType(), false))
                                .collect(Collectors.toSet())
                                .isEmpty();
                    })
                    .findAny().orElseGet(() -> currentIdsToCenters.keySet().stream().findAny().orElse(-1));
            activeState = client.world.getMapState(MapAtlasesAccessUtils.getMapStringFromInt(activeMapId));
        }
        if (activeState == null) {
            return;
        }
        double mapTextX = x + drawnMapBufferSize;
        double mapTextY = y + drawnMapBufferSize;
        int atlasScale = (1 << activeState.scale) * 128;
        int activeXCenter = currentIdsToCenters.get(activeMapId).getSecond().get(0) +
                (round(mouseXOffset, PAN_BUCKET) / PAN_BUCKET * atlasScale * -1);
        int activeZCenter = currentIdsToCenters.get(activeMapId).getSecond().get(1) +
                (round(mouseYOffset, PAN_BUCKET) / PAN_BUCKET * atlasScale * -1);
        int centerScreenXCenter = -1;
        int centerScreenZCenter = -1;
        for (int i = zoomLevelDim-1; i >= 0; i--) {
            for (int j = zoomLevelDim-1; j >= 0; j--) {
                int iXIdx = i-(zoomLevelDim/2);
                int jYIdx = j-(zoomLevelDim/2);
                int reqXCenter = activeXCenter + (jYIdx * atlasScale);
                int reqZCenter = activeZCenter + (iXIdx * atlasScale);
                if (i == (zoomLevelDim / 2)  && j == (zoomLevelDim / 2)) {
                    centerScreenXCenter = reqXCenter;
                    centerScreenZCenter = reqZCenter;
                }
                var state = findMapEntryForCenters(
                        mapInfos, registryWorldSelected, reqXCenter, reqZCenter);
                if (state == null) { continue; }
                String stateDimStr = MapAtlasesAccessUtils.getMapStateDimKey(state.getValue());

                boolean drawPlayerIcons = stateDimStr.compareTo(initialWorldSelected) == 0;
                if (!mapContainsMeaningfulIcons(state.getValue(), false)) {
                    drawMap(matrices,i,j,state,activeMapId,mapTextX,mapTextY,mapTextureScale, drawPlayerIcons);
                }
            }
        }
        // draw maps without icons first
        // and then draw maps with icons (to avoid drawing over icons)
        for (int i = zoomLevelDim-1; i >= 0; i--) {
            for (int j = zoomLevelDim-1; j >= 0; j--) {
                int iXIdx = i-(zoomLevelDim/2);
                int jYIdx = j-(zoomLevelDim/2);
                int reqXCenter = activeXCenter + (jYIdx * atlasScale);
                int reqZCenter = activeZCenter + (iXIdx * atlasScale);
                var state = findMapEntryForCenters(
                        mapInfos, registryWorldSelected, reqXCenter, reqZCenter);
                if (state == null) { continue; }
                String stateDimStr = MapAtlasesAccessUtils.getMapStateDimKey(state.getValue());
                boolean drawPlayerIcons = stateDimStr.compareTo(initialWorldSelected) == 0;
                if (mapContainsMeaningfulIcons(state.getValue(), false)) {
                    drawMap(matrices,i,j,state,activeMapId,mapTextX,mapTextY,mapTextureScale, drawPlayerIcons);
                }
            }
        }
        // Draw foreground
        RenderSystem.setShaderTexture(0, ATLAS_FOREGROUND);
        drawTexture(
                matrices,
                (int) x,
                (int) y,
                0,
                0,
                atlasBgScaledSize,
                atlasBgScaledSize,
                atlasBgScaledSize,
                atlasBgScaledSize
        );
        // Draw dimension tooltip if necessary
        drawDimensionTooltip(matrices, x, y, atlasBgScaledSize);
        // Draw world map coords
        if (mouseX < x + drawnMapBufferSize || mouseY < y + drawnMapBufferSize
                || mouseX > x + atlasBgScaledSize - drawnMapBufferSize
                || mouseY > y + atlasBgScaledSize - drawnMapBufferSize)
            return;
        if (MapAtlasesMod.CONFIG == null || !MapAtlasesMod.CONFIG.drawWorldMapCoords) return;
        BlockPos cursorBlockPos = getBlockPosForCursor(
                mouseX,
                mouseY,
                atlasScale,
                zoomLevelDim,
                centerScreenXCenter,
                centerScreenZCenter,
                atlasBgScaledSize,
                x,
                y,
                drawnMapBufferSize
        );
        int targetHeight = atlasBgScaledSize + 4;
        if (MapAtlasesMod.CONFIG.forceWorldMapScaling >= 95) {
            targetHeight = 8;
        }
        float textScaling = MapAtlasesMod.CONFIG.worldMapCoordsScale;
        drawMapTextXZCoords(matrices, (int) x, (int) y, atlasBgScaledSize, targetHeight, textScaling, cursorBlockPos);
    }

    private List<Map.Entry<String, MapIcon>> getMapIconList() {
        var currentIdsToCenters = getCurrentIdsToCenters();
        var mapInfos = MapAtlasesAccessUtils.getAllMapInfoFromAtlas(client.world, atlas);
        // Map of <"MapStateId/MapIconId": MapIcon>
        Map<String, MapIcon> mapIcons = new HashMap<>();
        for (var e : currentIdsToCenters.entrySet()) {
            var centers = e.getValue().getSecond();
            var state = findMapEntryForCenters(
                    mapInfos, registryWorldSelected, centers.get(0), centers.get(1));
            if (state == null) continue;
            var fullIcons = ((MapStateIntrfc) state.getValue()).getFullIcons();
            var stateString = state.getKey();
            var nonPlayerIcons = fullIcons.entrySet().stream()
                    .filter(t -> isMeaningfulMapIcon(t.getValue().getType(), true))
                    .map(t -> new AbstractMap.SimpleEntry<>(stateString + "/" + t.getKey(), t.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            mapIcons.putAll(nonPlayerIcons);
        }
        return mapIcons.entrySet().stream().toList();
    }

    private void drawMapIconSelectors(MatrixStack matrices, double x, double y, int atlasBgScaledSize) {
        int scalingFactor = client.getWindow().getHeight() / client.getWindow().getScaledHeight();
        if (scalingFactor == 0) return;
        int rawWidth = 48;
        int scaledWidth = rawWidth / scalingFactor;
        var mapList = getMapIconList();
        for (int k = 0; k < mapList.size(); k++) {
            var key = mapList.get(k).getKey();
            var stateId = key.split("/")[0];
            var iconId = key.split("/")[1];
            var mapIcon = mapList.get(k).getValue();
            // Draw selector
            RenderSystem.setShaderTexture(0, PAGE_SELECTED);
            drawTextureFlippedX(
                    matrices,
                    (int) x - (int) (1.0/16 * atlasBgScaledSize),
                    (int) y + (int) (k * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize),
                    0,
                    0,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth
            );

            // Draw map Icon
            matrices.push();
            matrices.translate(
                    (int) x + (int) (1/16 * atlasBgScaledSize),
                    (int) y + (int) (k * (4/32.0 * atlasBgScaledSize)) + (int) (1.85/16.0 * atlasBgScaledSize),
                    -0.019999999552965164D
            );
            matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(
                    (float)(mapIcon.getRotation() * 360) / 16.0F));
            matrices.scale((0.5f / 16) * atlasBgScaledSize ,(0.5f / 16) * atlasBgScaledSize, 1);
            matrices.translate(-0.125D, 0.125D, -1.0D);
            byte b = mapIcon.getTypeId();
            float g = (float)(b % 16 + 0) / 16.0F;
            float h = (float)(b / 16 + 0) / 16.0F;
            float l = (float)(b % 16 + 1) / 16.0F;
            float m = (float)(b / 16 + 1) / 16.0F;
            Matrix4f matrix4f2 = matrices.peek().getPositionMatrix();
            int light = Integer.parseInt("F000F0", 16);
            VertexConsumerProvider.Immediate vcp = VertexConsumerProvider.immediate(Tessellator.getInstance().getBuffer());
            VertexConsumer vertexConsumer2 = vcp.getBuffer(RenderLayer.getText(MAP_ICON_TEXTURE));
            vertexConsumer2.vertex(matrix4f2, -1.0F, 1.0F, (float)k * -0.001F)
                    .color(255, 255, 255, 255).texture(g, h).light(light).next();
            vertexConsumer2.vertex(matrix4f2, 1.0F, 1.0F, (float)k * -0.001F)
                    .color(255, 255, 255, 255).texture(l, h).light(light).next();
            vertexConsumer2.vertex(matrix4f2, 1.0F, -1.0F, (float)k * -0.001F)
                    .color(255, 255, 255, 255).texture(l, m).light(light).next();
            vertexConsumer2.vertex(matrix4f2, -1.0F, -1.0F, (float)k * -0.001F)
                    .color(255, 255, 255, 255).texture(g, m).light(light).next();
            vcp.draw();
            matrices.pop();
            if (mapIcon.getText() != null && rawMouseXMoved >= (int) x - (int) (1.0/16 * atlasBgScaledSize)
                    && rawMouseXMoved <= (int) x - (int) (1.0/16 * atlasBgScaledSize) + scaledWidth
                    && rawMouseYMoved >= (int) y + (int) (k * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize)
                    && rawMouseYMoved <= (int) y + (int) (k * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize) + scaledWidth) {
                // draw text
                this.renderTooltip(matrices, mapIcon.getText(), (int) rawMouseXMoved, (int) rawMouseYMoved);
            }
        }
    }

    private void drawTextureFlippedX(MatrixStack matrices, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder bufferBuilder = Tessellator.getInstance().getBuffer();
        bufferBuilder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        float u0 = (u + (float)width) / (float)textureWidth;
        float u1 = (u + 0.0F) / (float)textureWidth;
        float v0 = (v + 0.0F) / (float)textureHeight;
        float v1 = (v + (float)height) / (float)textureHeight;
        bufferBuilder.vertex(matrices.peek().getPositionMatrix(), (float)x, (float)y + height, (float)0).texture(u0, v1).next();
        bufferBuilder.vertex(matrices.peek().getPositionMatrix(), (float)x + width, (float)y + height, (float)0).texture(u1, v1).next();
        bufferBuilder.vertex(matrices.peek().getPositionMatrix(), (float)x + width, (float)y, (float)0).texture(u1, v0).next();
        bufferBuilder.vertex(matrices.peek().getPositionMatrix(), (float)x, (float)y, (float)0).texture(u0, v0).next();
        BufferRenderer.drawWithShader(bufferBuilder.end());
    }

    private void drawDimensionTooltip(
            MatrixStack matrices,
            double x,
            double y,
            int atlasBgScaledSize
    ) {
        var dimensions =
                idsToCenters.values().stream().map(Pair::getFirst).collect(Collectors.toSet()).stream().toList();
        if (client == null) return;
        int scalingFactor = client.getWindow().getHeight() / client.getWindow().getScaledHeight();
        if (scalingFactor == 0) return;

        for (int i = 0; i < dimensions.size(); i++) {
            int rawWidth = 48;
            int scaledWidth = rawWidth / scalingFactor;
            if (rawMouseXMoved >= (x + (int) (29.5/32.0 * atlasBgScaledSize))
                    && rawMouseXMoved <= (x + (int) (29.5/32.0 * atlasBgScaledSize) + scaledWidth)
                    && rawMouseYMoved >= (y + (int) (i * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize))
                    && rawMouseYMoved <= (y + (int) (i * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize)) + scaledWidth) {
                Identifier dimRegistry = new Identifier(dimensions.get(i));
                String dimName;
                if (dimRegistry.getNamespace().compareTo("minecraft") == 0) {
                    dimName = dimRegistry.getPath().toString().replace("_", " ");
                } else {
                    dimName = dimRegistry.toString().toString().replace("_", " ").replace(":", " ");
                }
                char[] array = dimName.toCharArray();
                array[0] = Character.toUpperCase(array[0]);
                for (int j = 1; j < array.length; j++) {
                    if (Character.isWhitespace(array[j - 1])) {
                        array[j] = Character.toUpperCase(array[j]);
                    }
                }
                dimName = new String(array);
                this.renderTooltip(matrices, Text.of(dimName), (int) rawMouseXMoved, (int) rawMouseYMoved);
            }
        }
    }

    private void drawDimensionSelectors(
            MatrixStack matrices,
            double x,
            double y,
            int atlasBgScaledSize
    ) {
        var dimensions =
                idsToCenters.values().stream().map(Pair::getFirst).collect(Collectors.toSet()).stream().toList();
        if (client == null) return;
        int scalingFactor = client.getWindow().getHeight() / client.getWindow().getScaledHeight();
        if (scalingFactor == 0) return;
        for (int i = 0; i < dimensions.size(); i++) {
            int rawWidth = 48;
            int scaledWidth = rawWidth / scalingFactor;
            // Draw selector
            if (dimensions.get(i).compareTo(registryWorldSelected) == 0) {
                RenderSystem.setShaderTexture(0, PAGE_SELECTED);
            } else {
                RenderSystem.setShaderTexture(0, PAGE_UNSELECTED);
            }
            drawTexture(
                    matrices,
                    (int) x + (int) (29.5/32.0 * atlasBgScaledSize),
                    (int) y + (int) (i * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize),
                    0,
                    0,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth
            );
            // Draw Icon
            if (dimensions.get(i).compareTo("minecraft:overworld") == 0) {
                RenderSystem.setShaderTexture(0, PAGE_OVERWORLD);
            } else if (dimensions.get(i).compareTo("minecraft:the_nether") == 0) {
                RenderSystem.setShaderTexture(0, PAGE_NETHER);
            } else if (dimensions.get(i).compareTo("minecraft:the_end") == 0) {
                RenderSystem.setShaderTexture(0, PAGE_END);
            } else {
                RenderSystem.setShaderTexture(0, PAGE_OTHER);
            }
            rawWidth = 36;
            scaledWidth = rawWidth / scalingFactor;
            drawTexture(
                    matrices,
                    (int) x + (int) (30.0/32.0 * atlasBgScaledSize),
                    (int) y + (int) (i * (4/32.0 * atlasBgScaledSize)) + (int) (4.0/64.0 * atlasBgScaledSize),
                    0,
                    0,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth,
                    scaledWidth
            );
        }
    }

    private BlockPos getBlockPosForCursor(
            int mouseX,
            int mouseY,
            int atlasScale,
            int zoomLevelDim,
            int centerScreenXCenter,
            int centerScreenZCenter,
            int atlasBgScaledSize,
            double x,
            double y,
            double buffer
    ) {
        double atlasMapsRelativeMouseX = mapRangeValueToAnother(
                mouseX, x + buffer, x + atlasBgScaledSize - buffer, -1.0, 1.0);
        double atlasMapsRelativeMouseZ = mapRangeValueToAnother(
                mouseY, y + buffer, y + atlasBgScaledSize - buffer, -1.0, 1.0);
        return new BlockPos(
                Math.floor(atlasMapsRelativeMouseX * zoomLevelDim * (atlasScale / 2.0)) + centerScreenXCenter,
                255,
                Math.floor(atlasMapsRelativeMouseZ * zoomLevelDim * (atlasScale / 2.0)) + centerScreenZCenter);
    }

    private boolean mapContainsMeaningfulIcons(MapState state, boolean allPlayer) {
        return ((MapStateIntrfc) state).getFullIcons().values().stream()
                .anyMatch(i -> this.isMeaningfulMapIcon(i.getType(), allPlayer));
    }

    private boolean isMeaningfulMapIcon(MapIcon.Type type, boolean allPlayer) {
        return type != MapIcon.Type.PLAYER_OFF_MAP && type != MapIcon.Type.PLAYER_OFF_LIMITS
                && (!allPlayer || type != MapIcon.Type.PLAYER);
    }

    private Map.Entry<String, MapState> findMapEntryForCenters(
            Map<String, MapState> mapInfos,
            String reqDimension,
            int reqXCenter,
            int reqZCenter
    ) {
        // Get the map for the GUI idx
        return mapInfos.entrySet().stream()
                .filter(m ->
                        idsToCenters.containsKey(MapAtlasesAccessUtils.getMapIntFromString(m.getKey()))
                        && idsToCenters.get(MapAtlasesAccessUtils.getMapIntFromString(m.getKey())).getFirst().compareTo(reqDimension) == 0
                        && idsToCenters.get(MapAtlasesAccessUtils.getMapIntFromString(m.getKey())).getSecond().get(0) == reqXCenter
                        && idsToCenters.get(MapAtlasesAccessUtils.getMapIntFromString(m.getKey())).getSecond().get(1) == reqZCenter)
                .findFirst().orElse(null);
    }

    private void drawMap(
            MatrixStack matrices,
            int i,
            int j,
            Map.Entry<String, MapState> state,
            int activeMapId,
            double mapTextX,
            double mapTextY,
            float mapTextureScale,
            boolean drawPlayerIcons
        ) {
        if (state == null || client == null) return;
        // Draw the map
        double curMapTextX = mapTextX + (mapTextureScale * 128 * j);
        double curMapTextY = mapTextY + (mapTextureScale * 128 * i);
        VertexConsumerProvider.Immediate vcp;
        vcp = VertexConsumerProvider.immediate(Tessellator.getInstance().getBuffer());
        matrices.push();
        matrices.translate(curMapTextX, curMapTextY, 0.0);
        matrices.scale(mapTextureScale, mapTextureScale, -1);
        // Remove the off-map player icons temporarily during render
        Iterator<Map.Entry<String, MapIcon>> it = ((MapStateIntrfc) state.getValue())
                .getFullIcons().entrySet().iterator();
        List<Map.Entry<String, MapIcon>> removed = new ArrayList<>();
        if (state.getKey().compareTo(FilledMapItem.getMapName(activeMapId)) != 0
                || registryWorldSelected.compareTo(initialWorldSelected) != 0) {
            // Only remove the off-map icon if it's not the active map or its not the active dimension
            while (it.hasNext()) {
                Map.Entry<String, MapIcon> e = it.next();
                if (!isMeaningfulMapIcon(e.getValue().getType(), !drawPlayerIcons)) {
                    it.remove();
                    removed.add(e);
                }
            }
        }
        client.gameRenderer.getMapRenderer()
                .draw(
                        matrices,
                        vcp,
                        activeMapId,
                        state.getValue(),
                        false,
                        Integer.parseInt("F000F0", 16)
                );
        vcp.draw();
        matrices.pop();
        // Re-add the off-map player icons after render
        for (Map.Entry<String, MapIcon> e : removed) {
            ((MapStateIntrfc) state.getValue()).getFullIcons().put(e.getKey(), e.getValue());
        }
    }

    public static void drawMapTextXZCoords(
            MatrixStack matrices,
            int x,
            int y,
            int originOffsetWidth,
            int originOffsetHeight,
            float textScaling,
            BlockPos blockPos
    ) {
        String coordsToDisplay = "X: "+blockPos.getX()+", Z: "+blockPos.getZ();
        MapAtlasesHUD.drawScaledText(
                matrices, x, y, coordsToDisplay, textScaling, originOffsetWidth, originOffsetHeight);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0) {
            mouseXOffset += deltaX;
            mouseYOffset += deltaY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        zoomValue += -1 * amount;
        zoomValue = Math.max(zoomValue, -1 * ZOOM_BUCKET);
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        rawMouseXMoved = mouseX;
        rawMouseYMoved = mouseY;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (client == null || client.player == null) return false;
        if (button == 0) {
            var dims =
                    idsToCenters.values().stream().map(Pair::getFirst).collect(Collectors.toSet()).stream().toList();
            int atlasBgScaledSize = getAtlasBgScaledSize();
            double x = (width / 2.0)-(atlasBgScaledSize/2.0);
            double y = (height / 2.0)-(atlasBgScaledSize/2.0);
            int scalingFactor = client.getWindow().getHeight() / client.getWindow().getScaledHeight();
            int rawWidth = 48;
            int scaledWidth = rawWidth / scalingFactor;
            for (int i = 0; i < dims.size(); i++) {
                int targetX = (int) x + (int) (29.5/32.0 * atlasBgScaledSize);
                int targetY = (int) y +
                        (int) (i * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize);
                if (mouseX >= targetX && mouseX <= targetX + scaledWidth
                    && mouseY >= targetY && mouseY <= targetY + scaledWidth) {
                    registryWorldSelected = dims.get(i);
                    // Reset zoom
                    mouseXOffset = 0;
                    mouseYOffset = 0;
                    zoomValue = ZOOM_BUCKET;
                    // Play sound
                    client.player.playSound(
                            MapAtlasesMod.ATLAS_PAGE_TURN_SOUND_EVENT, SoundCategory.PLAYERS, 1.0F, 1.0F);
                }
            }
            var mapList = getMapIconList();
            for (int k = 0; k < mapList.size(); k++) {
                int targetX = (int) x - (int) (1.0/16 * atlasBgScaledSize);
                int targetY = (int) y + (int) (k * (4/32.0 * atlasBgScaledSize)) + (int) (1.0/16.0 * atlasBgScaledSize);
                if (mouseX >= targetX && mouseX <= targetX + scaledWidth
                        && mouseY >= targetY && mouseY <= targetY + scaledWidth) {
                    // Set mouse
                    var key = mapList.get(k).getKey();
                    var currentIds = getCurrentIdsToCenters();
                    var stateId = MapAtlasesAccessUtils.getMapIntFromString(key.split("/")[0]);
                    var state = currentIds.get(stateId).getSecond();
                    var centerStateId = MapAtlasesAccessUtils.getMapIntFromString(centerMapId);
                    var centerState = currentIds.get(centerStateId).getSecond();
                    mouseXOffset = (int) ((-1.0 / 4) * (state.get(0) - centerState.get(0)));
                    mouseYOffset = (int) ((-1.0 / 4) * (state.get(1) - centerState.get(1)));
                    // Play sound
                    client.player.playSound(
                            MapAtlasesMod.ATLAS_PAGE_TURN_SOUND_EVENT, SoundCategory.PLAYERS, 1.0F, 1.0F);
                }
            }
        }
        return true;
    }

    private double mapRangeValueToAnother(
            double input, double inputStart, double inputEnd, double outputStart, double outputEnd) {
        double slope = (outputEnd - outputStart) / (inputEnd - inputStart);
        return outputStart + slope * (input - inputStart);
    }

    private int round(int num, int mod) {
        int t = num % mod;
        if (t < (int) Math.floor(mod / 2.0))
            return num - t;
        else
            return num + mod - t;
    }
}
