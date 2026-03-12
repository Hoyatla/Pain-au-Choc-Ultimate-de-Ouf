package pauc.pain_au_choc.mixin;

import pauc.pain_au_choc.ServerMobCadenceController;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Mob.class)
public abstract class MobServerAiMixin {
    @Redirect(
            method = "serverAiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tickRunningGoals(Z)V",
                    ordinal = 0
            )
    )
    private void pauc$budgetTargetSelectorRunningGoals(GoalSelector selector, boolean unusedValue) {
        Mob mob = (Mob) (Object) this;
        if (ServerMobCadenceController.shouldRunTargetSelectorTick(mob, true)) {
            selector.tickRunningGoals(false);
        }
    }

    @Redirect(
            method = "serverAiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tickRunningGoals(Z)V",
                    ordinal = 1
            )
    )
    private void pauc$budgetGoalSelectorRunningGoals(GoalSelector selector, boolean unusedValue) {
        Mob mob = (Mob) (Object) this;
        if (ServerMobCadenceController.shouldRunGoalSelectorTick(mob, true)) {
            selector.tickRunningGoals(false);
        }
    }

    @Redirect(
            method = "serverAiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V",
                    ordinal = 0
            )
    )
    private void pauc$budgetTargetSelectorTick(GoalSelector selector) {
        Mob mob = (Mob) (Object) this;
        if (ServerMobCadenceController.shouldRunTargetSelectorTick(mob, false)) {
            selector.tick();
            return;
        }

        selector.tickRunningGoals(false);
    }

    @Redirect(
            method = "serverAiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V",
                    ordinal = 1
            )
    )
    private void pauc$budgetGoalSelectorTick(GoalSelector selector) {
        Mob mob = (Mob) (Object) this;
        if (ServerMobCadenceController.shouldRunGoalSelectorTick(mob, false)) {
            selector.tick();
            return;
        }

        selector.tickRunningGoals(false);
    }

    @Redirect(
            method = "serverAiStep()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"
            )
    )
    private void pauc$budgetNavigationTick(PathNavigation navigation) {
        Mob mob = (Mob) (Object) this;
        if (ServerMobCadenceController.shouldRunNavigationTick(mob)) {
            navigation.tick();
        }
    }
}
