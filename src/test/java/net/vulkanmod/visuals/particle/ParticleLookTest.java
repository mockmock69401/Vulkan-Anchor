package net.vulkanmod.visuals.particle;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11 (shader roadmap session 13): the particle look groups and the encoding particle.vsh / particle.fsh mirror. */
class ParticleLookTest {
    @Test
    void groupsCoverThePixelEffects() {
        assertEquals(VanillaParticles.Group.SMOKE, VanillaParticles.of("minecraft:campfire_cosy_smoke").group());
        assertEquals(VanillaParticles.Shape.PUFF, VanillaParticles.of("large_smoke").shape());
        assertEquals(VanillaParticles.Group.FLAMES, VanillaParticles.of("minecraft:soul_fire_flame").group());
        assertEquals(VanillaParticles.Group.EMBERS, VanillaParticles.of("minecraft:lava").group());
        assertEquals(VanillaParticles.Shape.BUBBLE, VanillaParticles.of("bubble_column_up").shape());
        assertEquals(VanillaParticles.Group.MAGIC, VanillaParticles.of("enchant").group());
        assertEquals(VanillaParticles.Group.AMBIENT, VanillaParticles.of("minecraft:spore_blossom_air").group());
        assertEquals(VanillaParticles.Shape.PETAL, VanillaParticles.of("cherry_leaves").shape());
        assertEquals(VanillaParticles.Group.COMBAT, VanillaParticles.of("crit").group());
        //Symbols keep their sprite (size, amount and colour only)
        assertEquals(VanillaParticles.Shape.SPRITE, VanillaParticles.of("heart").shape());
        assertEquals(VanillaParticles.Shape.SPRITE, VanillaParticles.of("note").shape());
    }

    @Test
    void notReachedStayVanilla() {
        //Block and item fragments, rain (the shader weather's), the emitters, another namespace
        for (String id : new String[]{"block", "item", "item_slime", "rain", "explosion_emitter", "elder_guardian", "vibration", "sonic_boom",
                "shriek", "block_marker", "othermod:smoke"}) {
            assertNull(VanillaParticles.of(id), id);
            assertEquals(0, VanillaParticles.tag(id), id);
        }
    }

    @Test
    void tagRoundTrips() {
        for (Map.Entry<String, VanillaParticles.Member> e : VanillaParticles.members().entrySet()) {
            int tag = VanillaParticles.tag(e.getKey());
            assertNotEquals(0, tag);
            assertEquals(e.getValue().group(), VanillaParticles.group(tag), e.getKey());
            assertEquals(e.getValue().shape(), VanillaParticles.shape(tag), e.getKey());
        }
        assertNull(VanillaParticles.group(0));
    }

    @Test
    void shapeIdsFitTheShaderBits() {
        //particle.fsh: 0 is the sprite, 1..9 the shapes, 4 bits
        assertEquals(0, VanillaParticles.Shape.SPRITE.ordinal());
        assertEquals(1, VanillaParticles.Shape.PUFF.ordinal());
        assertEquals(9, VanillaParticles.Shape.GLINT.ordinal());
        assertTrue(VanillaParticles.Shape.values().length <= 16);
        //particle.fsh: groups 1..7 in ParticleTints (1..4) and ParticleTints2 (5..8)
        assertTrue(VanillaParticles.Group.values().length <= 8);
    }

    @Test
    void lightWithoutFlagsIsUnchanged() {
        //LightTexture.pack: block << 4, sky << 20; full bright 0xF000F0
        for (int block = 0; block <= 15; block++) {
            for (int sky = 0; sky <= 15; sky++) {
                int light = block << 4 | sky << 20;
                assertEquals(light, ParticleLookMath.light(light, 0, 0));
                int flagged = ParticleLookMath.light(light, 7, 5);
                assertEquals(light, ParticleLookMath.lightLevels(flagged));
                assertEquals(7, ParticleLookMath.shapeOf(flagged));
                assertEquals(5, ParticleLookMath.groupOf(flagged));
                //each half stays a positive short (the shader reads UV2 as two signed shorts)
                assertTrue((flagged & 0xFFFF) < 0x8000 && (flagged >>> 16) < 0x8000);
            }
        }
    }

    @Test
    void spritesStayInTheAtlas() {
        for (VanillaParticles.Shape shape : VanillaParticles.Shape.values()) {
            if (shape == VanillaParticles.Shape.SPRITE) continue;
            for (int seed : new int[]{0, 1, 17, -5, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                for (float age = 0; age < 200; age += 0.37f) {
                    int tile = ParticleSprites.tile(shape.ordinal(), seed, age);
                    assertTrue(tile >= 0 && tile < ParticleSprites.COLS * ParticleSprites.ATLAS_HEIGHT / ParticleSprites.TILE, shape + " " + tile);
                    float[] uv = ParticleSprites.uv(tile);
                    assertTrue(uv[0] >= 0 && uv[1] <= 1 && uv[2] >= 0 && uv[3] <= 1 && uv[0] < uv[1] && uv[2] < uv[3]);
                }
            }
        }
        //The flame plays its flipbook (rows 0..3) as the particle ages, and loops
        int flame = VanillaParticles.Shape.FLAME.ordinal();
        java.util.Set<Integer> frames = new java.util.HashSet<>();
        for (float age = 0; age < 20f * ParticleSprites.FLAME_FRAMES / ParticleSprites.FLAME_FPS; age += 0.25f) frames.add(ParticleSprites.tile(flame, 3, age));
        assertEquals(ParticleSprites.FLAME_FRAMES, frames.size());
        assertTrue(frames.stream().allMatch(t -> t < ParticleSprites.FLAME_FRAMES));
        //Light is added; smoke, drops and petals cover
        assertTrue(ParticleSprites.additive(flame));
        assertTrue(ParticleSprites.additive(VanillaParticles.Shape.ORB.ordinal()));
        assertFalse(ParticleSprites.additive(VanillaParticles.Shape.PUFF.ordinal()));
        assertFalse(ParticleSprites.additive(VanillaParticles.Shape.DROP.ordinal()));
        assertFalse(ParticleSprites.additive(0));
    }

    @Test
    void theAtlasIsBakedAtItsSize() throws Exception {
        try (var in = ParticleLookTest.class.getResourceAsStream("/assets/vulkanmod/visuals/textures/particles/particle_atlas.png")) {
            assertNotNull(in, "particle_atlas.png");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
            assertEquals(ParticleSprites.ATLAS_WIDTH, image.getWidth());
            assertEquals(ParticleSprites.ATLAS_HEIGHT, image.getHeight());
            //Every tile has something in it, and its border (the bake's padding) is clear
            for (int t = 0; t < ParticleSprites.COLS * ParticleSprites.ATLAS_HEIGHT / ParticleSprites.TILE; t++) {
                int x0 = t % ParticleSprites.COLS * ParticleSprites.TILE, y0 = t / ParticleSprites.COLS * ParticleSprites.TILE;
                long alpha = 0;
                for (int y = 0; y < ParticleSprites.TILE; y += 2) for (int x = 0; x < ParticleSprites.TILE; x += 2) alpha += image.getRGB(x0 + x, y0 + y) >>> 24;
                assertTrue(alpha > 0, "tile " + t);
                assertEquals(0, image.getRGB(x0, y0) >>> 24, "tile " + t + " corner");
            }
        }
    }

    @Test
    void amountCopies() {
        assertEquals(1, ParticleLookMath.copies(100, 0.99f));
        assertEquals(0, ParticleLookMath.copies(0, 0f));
        assertEquals(2, ParticleLookMath.copies(200, 0.5f));
        assertEquals(2, ParticleLookMath.copies(900, 0.5f));
        assertEquals(1, ParticleLookMath.copies(50, 0.2f));
        assertEquals(0, ParticleLookMath.copies(50, 0.7f));
        assertEquals(2, ParticleLookMath.copies(150, 0.49f));
        assertEquals(1, ParticleLookMath.copies(150, 0.51f));
        //the mean is the amount
        java.util.Random r = new java.util.Random(13);
        for (int a : new int[]{25, 75, 125, 175}) {
            long n = 0;
            int trials = 200000;
            for (int i = 0; i < trials; i++) n += ParticleLookMath.copies(a, r.nextFloat());
            assertEquals(a / 100.0, n / (double) trials, 0.01, "amount " + a);
        }
    }

    @Test
    void sizeAndRealisticSize() {
        assertEquals(1f, ParticleLookMath.sizeScale(100), 0f);
        assertEquals(0.25f, ParticleLookMath.sizeScale(5), 0f);
        assertEquals(3f, ParticleLookMath.sizeScale(900), 0f);
        //A sprite that covers as much as its shape keeps its size; a sparse frame shrinks, a full one grows (bounded)
        int puff = VanillaParticles.Shape.PUFF.ordinal(), flame = VanillaParticles.Shape.FLAME.ordinal();
        float p = ParticleLookMath.SHAPE_SCALE[puff];
        assertEquals(0.2f * p, ParticleLookMath.realisticSize(0.2f, ParticleLookMath.PUFF_COVER, puff), 1e-6f);
        assertTrue(ParticleLookMath.realisticSize(0.2f, 0.1f, puff) < 0.2f * p);
        assertTrue(ParticleLookMath.realisticSize(0.2f, 0.9f, puff) > 0.2f * p);
        assertEquals(0.2f * p * ParticleLookMath.MIN_GROWTH, ParticleLookMath.realisticSize(0.2f, 0.001f, puff), 1e-6f);
        assertEquals(0.2f * p, ParticleLookMath.realisticSize(0.2f, 0f, puff), 1e-6f);
        //Other shapes: a fixed scale, whatever the frame covers
        assertEquals(0.2f * ParticleLookMath.SHAPE_SCALE[flame], ParticleLookMath.realisticSize(0.2f, 0.05f, flame), 1e-6f);
        assertEquals(VanillaParticles.Shape.values().length, ParticleLookMath.SHAPE_SCALE.length);
    }
}
