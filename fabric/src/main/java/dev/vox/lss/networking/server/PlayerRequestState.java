package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.IncomingRequest;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class PlayerRequestState extends AbstractPlayerRequestState<CustomPacketPayload> {
    private volatile ServerPlayer player;
    private ResourceKey<Level> lastDimension;

    public PlayerRequestState(ServerPlayer player, int syncConcurrency, int genConcurrency) {
        super(player.getUUID(), syncConcurrency, genConcurrency);
        this.player = player;
        this.lastDimension = player.level().dimension();
    }

    public void addRequest(long packedPosition, long clientTimestamp) {
        int cx = PositionUtil.unpackX(packedPosition);
        int cz = PositionUtil.unpackZ(packedPosition);
        enqueueIncomingRequest(new IncomingRequest(cx, cz, clientTimestamp));
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    public ServerPlayer getPlayer() { return this.player; }

    @Override
    public String getPlayerName() { return this.player.getName().getString(); }
    public ResourceKey<Level> getLastDimension() { return this.lastDimension; }

    public boolean checkDimensionChange() {
        var currentDim = this.player.level().dimension();
        if (!currentDim.equals(this.lastDimension)) {
            this.lastDimension = currentDim;
            return true;
        }
        return false;
    }
}
