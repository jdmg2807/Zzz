package com.example.voxelmod;

import com.arena.voxel.api.BlockDef;
import com.arena.voxel.api.ModContext;
import com.arena.voxel.api.VoxelMod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Demo mod: adds two blocks (one with a PNG texture shipped inside the jar), draws an overlay
 * with the coordinates and builds a small platform when a world finishes loading.
 *
 * Build it with:  ./gradlew :examplemod:jar
 * The jar ends up in examplemod/build/libs/examplemod.jar and can be imported from the
 * "Mods" screen of the game (or copied to Android/data/com.arena.voxel/files/mods).
 */
public class ExampleMod implements VoxelMod {

    private ModContext ctx;
    private float timer = 0f;
    private boolean built = false;

    @Override
    public void onLoad(ModContext context) {
        this.ctx = context;

        BlockDef lamp = new BlockDef("example_lamp")
                .color(0xFFD966, 0xE8B23A, 0xB07A20)
                .solid(true)
                .opaque(true);
        try (InputStream in = getClass().getResourceAsStream("/assets/example_lamp.png")) {
            if (in != null) {
                context.registerTexture("example_lamp", readFully(in));
                lamp.texture("example_lamp");
            }
        } catch (Exception e) {
            context.getLogger().warning("No se pudo cargar la textura: " + e.getMessage());
        }
        context.registerBlock(lamp);

        context.registerBlock(new BlockDef("example_crystal")
                .color(0x8FE9FF, 0x4FC3E8, 0x2A7C9E));

        context.chat().broadcast("ExampleMod v1.0 cargado. Nuevos bloques: example_lamp, example_crystal");
    }

    @Override
    public void onWorldReady() {
        ctx.chat().broadcast("ExampleMod: mundo listo, construyendo plataforma...");
        if (built) return;
        try {
            double px = ctx.player().getX();
            double py = ctx.player().getY();
            double pz = ctx.player().getZ();
            int x = (int) Math.round(px);
            int y = (int) Math.round(py);
            int z = (int) Math.round(pz);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int top = ctx.world().getHighestBlockY(x + dx, z + dz);
                    ctx.world().setBlock(x + dx, top + 1, z + dz, "example_crystal");
                }
            }
            ctx.world().setBlock(x, y + 1, z, "example_lamp");
            built = true;
        } catch (Exception e) {
            ctx.getLogger().warning("No se pudo construir la plataforma: " + e.getMessage());
        }
    }

    @Override
    public void onTick(float dt) {
        timer += dt;
        if (timer < 0.5f) return;
        timer = 0f;
        long seconds = Math.round(ctx.world().getTime());
        ctx.player().setHudText(
                "ExampleMod · X " + Math.round(ctx.player().getX()) +
                        " Y " + Math.round(ctx.player().getY()) +
                        " Z " + Math.round(ctx.player().getZ()) +
                        " · " + seconds + "s"
        );
    }

    @Override
    public void onBlockPlaced(int x, int y, int z, String blockId) {
        if (blockId.startsWith("example_")) {
            ctx.chat().toast("Colocaste " + blockId + " en " + x + ", " + y + ", " + z);
        }
    }

    @Override
    public void onBlockBroken(int x, int y, int z, String blockId) {
        if (blockId.startsWith("example_")) {
            ctx.chat().toast("Rompiendo " + blockId);
        }
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
