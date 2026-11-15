package io.linkmc.server;

import com.infernalsuite.asp.api.utils.NibbleArray;
import com.infernalsuite.asp.api.world.SlimeChunk;
import com.infernalsuite.asp.api.world.SlimeChunkSection;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.skeleton.SlimeChunkSectionSkeleton;
import com.infernalsuite.asp.skeleton.SlimeChunkSkeleton;
import com.infernalsuite.asp.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class LinkWorldExportTestSuite {
    @TempDir Path folder;

    private static CompoundBinaryTag palette(String name) {
        return CompoundBinaryTag.builder().put("palette", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,
            List.of(CompoundBinaryTag.builder().putString("Name", name).build()))).build();
    }

    private static SlimeChunk chunk(int x, int z, boolean entity) {
        var biomes = CompoundBinaryTag.builder().put("palette", ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,
            List.of(net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("minecraft:plains")))).build();
        var light = new NibbleArray(new byte[2048]);
        SlimeChunkSection[] sections = {new SlimeChunkSectionSkeleton(palette("minecraft:stone"), biomes, light, light)};
        var chest = CompoundBinaryTag.builder().putString("id", "minecraft:chest").putInt("x", x * 16).putInt("y", -60).putInt("z", z * 16).build();
        var stand = CompoundBinaryTag.builder().putString("id", "minecraft:armor_stand").build();
        Map<String, net.kyori.adventure.nbt.BinaryTag> extra = new HashMap<>(Map.of("ChunkBukkitValues", CompoundBinaryTag.builder().putString("probe:value", "kept").build()));
        return new SlimeChunkSkeleton(x, z, sections, CompoundBinaryTag.empty(), List.of(chest), entity ? List.of(stand) : List.of(), extra, null, null, null, null);
    }

    @Test void chunksEntitiesAndChunkPdcBecomeVanillaRegionData() throws Exception {
        var chunks = new Long2ObjectOpenHashMap<SlimeChunk>();
        chunks.put(Util.chunkPosition(0, -2), chunk(0, -2, true));
        chunks.put(Util.chunkPosition(40, 3), chunk(40, 3, false));
        var props = new SlimePropertyMap(); props.setValue(SlimeProperties.CHUNK_SECTION_MIN, -4);
        var world = new SkeletonSlimeWorld("island_probe", null, true, chunks, new ConcurrentHashMap<>(), props, 4671);
        var dimension = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("island_probe"));

        assertEquals(2, LinkWorldExport.writeRegions(world, "world", dimension, folder));

        try (var region = new RegionFileStorage(new RegionStorageInfo("world", dimension, "chunk"), folder.resolve("region"), true);
             var entities = new RegionFileStorage(new RegionStorageInfo("world", dimension, "entities"), folder.resolve("entities"), true)) {
            CompoundTag tag = region.read(new ChunkPos(0, -2));
            assertNotNull(tag);
            assertEquals(4671, tag.getIntOr("DataVersion", 0));
            assertEquals(0, tag.getIntOr("xPos", 1)); assertEquals(-2, tag.getIntOr("zPos", 0)); assertEquals(-4, tag.getIntOr("yPos", 0));
            assertEquals("minecraft:full", tag.getStringOr("Status", ""));
            var section = tag.getListOrEmpty("sections").getCompoundOrEmpty(0);
            assertEquals(-4, section.getByteOr("Y", (byte) 0));
            assertEquals("minecraft:stone", section.getCompoundOrEmpty("block_states").getListOrEmpty("palette").getCompoundOrEmpty(0).getStringOr("Name", ""));
            assertEquals(2048, section.getByteArray("SkyLight").orElseThrow().length);
            assertEquals(1, tag.getListOrEmpty("block_entities").size());
            assertEquals("kept", tag.getCompoundOrEmpty("ChunkBukkitValues").getStringOr("probe:value", ""));
            assertNotNull(region.read(new ChunkPos(40, 3)), "chunk in another region file");

            CompoundTag entityChunk = entities.read(new ChunkPos(0, -2));
            assertNotNull(entityChunk);
            assertArrayEquals(new int[]{0, -2}, entityChunk.getIntArray("Position").orElseThrow());
            assertEquals("minecraft:armor_stand", entityChunk.getListOrEmpty("Entities").getCompoundOrEmpty(0).getStringOr("id", ""));
            assertNull(entities.read(new ChunkPos(40, 3)), "no entity file entry for a chunk without entities");
        }
    }
}
