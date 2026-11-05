package com.github.aeddddd.mmceaddition.verification;

import ae2.api.config.Actionable;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.networking.GridHelper;
import ae2.api.networking.IGridConnection;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.*;
import ae2.api.storage.*;
import ae2.core.definitions.AEBlocks;
import ae2.me.helpers.IGridConnectedTile;
import com.github.aeddddd.mmceaddition.RegistryHandler;
import com.github.aeddddd.mmceaddition.compat.NetworkEnergyCompat;
import com.github.aeddddd.mmceaddition.compat.NetworkResourceCompat;
import com.github.aeddddd.mmceaddition.tile.*;
import com.github.aeddddd.mmceaddition.util.*;
import com.google.gson.GsonBuilder;
import hellfirepvp.modularmachinery.common.block.BlockController;
import hellfirepvp.modularmachinery.common.tiles.TileMachineController;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;
import java.util.*;

/** Separate acceptance mod: never packaged into the production mod. */
@Mod(modid="mmceaddition_verification", name="MMCE Addition AE2S acceptance", version="1",
        dependencies="required-after:mmceaddition")
public final class RuntimeVerification {
    public static volatile boolean readyForClient;
    public static volatile String clientFailure;
    public static final BlockPos PATTERN = new BlockPos(8, 80, 0);
    private final List<String> checks = new ArrayList<>();
    private final Map<String,Object> report = new LinkedHashMap<>();
    private MinecraftServer server;
    private WorldServer world;
    private TileMEAsyncItemOutputBus items;
    private TileMEAsyncFluidOutputHatch fluids;
    private TileMEOutputAssembly output;
    private TileMEPatternAssembly patterns;
    private IManagedGridNode power;
    private IGridConnection outputLink;
    private final TestStorage storage = new TestStorage();
    private int ticks, phase, changedAt;
    private boolean finished;
    private IPatternDetails detail;
    private TileMachineController machine;
    private final AEItemKey apple = AEItemKey.of(Items.APPLE);
    private final AEItemKey diamond = AEItemKey.of(Items.DIAMOND);
    private final AEFluidKey water = AEFluidKey.of(FluidRegistry.WATER);

    @Mod.EventHandler public void init(FMLPreInitializationEvent event) throws Exception {
        report.put("cleanroom", Loader.instance().getIndexedModList().get("cleanroom").getVersion());
        report.put("checks", checks);
        require("0.4.0".equals(report.get("cleanroom")), "Wrong loader baseline");
        require(Runtime.version().feature() == 25, "Java 25 required");
        MinecraftForge.EVENT_BUS.register(this);
        if (event.getSide().isClient()) ClientVerification.install();
    }
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        server=FMLCommonHandler.instance().getMinecraftServerInstance(); world=server.getWorld(0);
        world.setSpawnPoint(new BlockPos(8,80,4));
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || finished) return;
        try {
            if (++ticks > 1600) throw new IllegalStateException("Timed out in phase " + phase);
            switch (phase) {
                case 0 -> { if (ticks >= 20) create(); }
                case 1 -> connect();
                case 2 -> partialOutput();
                case 3 -> completeOutput();
                case 4 -> offlineOutput();
                case 5 -> reconnectedOutput();
                case 6 -> patternChecks();
                case 7 -> machineCheck();
                case 8 -> {
                    readyForClient=true;
                    if (FMLCommonHandler.instance().getSide().isClient() && !ClientVerification.done()) return;
                    require(clientFailure==null,"Client GUI failure: "+clientFailure);
                    if (FMLCommonHandler.instance().getSide().isClient()) checks.add("registered-item-and-block-models-baked-and-rendered-server-backed-gui-120-slots-pattern-sync-and-shift-click");
                    invalidate(); finish("PASS", null);
                }
            }
        } catch (Throwable failure) { failure.printStackTrace(); finish("FAIL", failure); }
    }
    private void create() {
        for (Block b : net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS) {
            if (!"mmceaddition".equals(b.getRegistryName().getNamespace()) || !b.hasTileEntity(b.getDefaultState())) continue;
            TileEntity tile=b.createTileEntity(world,b.getDefaultState());
            tile.setWorld(world); tile.setPos(new BlockPos(24,80,checks.size()));
            NBTTagCompound data=tile.writeToNBT(new NBTTagCompound()); tile.readFromNBT(data);
        }
        checks.add("all-registered-tiles-construct-and-round-trip-nbt");
        world.setBlockState(new BlockPos(0,80,0),AEBlocks.CREATIVE_ENERGY_CELL.block().getDefaultState(),3);
        power=((IGridConnectedTile)world.getTileEntity(new BlockPos(0,80,0))).getMainNode();
        items=(TileMEAsyncItemOutputBus)place(new BlockPos(1,80,0),RegistryHandler.ME_ASYNC_ITEM_OUTPUT_BUS);
        fluids=(TileMEAsyncFluidOutputHatch)place(new BlockPos(4,80,0),RegistryHandler.ME_ASYNC_FLUID_OUTPUT_HATCH);
        patterns=(TileMEPatternAssembly)place(PATTERN,RegistryHandler.ME_PATTERN_ASSEMBLY);
        output=(TileMEOutputAssembly)place(new BlockPos(12,80,0),RegistryHandler.ME_OUTPUT_ASSEMBLY);
        phase=1;
    }
    private TileEntity place(BlockPos pos, Block block) {
        world.setBlockState(pos.down(),Blocks.STONE.getDefaultState(),3);
        world.setBlockState(pos,block.getDefaultState(),3); return world.getTileEntity(pos);
    }
    private void connect() {
        if (power.getNode()==null || fluids.getMainNode().getNode()==null || patterns.getMainNode().getNode()==null
                || output.getMainNode().getNode()==null) return;
        require(items.getMainNode().getGrid()==power.getGrid(),"AE2S capability did not connect adjacent blocks");
        GridHelper.createConnection(power.getNode(),fluids.getMainNode().getNode());
        GridHelper.createConnection(power.getNode(),patterns.getMainNode().getNode());
        outputLink=GridHelper.createConnection(power.getNode(),output.getMainNode().getNode());
        power.getGrid().getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(storage,0));
        storage.capacity=17;
        items.getItemBuffer().insert(new ItemStack(Items.APPLE,32));
        fluids.getFluidBuffer().fill(new FluidStack(FluidRegistry.WATER,32),true);
        checks.add("native-in-world-capability-and-shared-grid"); phase=2;
    }
    private void partialOutput() {
        if (!active() || ticks<changedAt+10 || storage.amount(apple)!=17 || storage.amount(water)!=17) return;
        require(items.getItemBuffer().getTotalAmount()==15 && fluids.getFluidBuffer().getTotalAmount()==15,
                "Partial insertion lost or duplicated a remainder");
        checks.add("item-and-fluid-partial-insertion-preserves-exact-remainder");
        storage.capacity=Long.MAX_VALUE; changedAt=ticks; phase=3;
    }
    private void completeOutput() {
        if (!items.getItemBuffer().isEmpty() || !fluids.getFluidBuffer().isEmpty()) return;
        require(storage.amount(apple)==32 && storage.amount(water)==32,"Retry did not conserve outputs");
        outputLink.destroy(); changedAt=ticks; phase=4;
    }
    private void offlineOutput() {
        if (ticks<changedAt+40) return;
        require(!output.getMainNode().isActive(),"Disconnected assembly stayed active");
        output.getItemBuffer().insert(new ItemStack(Items.DIAMOND,9));
        output.getFluidBuffer().fill(new FluidStack(FluidRegistry.LAVA,12),true);
        NBTTagCompound data=output.writeToNBT(new NBTTagCompound());
        TileMEOutputAssembly restored=new TileMEOutputAssembly(); restored.setWorld(world); restored.readFromNBT(data);
        require(restored.getBufferedItemAmount()==9 && restored.getBufferedFluidAmount()==12,"NBT lost buffered outputs");
        checks.add("offline-buffers-and-nbt-preserve-materials");
        outputLink=GridHelper.createConnection(power.getNode(),output.getMainNode().getNode());
        changedAt=ticks; phase=5;
    }
    private void reconnectedOutput() {
        if (!active() || !output.getMainNode().isActive() || output.hasAnyOutput()) return;
        require(storage.amount(diamond)==9 && storage.amount(AEFluidKey.of(FluidRegistry.LAVA))==12,
                "Reconnect did not retry pending output");
        checks.add("disconnected-output-retries-after-native-grid-reconnect");
        ItemStack encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(apple,2),new GenericStack(water,1000)),
                List.of(new GenericStack(diamond,1)));
        patterns.getPatterns().setItemDirect(35,encoded);
        detail=PatternDetailsHelper.decodePattern(encoded,world);
        changedAt=ticks; phase=6;
    }
    private void patternChecks() {
        if (!active() || ticks<changedAt+10) return;
        require(power.getGrid().getCraftingService().getCraftingFor(diamond).contains(detail),"Native crafting index omitted slot 36");
        require(patterns.getTerminalPatternInventory().size()==36 && patterns.containsPattern(detail.getDefinition()),
                "Native pattern terminal contract lost slots");
        require(patterns.getMainNode().getNode().getService(ICraftingProvider.class)==patterns,"Native crafting service missing");
        KeyCounter[] holders={new KeyCounter(),new KeyCounter()};
        holders[0].add(apple,6_000_000_000L); holders[1].add(water,3_000_000_000L);
        require(patterns.canMergePatternPush(detail) && patterns.getMaxPatternPushMultiplier(detail,100)==100,"Native merged push unavailable");
        require(patterns.pushPattern(detail,holders,3),"Native mixed long push rejected");
        var slot=patterns.getSlots()[35]; var variant=new ItemVariant(apple.toStack());
        require(slot.getInputItemBuffer().getAmount(variant)==6_000_000_000L && slot.getInputFluidBuffer().getAmount(FluidRegistry.WATER)==3_000_000_000L,
                "Native input was narrowed or multiplied twice");
        slot.getInputItemHandler().setStackInSlot(0,apple.toStack(Integer.MAX_VALUE-1));
        require(slot.getInputItemBuffer().getAmount(variant)==5_999_999_999L,"int view consumption truncated long input");
        slot.getInputItemBuffer().insertLong(variant,Long.MAX_VALUE);
        long fluidBefore=slot.getInputFluidBuffer().getTotalAmount();
        require(!patterns.pushPattern(detail,holders,3) && slot.getInputFluidBuffer().getTotalAmount()==fluidBefore,
                "Overflow rejection partially accepted materials");
        checks.add("native-slot-36-index-terminal-and-merged-mixed-long-inputs");
        checks.add("long-buffer-consumption-and-atomic-overflow-rejection");
        slot.getInputItemBuffer().clear(); slot.getInputFluidBuffer().clear(); slot.getInputItemHandler().syncWithBuffer();
        patterns.getPatterns().setItemDirect(35,ItemStack.EMPTY);
        require(power.getGrid().getCraftingService().getCraftingFor(diamond).isEmpty(),"Removed pattern stayed indexed");
        checks.add("pattern-edits-refresh-native-crafting-index");
        require(!NetworkEnergyCompat.isAvailable() && !NetworkResourceCompat.isManaAvailable()
                && !NetworkResourceCompat.isStarlightAvailable() && !NetworkResourceCompat.isEssentiaAvailable(),
                "Missing optional integrations did not remain optional");
        checks.add("optional-resource-mods-absent-without-linkage-errors");
        resourceChecks(); createMachine(); phase=7; changedAt=ticks;
    }
    private void createMachine() {
        BlockPos pos=new BlockPos(8,80,-1);
        Block block=Block.getBlockFromName("modularmachinery:blockcontroller");
        world.setBlockState(pos,block.getDefaultState().withProperty(BlockController.FACING,EnumFacing.NORTH),3);
        place(pos.add(-1,0,1),Block.getBlockFromName("modularmachinery:blockcasing"));
        place(pos.add(1,0,1),Block.getBlockFromName("modularmachinery:blockcasing"));
        machine=(TileMachineController)world.getTileEntity(pos);
        ItemStack encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(apple,2)),List.of(new GenericStack(AEItemKey.of(Items.EMERALD),1)));
        patterns.getPatterns().setItemDirect(0,encoded);
    }
    private volatile long workerEnergy = -1;
    private void resourceChecks() {
        AEKeyTypes.register(TestEnergyKey.TYPE);
        storage.insert(TestEnergyKey.INSTANCE,40,Actionable.MODULATE,IActionSource.empty());
        require(NetworkEnergyCompat.isAvailable(),"Registered native RF key not discovered");
        require(NetworkEnergyCompat.extractEnergy(patterns,41,false)==0 && storage.amount(TestEnergyKey.INSTANCE)==40,
                "Insufficient native resource commit partially drained storage");
        hellfirepvp.modularmachinery.ModularMachinery.EXECUTE_MANAGER.addTask(() -> {
            workerEnergy=NetworkEnergyCompat.extractEnergy(patterns,9,false);
        });
    }
    private boolean pushed;
    private void machineCheck() {
        if (workerEnergy<0) return;
        require(workerEnergy==9 && storage.amount(TestEnergyKey.INSTANCE)==31,"MMCE worker native resource transaction failed");
        if (!machine.isStructureFormed()) return;
        if (!pushed) {
            IPatternDetails recipe=patterns.getSlots()[0].getPatternDetails();
            KeyCounter[] holder={new KeyCounter()}; holder[0].add(apple,2);
            require(patterns.pushPattern(recipe,holder,1),"Physical machine input rejected"); pushed=true;
        }
        if (storage.amount(AEItemKey.of(Items.EMERALD))!=1) return;
        require(patterns.getSlots()[0].getInputItemBuffer().isEmpty(),"Real MMCE recipe did not consume its ingredients");
        checks.add("native-rf-key-fixture-worker-marshalling-and-atomic-insufficient-resource-rejection");
        checks.add("physical-mmce-multiblock-consumes-native-pattern-input-and-returns-product");
        phase=8;
    }
    private boolean active() { return items.getMainNode().isActive() && fluids.getMainNode().isActive() && patterns.getMainNode().isActive(); }
    private void invalidate() {
        for (TileMENetworkComponent tile : List.of(items,fluids,patterns,output)) {
            tile.invalidate(); require(tile.getMainNode().getNode()==null,"Invalidation retained a native node");
        }
        checks.add("native-node-and-manager-cleanup");
    }
    private void finish(String result, Throwable failure) {
        finished=true; report.put("result",result); report.put("ticks",ticks);
        if (failure!=null) report.put("failure",failure.toString());
        try { Files.writeString(Path.of("verification-report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception ex) { throw new RuntimeException(ex); }
        if (FMLCommonHandler.instance().getSide().isServer()) server.initiateShutdown();
        else ClientVerification.finish();
    }
    public static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }

    /** Fixture for the documented optional RF key schema; not an AE2Enhanced runtime test. */
    private static final class TestEnergyKey extends AEKey {
        static final TestEnergyKey INSTANCE=new TestEnergyKey();
        static final AEKeyType TYPE=new AEKeyType(new net.minecraft.util.ResourceLocation("ae2enhanced","rf"),
                TestEnergyKey.class,new TextComponentString("RF test fixture")) {
            @Override public AEKey loadKeyFromTag(NBTTagCompound tag) { return INSTANCE; }
            @Override public AEKey readFromPacket(net.minecraft.network.PacketBuffer packet) { return INSTANCE; }
            @Override public int getAmountPerOperation() { return 1; }
        };
        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public Object getPrimaryKey() { return this; }
        @Override public net.minecraft.util.ResourceLocation getId() { return TYPE.getId(); }
        @Override public NBTTagCompound toTag() { return new NBTTagCompound(); }
        @Override public void writeToPacket(net.minecraft.network.PacketBuffer buffer) { }
        @Override public Object getReadOnlyStack() { return this; }
        @Override public net.minecraft.nbt.NBTBase get(String component) { return null; }
        @Override protected ITextComponent computeDisplayName() { return new TextComponentString("RF test fixture"); }
        @Override public int hashCode() { return 37; }
        @Override public boolean equals(Object other) { return other==this; }
    }

    private static final class TestStorage implements MEStorageMonitor {
        private final Map<MEStorageChangeListener,Object> listeners=new IdentityHashMap<>();
        @Override public void addListener(MEStorageChangeListener listener,Object token) {
            if (listeners.putIfAbsent(listener,token)!=null) throw new IllegalStateException("Duplicate listener");
        }
        @Override public void removeListener(MEStorageChangeListener listener) { listeners.remove(listener); }
        private void changed(AEKey key,long delta) {
            if (delta==0) return;
            for (var listener : new ArrayList<>(listeners.keySet())) {
                if (!listeners.containsKey(listener)) continue;
                if (!listener.isValid(listeners.get(listener))) listeners.remove(listener);
                else listener.onStackChange(key,delta);
            }
        }
        private final Map<AEKey,Long> values=new HashMap<>();
        long capacity=Long.MAX_VALUE;
        long amount(AEKey key) { return values.getOrDefault(key,0L); }
        @Override public long insert(AEKey key,long amount,Actionable mode,IActionSource source) {
            long accepted=Math.min(amount,Math.max(0,capacity-amount(key)));
            if (mode==Actionable.MODULATE && accepted>0) { values.put(key,amount(key)+accepted); changed(key,accepted); } return accepted;
        }
        @Override public long extract(AEKey key,long amount,Actionable mode,IActionSource source) {
            long taken=Math.min(amount,amount(key));
            if (mode==Actionable.MODULATE && taken>0) { values.put(key,amount(key)-taken); changed(key,-taken); } return taken;
        }
        @Override public void getAvailableStacks(KeyCounter out) { values.forEach(out::add); }
        @Override public ITextComponent getDescription() { return new TextComponentString("Acceptance storage"); }
    }
}
