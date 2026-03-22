package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;

public final class IntegratedServerLoadController {
    private static final float MSPT_SMOOTHING = 0.18F;
    private static final float SOFT_PRESSURE_MSPT = 40.0F;
    private static final float HARD_PRESSURE_MSPT = 50.0F;
    private static final float EXTREME_PRESSURE_MSPT = 58.0F;
    private static final float SPIKE_MSPT_THRESHOLD = 62.0F;
    private static final float RECOVERY_MSPT = 35.0F;
    private static final int RECOVERY_SAMPLES_REQUIRED = 25;
    private static final int SPIKE_STREAK_FOR_EMERGENCY = 4;
    private static final int EMERGENCY_HOLD_TICKS = 120;

    private static int pressureLevel;
    private static int recoverySamples;
    private static int serverTick;
    private static int integratedServerIdentity;
    private static int spikeStreak;
    private static int emergencyHoldTicks;
    private static float smoothedMspt;

    private IntegratedServerLoadController() {
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer == null || event.getServer() != integratedServer) {
            reset();
            return;
        }

        int levelIdentity = System.identityHashCode(integratedServer);
        if (integratedServerIdentity != levelIdentity) {
            reset();
            integratedServerIdentity = levelIdentity;
        }

        serverTick = integratedServer.getTickCount();
        float averageTickTimeMs = integratedServer.getAverageTickTime();
        if (averageTickTimeMs <= 0.0F) {
            return;
        }

        if (smoothedMspt <= 0.0F) {
            smoothedMspt = averageTickTimeMs;
        } else {
            smoothedMspt += (averageTickTimeMs - smoothedMspt) * MSPT_SMOOTHING;
        }

        updatePressure(smoothedMspt);
        updateEmergencyMitigation(averageTickTimeMs);
    }

    public static int getPressureLevel() {
        return pressureLevel;
    }

    public static int getServerTick() {
        return serverTick;
    }

    public static float getSmoothedMspt() {
        return smoothedMspt;
    }

    public static int getMitigationTier() {
        if (emergencyHoldTicks > 0) {
            return 3;
        }
        if (pressureLevel >= 2 && smoothedMspt >= HARD_PRESSURE_MSPT - 2.0F) {
            return 2;
        }
        return pressureLevel;
    }

    public static boolean isEmergencyMitigationActive() {
        return emergencyHoldTicks > 0;
    }

    public static int getEmergencyHoldTicks() {
        return emergencyHoldTicks;
    }

    public static String getStatusLine() {
        return "MSPT=" + String.format("%.1f", smoothedMspt)
                + " pressure=" + pressureLevel
                + " tier=" + getMitigationTier()
                + " emergency=" + emergencyHoldTicks;
    }

    public static boolean isActiveFor(ServerLevel level) {
        if (!PauCClient.isBudgetActive() || level == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer integratedServer = minecraft.getSingleplayerServer();
        return integratedServer != null && level.getServer() == integratedServer;
    }

    public static void reset() {
        pressureLevel = 0;
        recoverySamples = 0;
        serverTick = 0;
        integratedServerIdentity = 0;
        spikeStreak = 0;
        emergencyHoldTicks = 0;
        smoothedMspt = 0.0F;
    }

    private static void updatePressure(float mspt) {
        int targetPressure;
        if (mspt >= EXTREME_PRESSURE_MSPT) {
            targetPressure = 3;
        } else if (mspt >= HARD_PRESSURE_MSPT) {
            targetPressure = 2;
        } else if (mspt >= SOFT_PRESSURE_MSPT) {
            targetPressure = 1;
        } else {
            targetPressure = 0;
        }

        if (targetPressure > pressureLevel) {
            pressureLevel = targetPressure;
            recoverySamples = 0;
            return;
        }

        if (targetPressure >= pressureLevel) {
            recoverySamples = 0;
            return;
        }

        if (mspt > RECOVERY_MSPT) {
            recoverySamples = 0;
            return;
        }

        recoverySamples++;
        if (recoverySamples >= RECOVERY_SAMPLES_REQUIRED) {
            pressureLevel = Math.max(targetPressure, pressureLevel - 1);
            recoverySamples = 0;
        }
    }

    private static void updateEmergencyMitigation(float currentMspt) {
        if (currentMspt >= SPIKE_MSPT_THRESHOLD) {
            spikeStreak = Math.min(64, spikeStreak + 1);
        } else if (spikeStreak > 0) {
            spikeStreak--;
        }

        if (spikeStreak >= SPIKE_STREAK_FOR_EMERGENCY) {
            emergencyHoldTicks = EMERGENCY_HOLD_TICKS;
            spikeStreak = SPIKE_STREAK_FOR_EMERGENCY;
        }

        if (emergencyHoldTicks <= 0) {
            return;
        }

        int recoveryRate = (smoothedMspt <= RECOVERY_MSPT && pressureLevel <= 0) ? 2 : 1;
        emergencyHoldTicks = Math.max(0, emergencyHoldTicks - recoveryRate);
    }
}
