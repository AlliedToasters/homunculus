package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.listener.IEventBus;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IMineProcess;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// Compile-only probe — proves every Baritone API call cited in SPEC.md resolves
// against baritone-api-fabric-1.13.1. Never invoked at runtime.
@SuppressWarnings("unused")
final class BaritoneProbe {
    private BaritoneProbe() {}

    static int probe() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        IMineProcess mine = baritone.getMineProcess();
        mine.mine(4, Blocks.OAK_LOG);
        boolean mineActive = mine.isActive();

        ICustomGoalProcess goal = baritone.getCustomGoalProcess();
        goal.setGoalAndPath(new GoalBlock(0, 64, 0));
        boolean goalActive = goal.isActive();

        IBuilderProcess builder = baritone.getBuilderProcess();
        builder.clearArea(new BlockPos(0, 64, 0), new BlockPos(1, 65, 1));
        BlockOptionalMeta bom = new BlockOptionalMeta(Blocks.COBBLESTONE);
        FillSchematic fillSchem = new FillSchematic(2, 2, 2, bom);
        builder.build("probe", fillSchem, new Vec3i(0, 64, 0));
        boolean builderActive = builder.isActive();

        IPathingBehavior pathing = baritone.getPathingBehavior();
        boolean isPathing = pathing.isPathing();
        boolean acked = pathing.cancelEverything();
        IPathExecutor executor = pathing.getCurrent();
        IPath viaExecutor = executor != null ? executor.getPath() : null;
        Optional<IPath> viaBehavior = pathing.getPath();
        int len = viaBehavior.map(IPath::length).orElse(0);

        IEventBus bus = baritone.getGameEventHandler();
        bus.registerEventListener(new AbstractGameEventListener() {
            @Override public void onPathEvent(PathEvent event) {
                switch (event) {
                    case CALC_FAILED, CALC_FINISHED_NOW_EXECUTING, AT_GOAL, CANCELED -> {}
                    default -> {}
                }
            }
        });

        return (mineActive ? 1 : 0) + (goalActive ? 1 : 0) + (builderActive ? 1 : 0)
             + (isPathing ? 1 : 0) + (acked ? 1 : 0) + len + (viaExecutor != null ? 1 : 0);
    }
}
