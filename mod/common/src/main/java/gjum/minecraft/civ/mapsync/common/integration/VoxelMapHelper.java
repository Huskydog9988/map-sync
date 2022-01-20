package gjum.minecraft.civ.mapsync.common.integration;

import com.mamiyaotaru.voxelmap.VoxelMap;
import com.mamiyaotaru.voxelmap.interfaces.IPersistentMap;
import com.mamiyaotaru.voxelmap.persistent.*;
import gjum.minecraft.civ.mapsync.common.protocol.ChunkTile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("JavaReflectionMemberAccess")
public class VoxelMapHelper {

	private static Field cachedRegionsField;
	private static Field cachedRegionsPoolField;
	private static Field worldField;

	private static Constructor<?> constructorCachedRegion;
	private static Field regionDataField;
	private static Field liveChunksUpdatedField;
	private static Field dataUpdatedField;
	private static Method loadCachedDataMethod;

	// these are necessary because they take a BlockState, which is obfuscated in the VoxelMap jar.
	private static Method setTransparentBlockstateMethod;
	private static Method setFoliageBlockstateMethod;
	private static Method setBlockstateMethod;
	private static Method setOceanFloorBlockstateMethod;

	static {
		try {
			cachedRegionsField = PersistentMap.class.getField("cachedRegions");
			cachedRegionsField.setAccessible(true);
			cachedRegionsPoolField = PersistentMap.class.getField("cachedRegionsPool");
			cachedRegionsPoolField.setAccessible(true);
			worldField = PersistentMap.class.getField("world");
			worldField.setAccessible(true);

			constructorCachedRegion = CachedRegion.class.getConstructor(IPersistentMap.class, String.class, ClientLevel.class, String.class, String.class, Integer.class, Integer.class);
			loadCachedDataMethod = CachedRegion.class.getMethod("loadCachedData");
			loadCachedDataMethod.setAccessible(true);
			regionDataField = CachedRegion.class.getField("data");
			regionDataField.setAccessible(true);
			liveChunksUpdatedField = CachedRegion.class.getField("liveChunksUpdated");
			liveChunksUpdatedField.setAccessible(true);
			dataUpdatedField = CachedRegion.class.getField("dataUpdated");
			dataUpdatedField.setAccessible(true);

			setTransparentBlockstateMethod = CompressibleMapData.class.getMethod("setTransparentBlockstate", Integer.class, Integer.class, BlockState.class);
			setFoliageBlockstateMethod = CompressibleMapData.class.getMethod("setFoliageBlockstate", Integer.class, Integer.class, BlockState.class);
			setBlockstateMethod = CompressibleMapData.class.getMethod("setBlockstate", Integer.class, Integer.class, BlockState.class);
			setOceanFloorBlockstateMethod = CompressibleMapData.class.getMethod("setOceanFloorBlockstate", Integer.class, Integer.class, BlockState.class);
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	// TODO update multiple chunks in one region at once
	// TODO which thread should this run on?
	public static void updateWithChunkTile(ChunkTile chunkTile) {
		try {
			// check that VoxelMap classes are loaded properly
			if (regionDataField == null) return;

			int rx = chunkTile.x() * 256 / 16;
			int rz = chunkTile.z() * 256 / 16;
			@Nullable var region = getRegion(rx, rz);
			if (region == null) return;

			var mapData = (CompressibleMapData) regionDataField.get(region);

			int i = 0;
			for (int x = 0; x < 16; ++x) {
				for (int z = 0; z < 16; ++z) {
					var col = chunkTile.columns()[i++];
					// "x/z in region"
					int xir = chunkTile.x() * 16 + x;
					int yir = chunkTile.z() * 16 + z;

					mapData.setBiomeID(xir, yir, col.biomeId());

					mapData.setTransparentLight(xir, yir, col.light());
					mapData.setTransparentHeight(xir, yir, transparentHeight);
					setTransparentBlockstateMethod.invoke(mapData, xir, yir, transparentBlockState);

					mapData.setFoliageLight(xir, yir, col.light());
					mapData.setFoliageHeight(xir, yir, foliageHeight);
					setFoliageBlockstateMethod.invoke(mapData, xir, yir, foliageBlockState);

					mapData.setLight(xir, yir, col.light());
					mapData.setHeight(xir, yir, surfaceHeight);
					setBlockstateMethod.invoke(mapData, xir, yir, surfaceBlockState);

					mapData.setOceanFloorLight(xir, yir, col.light());
					mapData.setOceanFloorHeight(xir, yir, seafloorHeight);
					setOceanFloorBlockstateMethod.invoke(mapData, xir, yir, seafloorBlockState);
				}
			}

			// TODO set region.empty=false?
			liveChunksUpdatedField.set(region, true);
			dataUpdatedField.set(region, true);

			// TODO write/close region? or does voxelmap close it after a while?
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	@Nullable
	private static CachedRegion getRegion(int rx, int rz)
			throws IllegalAccessException, InvocationTargetException, InstantiationException {
		var vm = VoxelMap.getInstance();
		PersistentMap pm = (PersistentMap) vm.getPersistentMap();

		@SuppressWarnings("unchecked")
		var cachedRegions = (ConcurrentHashMap<String, CachedRegion>) cachedRegionsField.get(pm);

		@SuppressWarnings("unchecked")
		var cachedRegionsPool = (List<CachedRegion>) cachedRegionsPoolField.get(pm);

		var world = worldField.get(pm);

		String worldName = vm.getWaypointManager().getCurrentWorldName();
		String subWorldName = vm.getWaypointManager().getCurrentSubworldDescriptor(false);

		String key = rx + "," + rz;

		@Nullable CachedRegion cachedRegion;

		// the following synchronized{} section matches VoxelMap's internal logic

		//noinspection SynchronizationOnLocalVariableOrMethodParameter
		synchronized (cachedRegions) {
			cachedRegion = cachedRegions.get(key);
			// could be race condition if the region is not fully loaded at this point
			if (cachedRegion != null) return cachedRegion;

//			// we don't know which constructor (because of obfuscated classes), so try them all
//			for (Constructor<?> constructor : CachedRegion.class.getConstructors()) {
//				try {
//					cachedRegion = (CachedRegion) constructor.newInstance(pm, key, world, worldName, subWorldName, rx, rz);
//					break;
//				} catch (IllegalArgumentException ignored) {
//					// try next constructor
//				}
//			}
//			if (cachedRegion == null) return null; // constructor failed

			cachedRegion = (CachedRegion) constructorCachedRegion.newInstance(
					pm, key, world, worldName, subWorldName, rx, rz);

			cachedRegions.put(key, cachedRegion);

			//noinspection SynchronizationOnLocalVariableOrMethodParameter
			synchronized (cachedRegionsPool) {
				cachedRegionsPool.add(cachedRegion);
			}
		}

		// TODO which thread should this run on?
		loadCachedDataMethod.invoke(cachedRegion);

		return cachedRegion;
	}
}