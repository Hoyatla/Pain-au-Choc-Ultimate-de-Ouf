package pauc.pain_au_choc;

import pauc.pain_au_choc.mixin.ParticleEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

public final class ParticleBudgetController {
    private static final int UNLIMITED_SENTINEL = 260;
    private static final int DEFAULT_TARGET_FPS = 120;
    private static final int UPDATE_INTERVAL_TICKS = 4;
    private static final float FPS_SMOOTHING = 0.18F;
    private static final float BUDGET_SMOOTHING = 0.28F;
    private static final float LOAD_MIN = 0.40F;
    private static final float LOAD_MAX = 0.95F;
    private static final int MIN_PARTICLE_BUDGET = 200;
    private static final int MAX_PARTICLE_BUDGET = 3000;
    private static final int SOFT_CAP_BAND = 64;
    private static final double CURVE_EXPONENT = 1.60D;

    private static float smoothedFps;
    private static float smoothedBudget = MAX_PARTICLE_BUDGET;
    private static int currentBudget = MAX_PARTICLE_BUDGET;
    private static int tickCounter;

    private ParticleBudgetController() {
    }

    public static void onClientTick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        tickCounter++;

        int fps = minecraft.getFps();
        if (fps > 0) {
            if (smoothedFps <= 0.0F) {
                smoothedFps = fps;
            } else {
                smoothedFps += (fps - smoothedFps) * FPS_SMOOTHING;
            }
        }

        if (tickCounter % UPDATE_INTERVAL_TICKS != 0 || smoothedFps <= 0.0F) {
            return;
        }

        float estimatedGpuLoad = estimateGpuLoad(minecraft, smoothedFps);
        int targetBudget = mapGpuLoadToParticleBudget(estimatedGpuLoad);
        smoothedBudget += (targetBudget - smoothedBudget) * BUDGET_SMOOTHING;
        currentBudget = clampBudget(Math.round(smoothedBudget));
    }

    public static boolean shouldAcceptParticle(ParticleEngine engine, Particle particle) {
        if (!PauCClient.isBudgetActive()) {
            return true;
        }

        int totalParticles = countParticles(engine);
        if (totalParticles < currentBudget) {
            return true;
        }

        int hardDropThreshold = currentBudget + SOFT_CAP_BAND;
        if (totalParticles >= hardDropThreshold) {
            return isPriorityParticle(particle) && totalParticles < hardDropThreshold + 24;
        }

        float overflow = (float) (totalParticles - currentBudget) / (float) SOFT_CAP_BAND;
        float acceptanceChance = 1.0F - overflow;
        if (isPriorityParticle(particle)) {
            acceptanceChance = Math.min(1.0F, acceptanceChance + 0.20F);
        }
        return ThreadLocalRandom.current().nextFloat() <= acceptanceChance;
    }

    public static void reset() {
        smoothedFps = 0.0F;
        smoothedBudget = MAX_PARTICLE_BUDGET;
        currentBudget = MAX_PARTICLE_BUDGET;
        tickCounter = 0;
    }

    private static float estimateGpuLoad(Minecraft minecraft, float observedFps) {
        float targetFps = resolveTargetFps(minecraft);
        float baselineLoad = GpuHeadroomController.getTargetGpuLoad();
        float fpsPressure = targetFps / Math.max(1.0F, observedFps);
        float latencyPressure = 1.0F + (LatencyController.getPressureLevel() * 0.08F);
        float bottleneckMultiplier = BottleneckController.isGpuBound() ? 1.12F : BottleneckController.isCpuBound() ? 0.86F : 1.0F;
        float estimatedLoad = baselineLoad * fpsPressure * latencyPressure * bottleneckMultiplier;
        return clampLoad(estimatedLoad);
    }

    private static float resolveTargetFps(Minecraft minecraft) {
        if (minecraft.options == null) {
            return DEFAULT_TARGET_FPS;
        }

        int configuredCap = minecraft.options.framerateLimit().get();
        if (configuredCap > 0 && configuredCap < UNLIMITED_SENTINEL) {
            return Math.max(30, AdaptiveFrameCapController.getLatencyReferenceCap(configuredCap));
        }
        return DEFAULT_TARGET_FPS;
    }

    private static int mapGpuLoadToParticleBudget(float gpuLoad) {
        float clampedLoad = clampLoad(gpuLoad);
        float normalized = (LOAD_MAX - clampedLoad) / (LOAD_MAX - LOAD_MIN);
        double curved = Math.pow(Math.max(0.0F, Math.min(1.0F, normalized)), CURVE_EXPONENT);
        return clampBudget(MIN_PARTICLE_BUDGET + (int) Math.round((MAX_PARTICLE_BUDGET - MIN_PARTICLE_BUDGET) * curved));
    }

    private static int countParticles(ParticleEngine engine) {
        if (!(engine instanceof ParticleEngineAccessor accessor)) {
            return 0;
        }

        int total = 0;
        Map<ParticleRenderType, Queue<Particle>> liveParticles = accessor.pauc$getParticles();
        if (liveParticles != null) {
            Collection<Queue<Particle>> values = liveParticles.values();
            for (Queue<Particle> queue : values) {
                if (queue != null) {
                    total += queue.size();
                }
            }
        }

        Queue<Particle> pending = accessor.pauc$getParticlesToAdd();
        if (pending != null) {
            total += pending.size();
        }
        return Math.max(0, total);
    }

    private static boolean isPriorityParticle(Particle particle) {
        if (particle == null) {
            return false;
        }

        String name = particle.getClass().getSimpleName();
        return name.contains("Explosion")
                || name.contains("Damage")
                || name.contains("Crit")
                || name.contains("Sweep")
                || name.contains("Totem");
    }

    private static float clampLoad(float value) {
        return Math.max(LOAD_MIN, Math.min(0.98F, value));
    }

    private static int clampBudget(int value) {
        return Math.max(MIN_PARTICLE_BUDGET, Math.min(MAX_PARTICLE_BUDGET, value));
    }
}

