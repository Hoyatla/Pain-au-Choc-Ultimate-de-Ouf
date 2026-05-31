package net.irisshaders.batchedentityrendering.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Sorts the entity list to allow entities of the same type to be properly batched. Without sorting, entities are
 * essentially rendered in random order, which makes it harder for entity batching to work properly.
 * <p>
 * Sorting reduces the number of RenderTypes that need to be active at any given time for batching to be effective.
 * For example, instead of the batching system needing to be prepared to render a pig at any time, it can know that
 * every pig is being rendered all at once, then it can use unused space within the buffer previously used for pigs for
 * something else.
 * <p>
 * This is even more effective with vanilla's entity rendering, since it only has a single buffer for most purposes,
 * except for a configured set of batched render types.
 * <p>
 * This injection point has been carefully chosen to avoid conflicts with other mixins such as
 * <a href="https://github.com/gnembon/fabric-carpet/blob/776f798aecb792a5881ccae8784888156207a047/src/main/java/carpet/mixins/WorldRenderer_pausedShakeMixin.java#L23"> one from Carpet:</a>
 * <p>
 * By using ModifyVariable instead of Redirect, it is more likely to be compatible with other rendering mods. We also
 * use a priority of 999 to apply before most other mixins to this method, meaning that other mods adding entities to
 * the rendering list (like Twilight Forest) are more likely to have these added entities sorted.
 */
@Mixin(value = LevelRenderer.class, priority = 999)
public class MixinLevelRenderer_EntityListSorting {
	@Shadow(aliases = "f_109465_")
	private ClientLevel level;
	@Unique
	private final Map<EntityType<?>, List<Entity>> pauc$sortedEntityGroups = new HashMap<>();
	@Unique
	private final List<Entity> pauc$sortedEntities = new ArrayList<>();

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;iterator()Ljava/util/Iterator;"))
	private Iterator<Entity> batchedentityrendering$sortEntityList(Iterable<Entity> instance, Operation<Iterator<Entity>> original) {
		long startedAt = System.nanoTime();
		// Sort the entity list first in order to allow vanilla's entity batching code to work better.
		this.level.getProfiler().push("sortEntityList");

		for (List<Entity> group : pauc$sortedEntityGroups.values()) {
			group.clear();
		}
		pauc$sortedEntities.clear();
		int entityCount = 0;
		int groupCount = 0;
		original.call(instance).forEachRemaining(entity -> {
			EntityType<?> type = entity.getType();
			List<Entity> group = pauc$sortedEntityGroups.get(type);
			if (group == null) {
				group = new ArrayList<>(32);
				pauc$sortedEntityGroups.put(type, group);
			}
			group.add(entity);
		});

		for (List<Entity> group : pauc$sortedEntityGroups.values()) {
			if (!group.isEmpty()) {
				groupCount++;
				entityCount += group.size();
				pauc$sortedEntities.addAll(group);
			}
		}

		this.level.getProfiler().pop();
		PauCVillagePerformanceDiagnostics.recordEntitySort(entityCount, groupCount, System.nanoTime() - startedAt);

		return pauc$sortedEntities.iterator();
	}
}
