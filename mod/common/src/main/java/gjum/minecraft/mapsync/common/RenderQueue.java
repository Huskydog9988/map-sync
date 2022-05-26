package gjum.minecraft.mapsync.common;

import gjum.minecraft.mapsync.common.data.ChunkTile;
import gjum.minecraft.mapsync.common.integration.JourneyMapHelper;
import gjum.minecraft.mapsync.common.integration.VoxelMapHelper;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

import static gjum.minecraft.mapsync.common.MapSyncMod.getMod;

public class RenderQueue {
	private final DimensionState dimensionState;

	private final long beginLiveTs = System.currentTimeMillis();
	private long tsRequestMore = 0;
	private Thread thread;

	private PriorityBlockingQueue<ChunkTile> queue = new PriorityBlockingQueue<>(18,
			// newest chunks first
			Comparator.comparingLong(ChunkTile::timestamp).reversed());

	public RenderQueue(DimensionState dimensionState) {
		this.dimensionState = dimensionState;
	}

	/**
	 * don't push chunks from mc - they're rendered by the installed map mod
	 */
	public synchronized void renderLater(@NotNull ChunkTile chunkTile) {
		queue.add(chunkTile);
		if (thread == null) {
			thread = new Thread(this::renderLoop);
			thread.start();
		}
		if (chunkTile.timestamp() < beginLiveTs) {
			// assume received chunk is catchup chunk
			// wait a bit to allow more catchup chunks to come in before requesting more
			tsRequestMore = System.currentTimeMillis() + 100;
		}
	}

	public synchronized void shutDown() {
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	private void renderLoop() {
		final int WATERMARK_REQUEST_MORE = MapSyncMod.modConfig.getCatchupWatermark();

		try {
			while (true) {

				if (Minecraft.getInstance().level == null) {
					return; // world closed; all queued chunks can't be rendered
				}

				if (!JourneyMapHelper.isJourneyMapNotAvailable && !JourneyMapHelper.isMapping()
						|| !VoxelMapHelper.isVoxelMapNotAvailable && !VoxelMapHelper.isMapping()
				) {
					Thread.sleep(1000);
					continue;
				}

				var chunkTile = queue.poll();
				if (chunkTile == null) return;

				if (chunkTile.dimension() != Minecraft.getInstance().level.dimension()) {
					continue; // mod renderers would render this to the wrong dimension
				}

				// chunks from sync server (live, region) will always be older than mc, so mc will take priority
				if (chunkTile.timestamp() < dimensionState.getChunkTimestamp(chunkTile.chunkPos())) {
					continue; // don't overwrite newer data with older data
				}

				boolean voxelRendered = VoxelMapHelper.updateWithChunkTile(chunkTile);
				boolean renderedJM = JourneyMapHelper.updateWithChunkTile(chunkTile);

				if (renderedJM || voxelRendered) {
					dimensionState.setChunkTimestamp(chunkTile.chunkPos(), chunkTile.timestamp());
					dimensionState.writeLastTimestamp(chunkTile.timestamp());
				} // otherwise, update this chunk again when server sends it again

				Thread.sleep(0); // allow stopping via thread.interrupt()

				long now = System.currentTimeMillis();
				if (queue.size() == 0 || queue.size() < WATERMARK_REQUEST_MORE && tsRequestMore < now) {
					// before requesting more, wait for a catchup chunk to be received (see renderLater());
					// if none get received within a second (all outdated etc.) then request more anyway
					tsRequestMore = now + 1000;
					var chunksToRequest = dimensionState.pollCatchupChunks(WATERMARK_REQUEST_MORE);
					getMod().requestCatchupData(chunksToRequest);
				}
			}
		} catch (InterruptedException ignored) {
			// exit silently
		} catch (Throwable err) {
			err.printStackTrace();
		} finally {
			synchronized (this) {
				thread = null;
			}
		}
	}

	public static boolean areAllMapModsMapping() {
		return JourneyMapHelper.isMapping();
	}
}
