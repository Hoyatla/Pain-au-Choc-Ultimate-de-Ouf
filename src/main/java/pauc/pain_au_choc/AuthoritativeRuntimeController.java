package pauc.pain_au_choc;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AuthoritativeRuntimeController {
    private static final Map<String, ModAuthorityPolicy> KNOWN_POLICIES = createKnownPolicies();

    private static final EnumSet<AuthorityDomain> contestedDomains = EnumSet.noneOf(AuthorityDomain.class);
    private static final EnumSet<AuthorityDomain> highRiskDomains = EnumSet.noneOf(AuthorityDomain.class);
    private static final ArrayList<String> delegatedBackends = new ArrayList<>();
    private static final ArrayList<String> passiveMods = new ArrayList<>();
    private static final ArrayList<String> contestedMods = new ArrayList<>();
    private static final ArrayList<String> highRiskMods = new ArrayList<>();

    private static boolean initialized;
    private static boolean logged;
    private static boolean shaderPipelineContested;
    private static boolean chunkAuthorityContested;
    private static boolean renderAuthorityContested;
    private static boolean capturePipelineContested;
    private static boolean worldgenRiskPresent;
    private static int runtimePressureBias;
    private static AuthoritativeRuntimeStatus status = AuthoritativeRuntimeStatus.SOVEREIGN;

    private AuthoritativeRuntimeController() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        rescanLoadedStack();
        updateRuntimeState();
        logAuthorityReport();
    }

    public static void onClientTick() {
        if (!initialized) {
            initialize();
        }

        if (!PauCClient.isBudgetActive()) {
            resetRuntimeState();
            return;
        }

        updateRuntimeState();
    }

    public static void resetRuntimeState() {
        runtimePressureBias = 0;
        status = contestedMods.isEmpty() && highRiskMods.isEmpty()
                ? AuthoritativeRuntimeStatus.SOVEREIGN
                : AuthoritativeRuntimeStatus.CONTESTED;
    }

    public static AuthoritativeRuntimeStatus getStatus() {
        return status;
    }

    public static String getStatusLabel() {
        return switch (status) {
            case SOVEREIGN -> "sovereign";
            case CONTESTED -> "contested";
            case DEGRADED -> "degraded";
        };
    }

    public static String getDomainSummary() {
        if (!highRiskDomains.isEmpty()) {
            return "high-risk " + joinDomainNames(highRiskDomains);
        }
        if (!contestedDomains.isEmpty()) {
            return "contested " + joinDomainNames(contestedDomains);
        }
        return "all domains owned by PauC";
    }

    public static int getRuntimePressureBias() {
        return runtimePressureBias;
    }

    /**
     * Check if PAUC's own internal shader pipeline is active.
     * When active, PAUC owns the shader domain natively and external
     * contest flags for shader_pipeline don't apply the same way.
     */
    public static boolean isInternalShaderPipelineActive() {
        return DeferredWorldRenderingPipeline.isShaderActive();
    }

    /**
     * When PAUC's own deferred pipeline is active, DRS should render into the
     * pipeline's GBuffer FBO rather than the vanilla framebuffer.
     * We only yield to external pipelines (Oculus/Iris) not our own.
     */
    public static boolean shouldYieldDynamicResolutionToExternalPipeline() {
        return shaderPipelineContested && !isInternalShaderPipelineActive();
    }

    public static boolean shouldYieldAdaptiveFrameCapToExternalPipeline() {
        return shaderPipelineContested && !isInternalShaderPipelineActive();
    }

    public static boolean shouldYieldEntityBillboardsToExternalPipeline() {
        return shaderPipelineContested && !isInternalShaderPipelineActive();
    }

    public static boolean shouldYieldAdvancedSharpeningToExternalPipeline() {
        return shaderPipelineContested && !isInternalShaderPipelineActive();
    }

    /**
     * Terrain proxy is disabled when external shaders contest, but stays
     * available when PAUC's own deferred pipeline runs (it manages its own
     * GBuffer state and knows how to handle proxy geometry).
     */
    public static boolean shouldDisableTerrainProxy() {
        if (chunkAuthorityContested) return true;
        return shaderPipelineContested && !isInternalShaderPipelineActive();
    }

    public static String getTerrainProxyBlockReason() {
        if (!PauCClient.isEnabled()) {
            return "PauC off";
        }
        if (!PauCClient.isBudgetActive()) {
            return "runtime off";
        }
        if (!PauCClient.isAuthoritativeRuntimeEnabled()) {
            return "authority off";
        }
        if (chunkAuthorityContested) {
            return "chunk streaming contested";
        }
        if (shaderPipelineContested) {
            return "shader pipeline contested";
        }
        return "ready";
    }

    public static boolean shouldForcePlayerAffectedChunkPriority() {
        return chunkAuthorityContested || worldgenRiskPresent || status == AuthoritativeRuntimeStatus.DEGRADED;
    }

    public static boolean shouldThrottleChunkStreaming() {
        return chunkAuthorityContested
                || status == AuthoritativeRuntimeStatus.DEGRADED
                || (worldgenRiskPresent && IntegratedServerLoadController.getPressureLevel() >= 1);
    }

    public static double getChunkBudgetPenaltyMultiplier() {
        if (status == AuthoritativeRuntimeStatus.DEGRADED) {
            return worldgenRiskPresent ? 0.72D : 0.82D;
        }

        if (worldgenRiskPresent || chunkAuthorityContested) {
            return 0.88D;
        }

        if (capturePipelineContested) {
            return 0.94D;
        }

        return 1.00D;
    }

    public static int adjustMobCadence(int cadence, boolean navigation) {
        if (cadence <= 1 || !PauCClient.isAuthoritativeRuntimeEnabled()) {
            return cadence;
        }

        int serverPressure = IntegratedServerLoadController.getPressureLevel();
        if (status == AuthoritativeRuntimeStatus.DEGRADED && worldgenRiskPresent && serverPressure >= 1) {
            return Math.max(cadence, navigation ? 5 : 4);
        }

        if (worldgenRiskPresent && serverPressure >= 2) {
            return Math.max(cadence, navigation ? 4 : 3);
        }

        return cadence;
    }

    private static void rescanLoadedStack() {
        delegatedBackends.clear();
        passiveMods.clear();
        contestedMods.clear();
        highRiskMods.clear();
        contestedDomains.clear();
        highRiskDomains.clear();

        ModList modList = ModList.get();
        if (modList == null) {
            return;
        }

        List<IModInfo> mods = modList.getMods();
        for (IModInfo modInfo : mods) {
            String modId = modInfo.getModId();
            if (Pain_au_Choc.MOD_ID.equals(modId)) {
                continue;
            }

            ModAuthorityPolicy policy = KNOWN_POLICIES.get(modId);
            if (policy == null) {
                continue;
            }

            String label = modInfo.getDisplayName() + " [" + policy.domain().label + "]";
            switch (policy.disposition()) {
                case DELEGATED_BACKEND -> delegatedBackends.add(label);
                case PASSIVE_ALLOWED -> passiveMods.add(label);
                case FORBIDDEN_IN_AUTHORITATIVE_PROFILE -> {
                    contestedMods.add(label);
                    contestedDomains.add(policy.domain());
                }
                case HIGH_RISK -> {
                    highRiskMods.add(label);
                    highRiskDomains.add(policy.domain());
                }
            }
        }

        shaderPipelineContested = contestedDomains.contains(AuthorityDomain.SHADER_PIPELINE);
        chunkAuthorityContested = contestedDomains.contains(AuthorityDomain.CHUNK_STREAMING);
        renderAuthorityContested = contestedDomains.contains(AuthorityDomain.RENDER_BACKEND);
        capturePipelineContested = contestedDomains.contains(AuthorityDomain.CAPTURE_PIPELINE);
        worldgenRiskPresent = highRiskDomains.contains(AuthorityDomain.WORLDGEN);
    }

    private static void updateRuntimeState() {
        int liveSignals = 0;
        if ((renderAuthorityContested || shaderPipelineContested) && LatencyController.getPressureLevel() >= 2) {
            liveSignals++;
        }
        if ((chunkAuthorityContested || worldgenRiskPresent) && ChunkBuildQueueController.getBackPressureRatio() >= 0.65F) {
            liveSignals++;
        }
        if (worldgenRiskPresent && IntegratedServerLoadController.getPressureLevel() >= 1) {
            liveSignals++;
        }
        if (IntegratedServerLoadController.getPressureLevel() >= 2 && LatencyController.getPressureLevel() >= 2) {
            liveSignals++;
        }

        if (contestedMods.isEmpty() && highRiskMods.isEmpty()) {
            status = AuthoritativeRuntimeStatus.SOVEREIGN;
        } else if ((worldgenRiskPresent && IntegratedServerLoadController.getPressureLevel() >= 1) || liveSignals >= 2) {
            status = AuthoritativeRuntimeStatus.DEGRADED;
        } else {
            status = AuthoritativeRuntimeStatus.CONTESTED;
        }

        runtimePressureBias = computeRuntimePressureBias();
    }

    private static int computeRuntimePressureBias() {
        int bias = 0;
        if (worldgenRiskPresent && IntegratedServerLoadController.getPressureLevel() >= 1) {
            bias++;
        }
        if ((chunkAuthorityContested || capturePipelineContested) && ChunkBuildQueueController.getBackPressureRatio() >= 0.70F) {
            bias++;
        }
        if ((shaderPipelineContested || renderAuthorityContested) && LatencyController.getPressureLevel() >= 2) {
            bias++;
        }

        int maxBias = status == AuthoritativeRuntimeStatus.DEGRADED ? 2 : 1;
        return Math.max(0, Math.min(maxBias, bias));
    }

    private static void logAuthorityReport() {
        if (logged) {
            return;
        }

        logged = true;
        Pain_au_Choc.LOGGER.info("PauC authority runtime: status={}, domains={}", getStatusLabel(), getDomainSummary());
        if (!delegatedBackends.isEmpty()) {
            Pain_au_Choc.LOGGER.info("PauC authority runtime: delegated backends={}", String.join(", ", delegatedBackends));
        }
        if (!passiveMods.isEmpty()) {
            Pain_au_Choc.LOGGER.info("PauC authority runtime: passive mods={}", String.join(", ", passiveMods));
        }
        if (!contestedMods.isEmpty()) {
            Pain_au_Choc.LOGGER.warn("PauC authority runtime: forbidden for authoritative profile={}", String.join(", ", contestedMods));
        }
        if (!highRiskMods.isEmpty()) {
            Pain_au_Choc.LOGGER.warn("PauC authority runtime: high-risk mods={}", String.join(", ", highRiskMods));
        }
    }

    private static String joinDomainNames(EnumSet<AuthorityDomain> domains) {
        ArrayList<String> names = new ArrayList<>(domains.size());
        for (AuthorityDomain domain : domains) {
            names.add(domain.label);
        }
        return String.join(", ", names);
    }

    private static Map<String, ModAuthorityPolicy> createKnownPolicies() {
        HashMap<String, ModAuthorityPolicy> policies = new HashMap<>();
        // PAUC now owns render_backend and shader_pipeline natively.
        // Embeddium/Rubidium conflict with PAUC's built-in Embeddium-like chunk renderer.
        registerPolicy(policies, "embeddium", AuthorityDomain.RENDER_BACKEND, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        registerPolicy(policies, "rubidium", AuthorityDomain.RENDER_BACKEND, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        // Oculus/Iris conflict with PAUC's built-in Oculus-like shader pipeline.
        registerPolicy(policies, "oculus", AuthorityDomain.SHADER_PIPELINE, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        registerPolicy(policies, "iris", AuthorityDomain.SHADER_PIPELINE, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        // Passive mods — no conflict with PAUC
        registerPolicy(policies, "geckolib", AuthorityDomain.ENTITY_RENDERING, ModDisposition.PASSIVE_ALLOWED);
        registerPolicy(policies, "servercore", AuthorityDomain.SERVER_SIMULATION, ModDisposition.PASSIVE_ALLOWED);
        registerPolicy(policies, "vmp", AuthorityDomain.SERVER_SIMULATION, ModDisposition.PASSIVE_ALLOWED);
        // Contested — external mods that overlap with PAUC managed domains
        registerPolicy(policies, "distanthorizons", AuthorityDomain.CHUNK_STREAMING, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        registerPolicy(policies, "flerovium", AuthorityDomain.RENDER_BACKEND, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        registerPolicy(policies, "replaymod", AuthorityDomain.CAPTURE_PIPELINE, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        registerPolicy(policies, "reforgedplaymod", AuthorityDomain.CAPTURE_PIPELINE, ModDisposition.FORBIDDEN_IN_AUTHORITATIVE_PROFILE);
        // High risk — may destabilize server tick
        registerPolicy(policies, "expandedworld", AuthorityDomain.WORLDGEN, ModDisposition.HIGH_RISK);
        return policies;
    }

    private static void registerPolicy(
            Map<String, ModAuthorityPolicy> policies,
            String modId,
            AuthorityDomain domain,
            ModDisposition disposition
    ) {
        policies.put(modId, new ModAuthorityPolicy(domain, disposition));
    }

    private enum AuthorityDomain {
        RENDER_BACKEND("render_backend"),
        SHADER_PIPELINE("shader_pipeline"),
        CHUNK_STREAMING("chunk_streaming"),
        SERVER_SIMULATION("server_simulation"),
        CAPTURE_PIPELINE("capture_pipeline"),
        WORLDGEN("worldgen"),
        ENTITY_RENDERING("entity_rendering");

        private final String label;

        AuthorityDomain(String label) {
            this.label = label;
        }
    }

    private enum ModDisposition {
        DELEGATED_BACKEND,
        PASSIVE_ALLOWED,
        FORBIDDEN_IN_AUTHORITATIVE_PROFILE,
        HIGH_RISK
    }

    private record ModAuthorityPolicy(AuthorityDomain domain, ModDisposition disposition) {
    }
}
